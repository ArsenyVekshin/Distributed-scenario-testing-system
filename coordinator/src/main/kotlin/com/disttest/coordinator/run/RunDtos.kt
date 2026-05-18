package com.disttest.coordinator.run

import java.time.Instant

data class RunSummaryDto(
    val id: String,
    val projectId: String,
    val status: String,
    val branch: String,
    val commitSha: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val durationMs: Long?,
    val blocksTotal: Int,
    val blocksSuccess: Int,
    val blocksFailed: Int,
    val testsTotal: Int,
    val testsPassed: Int,
    val testsFailed: Int,
)

data class RunDetailDto(
    val id: String,
    val projectId: String,
    val scenarioId: String?,
    val status: String,
    val branch: String,
    val commitSha: String?,
    val artifactKey: String?,
    val imageTag: String?,
    val errorStage: String?,
    val errorMessage: String?,
    val buildStdout: String?,
    val buildStderr: String?,
    val timing: RunTimingDto,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val durationMs: Long?,
    val summary: RunSummaryCountsDto,
    val blocks: List<BlockRunDto>,
)

data class RunTimingDto(
    val runStartedAt: Instant?,
    val buildStartedAt: Instant?,
    val buildFinishedAt: Instant?,
    val buildDurationMs: Long?,
    val executionStartedAt: Instant?,
    val runFinishedAt: Instant?,
    val totalDurationMs: Long?,
    val preparationDurationMs: Long?,
)

data class RunSummaryCountsDto(
    val blocksTotal: Int,
    val blocksSuccess: Int,
    val blocksFailed: Int,
    val blocksSkipped: Int,
    val blocksCanceled: Int,
    val blocksTimeout: Int,
    val testsTotal: Int,
    val testsPassed: Int,
    val testsFailed: Int,
    val testsError: Int,
    val testsTimeout: Int,
)

data class BlockRunDto(
    val id: String,
    val blockConfigId: String,
    val blockName: String,
    val dependsOn: List<String>,
    val status: String,
    val agentId: String?,
    val parallelism: Int,
    val timeoutSeconds: Long,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val durationMs: Long?,
    val imagePrepareStartedAt: Instant?,
    val imagePrepareFinishedAt: Instant?,
    val imagePrepareDurationMs: Long?,
    val errorMessage: String?,
    val tests: List<TestRunDto>,
)

data class TestRunDto(
    val id: String,
    val testConfigId: String,
    val testName: String,
    val command: String,
    val status: String,
    val exitCode: Int?,
    val attempts: Int,
    val stdout: String?,
    val stderr: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val durationMs: Long?,
    val errorMessage: String?,
)

data class StartRunResponse(
    val id: String,
    val projectId: String,
    val status: String,
    val branch: String,
    val startedAt: Instant?,
)

data class MarkBlockStartedRequest(
    val agentId: String,
)

fun ScenarioRunEntity.toSummaryDto() = RunSummaryDto(
    id           = id,
    projectId    = project.id,
    status       = status.name,
    branch       = branch,
    commitSha    = commitSha,
    startedAt    = startedAt,
    finishedAt   = finishedAt,
    durationMs   = durationMs,
    blocksTotal   = blocksTotal,
    blocksSuccess = blocksSuccess,
    blocksFailed  = blocksFailed,
    testsTotal    = testsTotal,
    testsPassed   = testsPassed,
    testsFailed   = testsFailed,
)

fun ScenarioRunEntity.toDetailDto(
    blocks: List<BlockRunEntity>,
    testsByBlock: Map<String, List<TestRunEntity>>,
    dependsOnByBlockConfigId: Map<String, List<String>> = emptyMap(),
) = RunDetailDto(
    id           = id,
    projectId    = project.id,
    scenarioId   = scenario?.id,
    status       = status.name,
    branch       = branch,
    commitSha    = commitSha,
    artifactKey  = artifactKey,
    imageTag     = imageTag,
    errorStage   = errorStage?.name,
    errorMessage = errorMessage,
    buildStdout  = buildStdout,
    buildStderr  = buildStderr,
    timing = RunTimingDto(
        runStartedAt          = startedAt,
        buildStartedAt        = buildStartedAt,
        buildFinishedAt       = buildFinishedAt,
        buildDurationMs       = buildDurationMs,
        executionStartedAt    = executionStartedAt,
        runFinishedAt         = finishedAt,
        totalDurationMs       = durationMs,
        preparationDurationMs = startedAt?.let { start ->
            executionStartedAt?.toEpochMilli()?.minus(start.toEpochMilli())
        },
    ),
    startedAt    = startedAt,
    finishedAt   = finishedAt,
    durationMs   = durationMs,
    summary = RunSummaryCountsDto(
        blocksTotal   = blocksTotal,
        blocksSuccess = blocksSuccess,
        blocksFailed  = blocksFailed,
        blocksSkipped = blocksSkipped,
        blocksCanceled = blocksCanceled,
        blocksTimeout = blocksTimeout,
        testsTotal    = testsTotal,
        testsPassed   = testsPassed,
        testsFailed   = testsFailed,
        testsError    = testsError,
        testsTimeout  = testsTimeout,
    ),
    blocks = blocks.map { block ->
        val tests = testsByBlock[block.id] ?: emptyList()
        block.toDto(tests, dependsOnByBlockConfigId[block.blockConfigId] ?: emptyList())
    }
)

fun BlockRunEntity.toDto(tests: List<TestRunEntity>, dependsOn: List<String> = emptyList()) = BlockRunDto(
    id             = id,
    blockConfigId  = blockConfigId,
    blockName      = blockName,
    dependsOn      = dependsOn,
    status         = status.name,
    agentId        = agentId,
    parallelism    = parallelism,
    timeoutSeconds = timeoutSeconds,
    startedAt      = startedAt,
    finishedAt     = finishedAt,
    durationMs     = durationMs,
    imagePrepareStartedAt = imagePrepareStartedAt,
    imagePrepareFinishedAt = imagePrepareFinishedAt,
    imagePrepareDurationMs = imagePrepareDurationMs,
    errorMessage   = errorMessage,
    tests          = tests.map { it.toDto() },
)

fun TestRunEntity.toDto() = TestRunDto(
    id           = id,
    testConfigId = testConfigId,
    testName     = testName,
    command      = command,
    status       = status.name,
    exitCode     = exitCode,
    attempts     = attempts,
    stdout       = stdout,
    stderr       = stderr,
    startedAt    = startedAt,
    finishedAt   = finishedAt,
    durationMs   = durationMs,
    errorMessage = errorMessage,
)
