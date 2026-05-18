package com.disttest.coordinator.run

import com.disttest.coordinator.project.ProjectRepository
import com.disttest.coordinator.model.ScenarioRepository
import com.disttest.coordinator.temporal.ScenarioRunWorkflow
import com.disttest.coordinator.temporal.TaskQueues
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class RunService(
    private val projectRepository: ProjectRepository,
    private val runRepository: ScenarioRunRepository,
    private val blockRunRepository: BlockRunRepository,
    private val testRunRepository: TestRunRepository,
    private val scenarioRepository: ScenarioRepository,
    private val workflowClient: WorkflowClient,
) {
    private val log = LoggerFactory.getLogger(RunService::class.java)

    fun listByProject(projectId: String): List<ScenarioRunEntity> =
        runRepository.findByProjectIdOrderByStartedAtDesc(projectId)

    fun get(runId: String): ScenarioRunEntity =
        runRepository.findById(runId).orElseThrow { NoSuchElementException("Run '$runId' not found") }

    fun getDetail(runId: String): RunDetailDto {
        val run = get(runId)
        val blocks = blockRunRepository.findByScenarioRunId(runId)
        val testsByBlock = blocks.associate { block ->
            block.id to testRunRepository.findByBlockRunId(block.id)
        }
        val dependsOnByBlockConfigId = run.scenario?.id
            ?.let { scenarioRepository.loadAggregateById(it).orElse(null) }
            ?.blocks
            ?.distinctBy { it.configId }
            ?.associate { block -> block.configId to block.dependsOn.distinct() }
            ?: emptyMap()

        return run.toDetailDto(blocks, testsByBlock, dependsOnByBlockConfigId)
    }

    @Transactional
    fun startRun(projectId: String): ScenarioRunEntity {
        val project = projectRepository.findById(projectId).orElseThrow {
            NoSuchElementException("Project '$projectId' not found")
        }

        val run = ScenarioRunEntity(
            project    = project,
            status     = ScenarioRunStatus.PENDING,
            branch     = project.branch,
            startedAt  = Instant.now(),
        )
        val saved = runRepository.save(run)

        val workflowId = "scenario-run-${saved.id}"
        val stub = workflowClient.newWorkflowStub(
            ScenarioRunWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TaskQueues.COORDINATOR)
                .setWorkflowExecutionTimeout(Duration.ofHours(2))
                .build()
        )

        val execution = WorkflowClient.start(stub::execute, saved.id)
        saved.workflowId    = workflowId
        saved.workflowRunId = execution.runId
        runRepository.save(saved)

        log.info("Started workflow '{}' (runId={}) for project '{}'", workflowId, saved.id, projectId)
        return saved
    }

    @Transactional
    fun cancelRun(runId: String) {
        val run = get(runId)
        if (run.status.isTerminal()) {
            throw IllegalStateException("Run '$runId' is already in terminal state: ${run.status}")
        }
        workflowClient.newWorkflowStub(ScenarioRunWorkflow::class.java, run.workflowId!!)
            .cancel()
        log.info("Sent cancel signal to workflow '{}' for run '{}'", run.workflowId, runId)
    }

    @Transactional
    fun markBlockStarted(blockRunId: String, agentId: String): BlockRunEntity {
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        val block = blockRunRepository.findById(blockRunId)
            .orElseThrow { NoSuchElementException("BlockRun '$blockRunId' not found") }

        if (block.status !in setOf(BlockRunStatus.PENDING, BlockRunStatus.READY, BlockRunStatus.RUNNING)) {
            return block
        }

        block.status = BlockRunStatus.RUNNING
        block.agentId = agentId
        if (block.startedAt == null) {
            block.startedAt = Instant.now()
        }
        return blockRunRepository.save(block)
    }
}
