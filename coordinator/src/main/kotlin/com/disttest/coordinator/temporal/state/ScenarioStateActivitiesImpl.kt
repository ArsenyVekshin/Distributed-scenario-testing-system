package com.disttest.coordinator.temporal.state

import com.disttest.coordinator.model.ScenarioRepository
import com.disttest.coordinator.run.*
import com.disttest.coordinator.scenario.ScenarioParser
import com.disttest.coordinator.scenario.ScenarioValidationException
import com.disttest.coordinator.storage.ConfigStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ScenarioStateActivitiesImpl(
    private val runRepository: ScenarioRunRepository,
    private val blockRunRepository: BlockRunRepository,
    private val testRunRepository: TestRunRepository,
    private val scenarioRepository: ScenarioRepository,
    private val configStore: ConfigStore,
    private val scenarioParser: ScenarioParser,
) : ScenarioStateActivities {

    private val log = LoggerFactory.getLogger(ScenarioStateActivitiesImpl::class.java)

    @Transactional
    override fun loadRunContext(runId: String): RunContext {
        val run = findRun(runId)
        val project = run.project
        val imageTag = "disttest/run-$runId"
        val artifactKey = "runs/$runId/image.tar"
        return RunContext(
            runId              = runId,
            projectId          = project.id,
            gitlabUrl          = project.gitlabUrl,
            branch             = project.branch,
            accessToken        = project.accessToken,
            scenarioConfigPath = project.scenarioConfigPath,
            dockerfilePath     = project.dockerfilePath,
            imageTag           = imageTag,
            artifactKey        = artifactKey,
        )
    }

    @Transactional
    override fun markRunBuilding(runId: String) {
        val run = findRun(runId)
        run.status = ScenarioRunStatus.BUILDING
        runRepository.save(run)
        log.info("Run '{}' → BUILDING", runId)
    }

    @Transactional
    override fun applyBuildResult(result: BuildProjectResult): BuildApplyResult {
        val run = findRun(result.runId)
        val now = Instant.now()

        run.buildStdout = result.stdout.takeLast(1_048_576)
        run.buildStderr = result.stderr.takeLast(1_048_576)
        run.buildStartedAt = result.startedAt
        run.buildFinishedAt = result.finishedAt
        run.buildDurationMs = result.durationMs

        if (!result.success) {
            run.status       = ScenarioRunStatus.FAILED
            run.errorStage   = RunErrorStage.valueOf(result.errorStage ?: "BUILD")
            run.errorMessage = result.errorMessage
            run.finishedAt   = now
            run.durationMs   = run.startedAt?.let { now.toEpochMilli() - it.toEpochMilli() }
            runRepository.save(run)
            log.warn("Run '{}' build FAILED: stage={}, msg={}", result.runId, result.errorStage, result.errorMessage)
            return BuildApplyResult(success = false)
        }

        val yaml = result.scenarioYaml
            ?: return BuildApplyResult(success = false).also {
                run.status       = ScenarioRunStatus.FAILED
                run.errorStage   = RunErrorStage.CONFIG_VALIDATION
                run.errorMessage = "Scenario YAML is missing from build result"
                run.finishedAt   = now
                run.durationMs   = run.startedAt?.let { now.toEpochMilli() - it.toEpochMilli() }
                runRepository.save(run)
            }

        val scenarioEntity = try {
            val parsed = scenarioParser.parse(yaml)
            val project = run.project

            // Find existing scenario for this project, or use parsed as new one
            val existingList = scenarioRepository.findByProjectId(project.id)
            val scenario = if (existingList.isNotEmpty()) {
                val existing = existingList.first()
                existing.name          = parsed.name
                existing.description   = parsed.description
                existing.timeoutSeconds = parsed.timeoutSeconds
                existing.lastCommitSha  = result.commitSha
                existing.lastImportedAt = now
                existing.blocks.clear()
                parsed.blocks.forEach { b ->
                    b.scenario = existing
                    existing.blocks.add(b)
                }
                existing.blocksTotal = parsed.blocks.size
                existing
            } else {
                parsed.project        = project
                parsed.lastCommitSha  = result.commitSha
                parsed.lastImportedAt = now
                parsed.blocksTotal    = parsed.blocks.size
                parsed
            }

            val savedScenario = scenarioRepository.save(scenario)

            // Save YAML to MinIO: scenarios/{id}/config.yaml and runs/{runId}/config.yaml
            val scenarioKey = "scenarios/${savedScenario.id}/config.yaml"
            configStore.upload(scenarioKey, yaml)
            configStore.upload("runs/${result.runId}/config.yaml", yaml)
            savedScenario.configYamlKey = scenarioKey
            scenarioRepository.save(savedScenario)

            savedScenario
        } catch (e: ScenarioValidationException) {
            run.status       = ScenarioRunStatus.FAILED
            run.errorStage   = RunErrorStage.CONFIG_VALIDATION
            run.errorMessage = e.errors.joinToString("; ") { "[${it.path}] ${it.message}" }
            run.finishedAt   = now
            run.durationMs   = run.startedAt?.let { now.toEpochMilli() - it.toEpochMilli() }
            runRepository.save(run)
            log.warn("Run '{}' YAML validation failed: {}", result.runId, run.errorMessage)
            return BuildApplyResult(success = false)
        }

        run.scenario     = scenarioEntity
        run.commitSha    = result.commitSha
        run.artifactKey  = result.artifactKey
        run.imageTag     = result.imageTag
        runRepository.save(run)

        log.info("Run '{}' build applied: scenario='{}', commit={}", result.runId, scenarioEntity.id, result.commitSha)
        return BuildApplyResult(success = true, scenarioId = scenarioEntity.id)
    }

    @Transactional
    override fun markRunRunning(runId: String) {
        val run = findRun(runId)
        run.status = ScenarioRunStatus.RUNNING
        run.executionStartedAt = Instant.now()
        runRepository.save(run)
        log.info("Run '{}' → RUNNING", runId)
    }

    @Transactional
    override fun initializeExecutionPlan(runId: String): ExecutionPlan {
        val run = findRun(runId)
        val scenario = scenarioRepository.loadAggregateById(run.scenario!!.id)
            .orElseThrow { IllegalStateException("Scenario not found for run '$runId'") }

        val blocks = scenario.blocks.distinctBy { it.configId }

        val blockPlans = blocks.map { block ->
            val blockRun = BlockRunEntity(
                scenarioRun    = run,
                blockConfigId  = block.configId,
                blockName      = block.name,
                status         = BlockRunStatus.PENDING,
                parallelism    = block.parallelism,
                timeoutSeconds = block.timeoutSeconds.toLong(),
                retries        = block.retries,
            )
            val savedBlock = blockRunRepository.save(blockRun)

            val tests = block.tests.distinctBy { it.configId }

            val testPlans = tests.map { test ->
                val testRun = TestRunEntity(
                    blockRun       = savedBlock,
                    testConfigId   = test.configId,
                    testName       = test.name,
                    command        = test.command,
                    status         = TestRunStatus.PENDING,
                    timeoutSeconds = test.timeoutSeconds.toLong(),
                    retries        = test.retries,
                )
                val savedTest = testRunRepository.save(testRun)
                TestExecutionPlan(
                    testRunId      = savedTest.id,
                    testConfigId   = test.configId,
                    name           = test.name,
                    command        = test.command,
                    timeoutSeconds = test.timeoutSeconds.toLong(),
                    retries        = test.retries,
                    workdir        = test.workdir.ifBlank { null },
                    env            = test.env.toMap(),
                )
            }

            BlockExecutionPlan(
                blockRunId     = savedBlock.id,
                blockConfigId  = block.configId,
                blockName      = block.name,
                dependsOn      = block.dependsOn.toList(),
                parallelism    = block.parallelism,
                timeoutSeconds = block.timeoutSeconds.toLong(),
                retries        = block.retries,
                tests          = testPlans,
            )
        }

        run.blocksTotal = blocks.size
        run.testsTotal  = blocks.sumOf { it.tests.distinctBy { test -> test.configId }.size }
        runRepository.save(run)

        log.info("Run '{}': initialized {} blocks, {} tests", runId, blockPlans.size, run.testsTotal)
        return ExecutionPlan(
            runId       = runId,
            artifactKey = run.artifactKey ?: "runs/$runId/image.tar",
            imageTag    = run.imageTag ?: "disttest/run-$runId",
            blocks      = blockPlans,
        )
    }

    @Transactional
    override fun markBlockReady(blockRunId: String) {
        val block = findBlock(blockRunId)
        block.status = BlockRunStatus.READY
        blockRunRepository.save(block)
    }

    @Transactional
    override fun markBlockRunning(request: MarkBlockRunningRequest) {
        val block = findBlock(request.blockRunId)
        block.status    = BlockRunStatus.RUNNING
        block.agentId   = request.agentId
        block.startedAt = Instant.now()
        blockRunRepository.save(block)
    }

    @Transactional
    override fun saveBlockResult(result: ExecuteBlockResult) {
        val block = findBlock(result.blockRunId)
        block.status       = BlockRunStatus.valueOf(result.status)
        block.agentId      = result.agentId
        block.startedAt    = result.startedAt
        block.finishedAt   = result.finishedAt
        block.durationMs   = result.durationMs
        block.imagePrepareStartedAt = result.imagePrepareStartedAt
        block.imagePrepareFinishedAt = result.imagePrepareFinishedAt
        block.imagePrepareDurationMs = result.imagePrepareDurationMs
        block.errorMessage = result.errorMessage
        blockRunRepository.save(block)

        result.tests.distinctBy { it.testRunId }.forEach { testResult ->
            val testRun = testRunRepository.findById(testResult.testRunId).orElse(null) ?: return@forEach
            testRun.status       = TestRunStatus.valueOf(testResult.status)
            testRun.exitCode     = testResult.exitCode
            testRun.attempts     = testResult.attempts
            testRun.startedAt    = testResult.startedAt
            testRun.finishedAt   = testResult.finishedAt
            testRun.durationMs   = testResult.durationMs
            testRun.stdout       = testResult.stdout.takeLast(1_048_576)
            testRun.stderr       = testResult.stderr.takeLast(1_048_576)
            testRun.errorMessage = testResult.errorMessage
            testRunRepository.save(testRun)
        }

        updateRunCounters(block.scenarioRun.id)
    }

    @Transactional
    override fun skipBlocks(request: SkipBlocksRequest) {
        request.blockRunIds.forEach { blockRunId ->
            val block = findBlock(blockRunId)
            block.status = BlockRunStatus.SKIPPED
            blockRunRepository.save(block)

            // Skip all test runs for this block
            testRunRepository.findByBlockRunId(blockRunId).forEach { testRun ->
                testRun.status = TestRunStatus.CANCELED
                testRunRepository.save(testRun)
            }
        }
    }

    @Transactional
    override fun finalizeRun(runId: String): ScenarioRunWorkflowResult {
        updateRunCounters(runId)
        val run = findRun(runId)
        val now = Instant.now()

        val finalStatus = when {
            run.blocksTimeout > 0  -> ScenarioRunStatus.TIMEOUT
            run.blocksFailed > 0   -> ScenarioRunStatus.FAILED
            run.blocksSuccess == run.blocksTotal -> ScenarioRunStatus.SUCCESS
            else                   -> ScenarioRunStatus.FAILED
        }

        run.status     = finalStatus
        run.finishedAt = now
        run.durationMs = run.startedAt?.let { now.toEpochMilli() - it.toEpochMilli() }
        runRepository.save(run)

        log.info("Run '{}' finalized → {}", runId, finalStatus)
        return ScenarioRunWorkflowResult(runId = runId, status = finalStatus.name)
    }

    @Transactional
    override fun markRunFailed(request: MarkRunFailedRequest) {
        val run = findRun(request.runId)
        val now = Instant.now()
        run.status       = ScenarioRunStatus.FAILED
        run.errorStage   = RunErrorStage.valueOf(request.errorStage)
        run.errorMessage = request.errorMessage
        run.finishedAt   = now
        run.durationMs   = run.startedAt?.let { now.toEpochMilli() - it.toEpochMilli() }
        runRepository.save(run)
        log.warn("Run '{}' → FAILED (stage={}, msg={})", request.runId, request.errorStage, request.errorMessage)
    }

    @Transactional
    override fun markRunCanceled(runId: String) {
        val run = findRun(runId)
        val now = Instant.now()
        run.status     = ScenarioRunStatus.CANCELED
        run.finishedAt = now
        run.durationMs = run.startedAt?.let { now.toEpochMilli() - it.toEpochMilli() }
        runRepository.save(run)
        log.info("Run '{}' → CANCELED", runId)
    }

    @Transactional
    override fun markRunTimeout(runId: String) {
        val run = findRun(runId)
        val now = Instant.now()
        run.status     = ScenarioRunStatus.TIMEOUT
        run.finishedAt = now
        run.durationMs = run.startedAt?.let { now.toEpochMilli() - it.toEpochMilli() }
        runRepository.save(run)
        log.info("Run '{}' → TIMEOUT", runId)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun findRun(runId: String) =
        runRepository.findById(runId).orElseThrow { IllegalStateException("Run '$runId' not found") }

    private fun findBlock(blockRunId: String) =
        blockRunRepository.findById(blockRunId).orElseThrow { IllegalStateException("BlockRun '$blockRunId' not found") }

    private fun updateRunCounters(runId: String) {
        val run = findRun(runId)
        val blocks = blockRunRepository.findByScenarioRunId(runId)

        run.blocksSuccess  = blocks.count { it.status == BlockRunStatus.SUCCESS }
        run.blocksFailed   = blocks.count { it.status == BlockRunStatus.FAILED }
        run.blocksSkipped  = blocks.count { it.status == BlockRunStatus.SKIPPED }
        run.blocksCanceled = blocks.count { it.status == BlockRunStatus.CANCELED }
        run.blocksTimeout  = blocks.count { it.status == BlockRunStatus.TIMEOUT }

        val allTests = blocks.flatMap { testRunRepository.findByBlockRunId(it.id) }
        run.testsPassed  = allTests.count { it.status == TestRunStatus.PASSED }
        run.testsFailed  = allTests.count { it.status == TestRunStatus.FAILED }
        run.testsError   = allTests.count { it.status == TestRunStatus.ERROR }
        run.testsTimeout = allTests.count { it.status == TestRunStatus.TIMEOUT }

        runRepository.save(run)
    }
}
