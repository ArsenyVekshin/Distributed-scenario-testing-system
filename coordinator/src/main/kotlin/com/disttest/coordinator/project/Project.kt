package com.disttest.coordinator.project

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "projects")
class ProjectEntity(
    @Id
    var id: String = UUID.randomUUID().toString(),

    @Column(nullable = false)
    var name: String = "",

    @Column(name = "gitlab_url", nullable = false)
    var gitlabUrl: String = "",

    @Column(nullable = false)
    var branch: String = "",

    /**
     * GitLab access token. Хранится в открытом виде для MVP.
     * API никогда не должен возвращать полный токен наружу.
     */
    @Column(name = "access_token", nullable = false)
    var accessToken: String = "",

    @Column(name = "scenario_config_path", nullable = false)
    var scenarioConfigPath: String = "scenario.dst.yaml",

    @Column(name = "dockerfile_path", nullable = false)
    var dockerfilePath: String = "Dockerfile",

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

@Repository
interface ProjectRepository : JpaRepository<ProjectEntity, String>
