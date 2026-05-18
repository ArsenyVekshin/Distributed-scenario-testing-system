package com.disttest.coordinator.run

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "test_runs")
class TestRunEntity(
    @Id
    var id: String = UUID.randomUUID().toString(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "block_run_id", nullable = false)
    var blockRun: BlockRunEntity,

    @Column(name = "test_config_id", nullable = false)
    var testConfigId: String = "",

    @Column(name = "test_name", nullable = false)
    var testName: String = "",

    @Column(columnDefinition = "TEXT", nullable = false)
    var command: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TestRunStatus = TestRunStatus.PENDING,

    var exitCode: Int? = null,

    @Column(name = "timeout_seconds", nullable = false)
    var timeoutSeconds: Long = 60,

    @Column(nullable = false)
    var retries: Int = 0,

    @Column(nullable = false)
    var attempts: Int = 0,

    var startedAt: Instant? = null,

    var finishedAt: Instant? = null,

    var durationMs: Long? = null,

    @Column(columnDefinition = "TEXT")
    var stdout: String? = null,

    @Column(columnDefinition = "TEXT")
    var stderr: String? = null,

    @Column(columnDefinition = "TEXT")
    var errorMessage: String? = null,
)

@Repository
interface TestRunRepository : JpaRepository<TestRunEntity, String> {
    fun findByBlockRunId(blockRunId: String): List<TestRunEntity>
}
