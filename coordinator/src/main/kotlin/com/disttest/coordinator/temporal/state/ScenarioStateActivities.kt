package com.disttest.coordinator.temporal.state

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import java.time.Instant

// ─── DTOs ────────────────────────────────────────────────────────────────────

data class RunContext(
    val runId: String,
    val projectId: String,
    val gitlabUrl: String,
    val branch: String,
    val accessToken: String,
    val scenarioConfigPath: String,
    val dockerfilePath: String,
    val imageTag: String,
    val artifactKey: String,
)

data class BuildProjectResult(
    val runId: String,
    val success: Boolean,
    val commitSha: String? = null,
    val scenarioYaml: String? = null,
    val artifactKey: String? = null,
    val imageTag: String? = null,
    val stdout: String = "",
    val stderr: String = "",
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val durationMs: Long? = null,
    val errorStage: String? = null,
    val errorMessage: String? = null,
)

data class BuildApplyResult(
    val success: Boolean,
    val scenarioId: String? = null,
)

data class ExecutionPlan(
    val runId: String,
    val artifactKey: String,
    val imageTag: String,
    val blocks: List<BlockExecutionPlan>,
)

data class BlockExecutionPlan(
    val blockRunId: String,
    val blockConfigId: String,
    val blockName: String,
    val dependsOn: List<String>,
    val parallelism: Int,
    val timeoutSeconds: Long,
    val retries: Int,
    val tests: List<TestExecutionPlan>,
)

data class TestExecutionPlan(
    val testRunId: String,
    val testConfigId: String,
    val name: String,
    val command: String,
    val timeoutSeconds: Long,
    val retries: Int,
    val workdir: String?,
    val env: Map<String, String>,
)

data class MarkBlockRunningRequest(
    val blockRunId: String,
    val agentId: String,
)

data class SkipBlocksRequest(
    val blockRunIds: List<String>,
)

data class MarkRunFailedRequest(
    val runId: String,
    val errorStage: String,
    val errorMessage: String,
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

data class ScenarioRunWorkflowResult(
    val runId: String,
    val status: String,
)

// ─── Activity interface ───────────────────────────────────────────────────────

/**
 * Coordinator-side activities for persisting workflow state to PostgreSQL.
 * Registered on [TaskQueues.COORDINATOR]. Workflow calls these via activity stubs.
 * DB interaction happens only through these activities, never directly in the workflow.
 */
@ActivityInterface
interface ScenarioStateActivities {

    /** Loads project + run context needed to start the workflow. */
    @ActivityMethod
    fun loadRunContext(runId: String): RunContext

    /** Sets run status to BUILDING. */
    @ActivityMethod
    fun markRunBuilding(runId: String)

    /**
     * Processes build result: if success — parses YAML, creates/updates ScenarioEntity,
     * saves YAML to MinIO, updates run fields. If failure — marks run FAILED.
     */
    @ActivityMethod
    fun applyBuildResult(result: BuildProjectResult): BuildApplyResult

    /** Sets run status to RUNNING (test stage begins). */
    @ActivityMethod
    fun markRunRunning(runId: String)

    /**
     * Creates BlockRunEntity + TestRunEntity records for the scenario.
     * Returns the execution plan for the workflow DAG scheduler.
     */
    @ActivityMethod
    fun initializeExecutionPlan(runId: String): ExecutionPlan

    /** Sets block status to READY. */
    @ActivityMethod
    fun markBlockReady(blockRunId: String)

    /** Sets block status to RUNNING, records agentId. */
    @ActivityMethod
    fun markBlockRunning(request: MarkBlockRunningRequest)

    /** Saves block and test results, updates run counters. */
    @ActivityMethod
    fun saveBlockResult(result: ExecuteBlockResult)

    /** Sets blocks to SKIPPED (when dependencies failed). */
    @ActivityMethod
    fun skipBlocks(request: SkipBlocksRequest)

    /** Computes final run status (SUCCESS/FAILED/TIMEOUT) and persists it. */
    @ActivityMethod
    fun finalizeRun(runId: String): ScenarioRunWorkflowResult

    /** Marks run as FAILED with error details. */
    @ActivityMethod
    fun markRunFailed(request: MarkRunFailedRequest)

    /** Marks run as CANCELED. */
    @ActivityMethod
    fun markRunCanceled(runId: String)

    /** Marks run as TIMEOUT. */
    @ActivityMethod
    fun markRunTimeout(runId: String)
}
