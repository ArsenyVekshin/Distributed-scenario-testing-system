package com.disttest.coordinator.temporal

import com.disttest.coordinator.temporal.state.ExecuteBlockResult
import com.disttest.coordinator.temporal.state.ExecutionPlan
import com.disttest.coordinator.temporal.state.ScenarioRunWorkflowResult
import com.disttest.coordinator.temporal.state.ScenarioStateActivities
import com.disttest.coordinator.temporal.state.SkipBlocksRequest
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

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

@WorkflowInterface
interface TestScenarioWorkflow {

    @WorkflowMethod
    fun execute(runId: String): ScenarioRunWorkflowResult
}

@io.temporal.activity.ActivityInterface
interface TestBlockActivities {

    @io.temporal.activity.ActivityMethod
    fun executeBlock(request: ExecuteBlockRequest): ExecuteBlockResult
}

class TestScenarioWorkflowImpl : TestScenarioWorkflow {

    private val stateActivities: ScenarioStateActivities = Workflow.newActivityStub(
        ScenarioStateActivities::class.java,
        ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.COORDINATOR)
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build()
    )

    private val testBlockActivities: TestBlockActivities = Workflow.newActivityStub(
        TestBlockActivities::class.java,
        ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.AGENT)
            .setStartToCloseTimeout(Duration.ofHours(1))
            .setHeartbeatTimeout(Duration.ofMinutes(5))
            .build()
    )

    override fun execute(runId: String): ScenarioRunWorkflowResult {
        val plan = stateActivities.initializeExecutionPlan(runId)
        val statuses = mutableMapOf<String, String>()
        val inFlight = mutableMapOf<String, Promise<ExecuteBlockResult>>()

        plan.blocks.forEach { statuses[it.blockConfigId] = "PENDING" }

        while (true) {
            val pending = plan.blocks.filter { statuses[it.blockConfigId] == "PENDING" }
            if (pending.isEmpty() && inFlight.isEmpty()) break

            skipBlockedBlocks(pending, statuses)
            dispatchReadyBlocks(plan, pending, statuses, inFlight)

            if (inFlight.isEmpty()) break

            Workflow.await { inFlight.values.any { it.isCompleted } }
            persistCompletedBlocks(statuses, inFlight)
        }

        return stateActivities.finalizeRun(runId)
    }

    private fun skipBlockedBlocks(
        pending: List<com.disttest.coordinator.temporal.state.BlockExecutionPlan>,
        statuses: MutableMap<String, String>,
    ) {
        val toSkip = pending.filter { block ->
            block.dependsOn.any { dep ->
                statuses[dep] in setOf("FAILED", "CANCELED", "SKIPPED", "TIMEOUT")
            }
        }
        if (toSkip.isEmpty()) return

        stateActivities.skipBlocks(SkipBlocksRequest(toSkip.map { it.blockRunId }))
        toSkip.forEach { statuses[it.blockConfigId] = "SKIPPED" }
    }

    private fun dispatchReadyBlocks(
        plan: ExecutionPlan,
        pending: List<com.disttest.coordinator.temporal.state.BlockExecutionPlan>,
        statuses: MutableMap<String, String>,
        inFlight: MutableMap<String, Promise<ExecuteBlockResult>>,
    ) {
        pending
            .filter { block -> statuses[block.blockConfigId] == "PENDING" }
            .filter { block -> block.dependsOn.all { dep -> statuses[dep] == "SUCCESS" } }
            .forEach { block ->
                stateActivities.markBlockReady(block.blockRunId)
                statuses[block.blockConfigId] = "RUNNING"
                inFlight[block.blockConfigId] = Async.function(
                    testBlockActivities::executeBlock,
                    ExecuteBlockRequest(
                        runId = plan.runId,
                        blockRunId = block.blockRunId,
                        blockConfigId = block.blockConfigId,
                        blockName = block.blockName,
                        artifactKey = plan.artifactKey,
                        imageTag = plan.imageTag,
                        parallelism = block.parallelism,
                        timeoutSeconds = block.timeoutSeconds,
                        tests = block.tests.map { test ->
                            TestCaseRequest(
                                testRunId = test.testRunId,
                                testConfigId = test.testConfigId,
                                name = test.name,
                                command = test.command,
                                timeoutSeconds = test.timeoutSeconds,
                                retries = test.retries,
                                workdir = test.workdir,
                                env = test.env,
                            )
                        },
                    )
                )
            }
    }

    private fun persistCompletedBlocks(
        statuses: MutableMap<String, String>,
        inFlight: MutableMap<String, Promise<ExecuteBlockResult>>,
    ) {
        val completed = inFlight
            .filterValues { it.isCompleted }
            .map { (blockConfigId, promise) -> blockConfigId to promise }

        completed.forEach { (blockConfigId, promise) ->
            val result = promise.get()
            stateActivities.saveBlockResult(result)
            statuses[blockConfigId] = result.status
            inFlight.remove(blockConfigId)
        }
    }
}
