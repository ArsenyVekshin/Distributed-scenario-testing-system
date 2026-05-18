package com.disttest.coordinator.run

import com.disttest.coordinator.model.ScenarioEntity
import com.disttest.coordinator.project.ProjectEntity
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "scenario_runs")
class ScenarioRunEntity(
    @Id
    var id: String = UUID.randomUUID().toString(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    var project: ProjectEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id")
    var scenario: ScenarioEntity? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ScenarioRunStatus = ScenarioRunStatus.PENDING,

    @Column(nullable = false)
    var branch: String = "",

    var commitSha: String? = null,

    var workflowId: String? = null,

    var workflowRunId: String? = null,

    var artifactKey: String? = null,

    var imageTag: String? = null,

    @Enumerated(EnumType.STRING)
    var errorStage: RunErrorStage? = null,

    @Column(columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(columnDefinition = "TEXT")
    var buildStdout: String? = null,

    @Column(columnDefinition = "TEXT")
    var buildStderr: String? = null,

    var buildStartedAt: Instant? = null,

    var buildFinishedAt: Instant? = null,

    var buildDurationMs: Long? = null,

    var executionStartedAt: Instant? = null,

    var startedAt: Instant? = null,

    var finishedAt: Instant? = null,

    var durationMs: Long? = null,

    @Column(nullable = false)
    var blocksTotal: Int = 0,

    @Column(nullable = false)
    var blocksSuccess: Int = 0,

    @Column(nullable = false)
    var blocksFailed: Int = 0,

    @Column(nullable = false)
    var blocksSkipped: Int = 0,

    @Column(nullable = false)
    var blocksCanceled: Int = 0,

    @Column(nullable = false)
    var blocksTimeout: Int = 0,

    @Column(nullable = false)
    var testsTotal: Int = 0,

    @Column(nullable = false)
    var testsPassed: Int = 0,

    @Column(nullable = false)
    var testsFailed: Int = 0,

    @Column(nullable = false)
    var testsError: Int = 0,

    @Column(nullable = false)
    var testsTimeout: Int = 0,
)

@Repository
interface ScenarioRunRepository : JpaRepository<ScenarioRunEntity, String> {
    fun findByProjectIdOrderByStartedAtDesc(projectId: String): List<ScenarioRunEntity>
}
