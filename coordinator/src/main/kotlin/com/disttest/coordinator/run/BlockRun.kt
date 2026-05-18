package com.disttest.coordinator.run

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "block_runs")
class BlockRunEntity(
    @Id
    var id: String = UUID.randomUUID().toString(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_run_id", nullable = false)
    var scenarioRun: ScenarioRunEntity,

    @Column(name = "block_config_id", nullable = false)
    var blockConfigId: String = "",

    @Column(name = "block_name", nullable = false)
    var blockName: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: BlockRunStatus = BlockRunStatus.PENDING,

    var agentId: String? = null,

    @Column(nullable = false)
    var parallelism: Int = 1,

    @Column(name = "timeout_seconds", nullable = false)
    var timeoutSeconds: Long = 0,

    @Column(nullable = false)
    var retries: Int = 0,

    var startedAt: Instant? = null,

    var finishedAt: Instant? = null,

    var durationMs: Long? = null,

    var imagePrepareStartedAt: Instant? = null,

    var imagePrepareFinishedAt: Instant? = null,

    var imagePrepareDurationMs: Long? = null,

    @Column(columnDefinition = "TEXT")
    var errorMessage: String? = null,
)

@Repository
interface BlockRunRepository : JpaRepository<BlockRunEntity, String> {
    fun findByScenarioRunId(runId: String): List<BlockRunEntity>
    fun findByScenarioRunIdAndBlockConfigId(runId: String, blockConfigId: String): BlockRunEntity?
}
