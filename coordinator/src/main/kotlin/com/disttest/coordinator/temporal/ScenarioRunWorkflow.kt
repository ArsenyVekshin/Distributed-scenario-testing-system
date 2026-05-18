package com.disttest.coordinator.temporal

import com.disttest.coordinator.temporal.state.*
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.*
import java.time.Duration

// ─── Workflow interface ───────────────────────────────────────────────────────

@WorkflowInterface
interface ScenarioRunWorkflow {

    @WorkflowMethod
    fun execute(runId: String): ScenarioRunWorkflowResult

    @SignalMethod
    fun cancel()

    @QueryMethod
    fun getStatus(): String
}

// ─── Build activities interface (agent-side, build-task-queue) ────────────────

data class BuildProjectRequest(
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

@io.temporal.activity.ActivityInterface
interface BuildProjectActivities {
    @io.temporal.activity.ActivityMethod
    fun prepareAndBuild(request: BuildProjectRequest): BuildProjectResult
}

// ─── Workflow implementation ──────────────────────────────────────────────────

class ScenarioRunWorkflowImpl : ScenarioRunWorkflow {

    @Volatile
    private var canceled = false

    @Volatile
    private var currentStatus = "PENDING"

    private val stateActivities: ScenarioStateActivities = Workflow.newActivityStub(
        ScenarioStateActivities::class.java,
        ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.COORDINATOR)
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build()
    )

    private val buildActivities: BuildProjectActivities = Workflow.newActivityStub(
        BuildProjectActivities::class.java,
        ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.BUILD)
            .setStartToCloseTimeout(Duration.ofMinutes(20))
            .setHeartbeatTimeout(Duration.ofMinutes(2))
            .build()
    )

    override fun cancel() {
        canceled = true
    }

    override fun getStatus(): String = currentStatus

    override fun execute(runId: String): ScenarioRunWorkflowResult {
        currentStatus = "BUILDING"
        return try {
            val context = stateActivities.loadRunContext(runId)
            stateActivities.markRunBuilding(runId)

            val buildResult = buildActivities.prepareAndBuild(
                BuildProjectRequest(
                    runId              = context.runId,
                    projectId          = context.projectId,
                    gitlabUrl          = context.gitlabUrl,
                    branch             = context.branch,
                    accessToken        = context.accessToken,
                    scenarioConfigPath = context.scenarioConfigPath,
                    dockerfilePath     = context.dockerfilePath,
                    imageTag           = context.imageTag,
                    artifactKey        = context.artifactKey,
                )
            )

            val applied = stateActivities.applyBuildResult(buildResult)
            if (!applied.success) {
                currentStatus = "FAILED"
                return ScenarioRunWorkflowResult(runId = runId, status = "FAILED")
            }

            if (canceled) {
                stateActivities.markRunCanceled(runId)
                currentStatus = "CANCELED"
                return ScenarioRunWorkflowResult(runId = runId, status = "CANCELED")
            }

            currentStatus = "RUNNING"
            stateActivities.markRunRunning(runId)

            val testResult = Workflow.newChildWorkflowStub(
                TestScenarioWorkflow::class.java,
                ChildWorkflowOptions.newBuilder()
                    .setWorkflowId("${Workflow.getInfo().workflowId}-test")
                    .setTaskQueue(TaskQueues.COORDINATOR)
                    .build()
            ).execute(runId)

            currentStatus = testResult.status
            testResult

        } catch (e: io.temporal.failure.CanceledFailure) {
            stateActivities.markRunCanceled(runId)
            currentStatus = "CANCELED"
            ScenarioRunWorkflowResult(runId = runId, status = "CANCELED")
        } catch (e: Exception) {
            stateActivities.markRunFailed(
                MarkRunFailedRequest(
                    runId        = runId,
                    errorStage   = "INTERNAL",
                    errorMessage = e.message ?: e.toString(),
                )
            )
            currentStatus = "FAILED"
            throw e
        }
    }
}
