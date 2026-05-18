package com.disttest.agent.temporal

import com.disttest.agent.docker.DockerImageCache
import com.disttest.agent.nodes.AgentNodeClient
import com.disttest.agent.process.ProcessRunner
import com.disttest.agent.storage.S3ArtifactClient
import io.temporal.activity.Activity
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists

// ─── DTOs (must match coordinator's TestScenarioWorkflow DTOs) ────────────────

data class ExecuteBlockRequest(
    val runId: String,
    val blockRunId: String,
    val blockConfigId: String,
    val blockName: String,
    val artifactKey: String,
    val imageTag: String,
    val parallelism: Int,
    val timeoutSeconds: Long,
    val tests: List<TestCaseRequest>,
)

data class TestCaseRequest(
    val testRunId: String,
    val testConfigId: String,
    val name: String,
    val command: String,
    val timeoutSeconds: Long,
    val retries: Int,
    val workdir: String?,
    val env: Map<String, String>,
)

data class ExecuteBlockResult(
    val runId: String,
    val blockRunId: String,
    val blockConfigId: String,
    val status: String,
    val agentId: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val durationMs: Long,
    val imagePrepareStartedAt: Instant? = null,
    val imagePrepareFinishedAt: Instant? = null,
    val imagePrepareDurationMs: Long? = null,
    val errorMessage: String? = null,
    val tests: List<TestCaseResult>,
)

data class TestCaseResult(
    val testRunId: String,
    val testConfigId: String,
    val status: String,
    val exitCode: Int? = null,
    val attempts: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
    val durationMs: Long,
    val stdout: String = "",
    val stderr: String = "",
    val errorMessage: String? = null,
)

// ─── Interface ────────────────────────────────────────────────────────────────

@ActivityInterface
interface TestBlockActivities {
    @ActivityMethod
    fun executeBlock(request: ExecuteBlockRequest): ExecuteBlockResult
}

// ─── Implementation ───────────────────────────────────────────────────────────

@Component
class TestBlockActivitiesImpl(
    private val dockerCache: DockerImageCache,
    private val s3: S3ArtifactClient,
    private val nodeClient: AgentNodeClient,
) : TestBlockActivities {

    private val log = LoggerFactory.getLogger(TestBlockActivitiesImpl::class.java)

    override fun executeBlock(request: ExecuteBlockRequest): ExecuteBlockResult {
        val agentId = nodeClient.agentId
        val blockStartedAt = Instant.now()
        nodeClient.blockStarted(request.blockRunId, request.blockConfigId)

        try {
            log.info(
                "Agent '{}' executing block '{}' ({} tests, parallelism={})",
                agentId, request.blockName, request.tests.size, request.parallelism
            )

            Activity.getExecutionContext().heartbeat("ensuring image")

            // 1. Ensure Docker image is available
            val imagePrepareStartedAt = Instant.now()
            ensureImage(request.imageTag, request.artifactKey, request.runId)

            // 2. Evict old managed images if needed
            dockerCache.evictIfNeeded()
            val imagePrepareFinishedAt = Instant.now()
            val imagePrepareDurationMs = imagePrepareFinishedAt.toEpochMilli() - imagePrepareStartedAt.toEpochMilli()

            val blockTimeoutDeadline: Long? = if (request.timeoutSeconds > 0)
                System.currentTimeMillis() + request.timeoutSeconds * 1000
            else null

            val blockTimedOut = AtomicBoolean(false)

            // 3. Execute tests with parallelism limit
            val executor = Executors.newFixedThreadPool(request.parallelism.coerceAtLeast(1))
            val testFutures = request.tests.map { testReq ->
                testReq to executor.submit(Callable {
                    if (blockTimeoutDeadline != null && System.currentTimeMillis() >= blockTimeoutDeadline) {
                        blockTimedOut.set(true)
                        return@Callable timeoutTestResult(testReq, "Block timeout reached before test started")
                    }

                    val remainingBlockMs = blockTimeoutDeadline?.let { it - System.currentTimeMillis() }
                    runTestWithRetries(testReq, request.imageTag, remainingBlockMs, blockTimedOut)
                })
            }

            executor.shutdown()

            val testResults = try {
                val completed = if (blockTimeoutDeadline != null) {
                    val remaining = (blockTimeoutDeadline - System.currentTimeMillis()).coerceAtLeast(1000)
                    executor.awaitTermination(remaining, TimeUnit.MILLISECONDS)
                } else {
                    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                }

                if (!completed) {
                    blockTimedOut.set(true)
                    executor.shutdownNow()
                }

                testFutures.map { (testReq, future) ->
                    collectTestResult(testReq, future, blockTimedOut.get())
                }
            } catch (e: Exception) {
                log.error("Block '{}' executor error: {}", request.blockName, e.message)
                executor.shutdownNow()
                testFutures.map { (testReq, future) ->
                    runCatching { collectTestResult(testReq, future, blockTimedOut.get()) }
                        .getOrElse { errorTestResult(testReq, "Block executor error: ${e.message ?: e::class.simpleName}") }
                }
            }

            val blockFinishedAt = Instant.now()
            val blockDurationMs = blockFinishedAt.toEpochMilli() - blockStartedAt.toEpochMilli()

            val blockStatus = computeBlockStatus(testResults, blockTimedOut.get())

            log.info(
                "Block '{}' completed: status={}, duration={}ms",
                request.blockName, blockStatus, blockDurationMs
            )

            return ExecuteBlockResult(
                runId         = request.runId,
                blockRunId    = request.blockRunId,
                blockConfigId = request.blockConfigId,
                status        = blockStatus,
                agentId       = agentId,
                startedAt     = blockStartedAt,
                finishedAt    = blockFinishedAt,
                durationMs    = blockDurationMs,
                imagePrepareStartedAt = imagePrepareStartedAt,
                imagePrepareFinishedAt = imagePrepareFinishedAt,
                imagePrepareDurationMs = imagePrepareDurationMs,
                tests         = testResults,
            )
        } finally {
            nodeClient.idle()
        }
    }

    private fun runTestWithRetries(
        testReq: TestCaseRequest,
        imageTag: String,
        remainingBlockMs: Long?,
        blockTimedOut: AtomicBoolean,
    ): TestCaseResult {
        val maxAttempts = testReq.retries + 1
        var lastResult: TestCaseResult? = null

        for (attempt in 1..maxAttempts) {
            if (blockTimedOut.get()) {
                return canceledTestResult(testReq)
            }

            val effectiveTimeout = if (remainingBlockMs != null)
                minOf(testReq.timeoutSeconds, remainingBlockMs / 1000).coerceAtLeast(1)
            else
                testReq.timeoutSeconds

            lastResult = runSingleTest(testReq, imageTag, attempt, effectiveTimeout)

            if (lastResult.status == "PASSED") return lastResult
            if (lastResult.status == "TIMEOUT" || lastResult.status == "ERROR") return lastResult
            // FAILED → retry if attempts remain
        }

        return lastResult!!
    }

    private fun runSingleTest(
        testReq: TestCaseRequest,
        imageTag: String,
        attempt: Int,
        timeoutSeconds: Long,
    ): TestCaseResult {
        val startedAt = Instant.now()
        log.debug("  Running test '{}' attempt {}/{}", testReq.name, attempt, testReq.retries + 1)

        val command = buildDockerRunCommand(imageTag, testReq)

        try {
            val result = ProcessRunner.run(
                command        = command,
                timeoutSeconds = timeoutSeconds,
            )
            val finishedAt = Instant.now()
            val durationMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli()

            val status = when {
                result.timedOut        -> "TIMEOUT"
                result.exitCode == 0   -> "PASSED"
                else                   -> "FAILED"
            }

            return TestCaseResult(
                testRunId    = testReq.testRunId,
                testConfigId = testReq.testConfigId,
                status       = status,
                exitCode     = result.exitCode,
                attempts     = attempt,
                startedAt    = startedAt,
                finishedAt   = finishedAt,
                durationMs   = durationMs,
                stdout       = result.stdout,
                stderr       = result.stderr,
            )
        } catch (e: Exception) {
            val finishedAt = Instant.now()
            log.error("Test '{}' infrastructure error: {}", testReq.name, e.message)
            return TestCaseResult(
                testRunId    = testReq.testRunId,
                testConfigId = testReq.testConfigId,
                status       = "ERROR",
                attempts     = attempt,
                startedAt    = startedAt,
                finishedAt   = finishedAt,
                durationMs   = finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
                errorMessage = e.message,
            )
        }
    }

    private fun buildDockerRunCommand(imageTag: String, testReq: TestCaseRequest): List<String> {
        val cmd = mutableListOf("docker", "run", "--rm")
        testReq.env.forEach { (k, v) -> cmd += listOf("-e", "$k=$v") }
        if (!testReq.workdir.isNullOrBlank()) {
            cmd += listOf("-w", testReq.workdir)
        }
        cmd += imageTag
        cmd += listOf("/bin/sh", "-c", testReq.command)
        return cmd
    }

    private fun ensureImage(imageTag: String, artifactKey: String, runId: String) {
        if (dockerCache.imageExists(imageTag)) {
            log.debug("Image '{}' already cached on agent", imageTag)
            return
        }

        log.info("Image '{}' not found, downloading from MinIO key '{}'", imageTag, artifactKey)
        val tarPath = Path.of("/tmp/disttest/images/$runId-image.tar")
        try {
            Files.createDirectories(tarPath.parent)
            s3.downloadObject(artifactKey, tarPath)
            dockerCache.loadImage(tarPath)
        } finally {
            tarPath.deleteIfExists()
        }
    }

    private fun computeBlockStatus(tests: List<TestCaseResult>, blockTimedOut: Boolean): String = when {
        blockTimedOut                            -> "TIMEOUT"
        tests.any { it.status == "ERROR" }       -> "FAILED"
        tests.any { it.status == "TIMEOUT" }     -> "TIMEOUT"
        tests.any { it.status == "FAILED" }      -> "FAILED"
        tests.all { it.status == "PASSED" }      -> "SUCCESS"
        else                                     -> "FAILED"
    }

    private fun collectTestResult(
        testReq: TestCaseRequest,
        future: Future<TestCaseResult>,
        blockTimedOut: Boolean,
    ): TestCaseResult {
        if (future.isDone) {
            return runCatching { future.get() }
                .getOrElse { errorTestResult(testReq, "Test worker error: ${it.message ?: it::class.simpleName}") }
        }

        if (blockTimedOut) {
            future.cancel(true)
            return timeoutTestResult(testReq, "Block timeout reached before test completed")
        }

        return runCatching { future.get(100, TimeUnit.MILLISECONDS) }
            .getOrElse { errorTestResult(testReq, "Test result was not available: ${it.message ?: it::class.simpleName}") }
    }

    private fun canceledTestResult(testReq: TestCaseRequest): TestCaseResult {
        val now = Instant.now()
        return TestCaseResult(
            testRunId    = testReq.testRunId,
            testConfigId = testReq.testConfigId,
            status       = "CANCELED",
            attempts     = 0,
            startedAt    = now,
            finishedAt   = now,
            durationMs   = 0,
        )
    }

    private fun timeoutTestResult(testReq: TestCaseRequest, message: String): TestCaseResult {
        val now = Instant.now()
        return TestCaseResult(
            testRunId    = testReq.testRunId,
            testConfigId = testReq.testConfigId,
            status       = "TIMEOUT",
            attempts     = 0,
            startedAt    = now,
            finishedAt   = now,
            durationMs   = 0,
            errorMessage = message,
        )
    }

    private fun errorTestResult(testReq: TestCaseRequest, message: String): TestCaseResult {
        val now = Instant.now()
        return TestCaseResult(
            testRunId    = testReq.testRunId,
            testConfigId = testReq.testConfigId,
            status       = "ERROR",
            attempts     = 0,
            startedAt    = now,
            finishedAt   = now,
            durationMs   = 0,
            errorMessage = message,
        )
    }

}
