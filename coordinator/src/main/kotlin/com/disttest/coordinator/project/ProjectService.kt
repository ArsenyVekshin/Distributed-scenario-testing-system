package com.disttest.coordinator.project

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ProjectService(private val repository: ProjectRepository) {

    private val log = LoggerFactory.getLogger(ProjectService::class.java)

    fun list(): List<ProjectEntity> = repository.findAll()

    fun get(id: String): ProjectEntity =
        repository.findById(id).orElseThrow { NoSuchElementException("Project '$id' not found") }

    @Transactional
    fun create(request: CreateProjectRequest): ProjectEntity {
        val entity = ProjectEntity(
            name               = request.name,
            gitlabUrl          = request.gitlabUrl,
            branch             = request.branch,
            accessToken        = request.accessToken,
            scenarioConfigPath = request.scenarioConfigPath,
            dockerfilePath     = request.dockerfilePath,
        )
        val saved = repository.save(entity)
        log.info("Created project '{}' ({})", saved.name, saved.id)
        return saved
    }

    @Transactional
    fun update(id: String, request: UpdateProjectRequest): ProjectEntity {
        val entity = get(id)
        request.name?.let               { entity.name               = it }
        request.gitlabUrl?.let          { entity.gitlabUrl          = it }
        request.branch?.let             { entity.branch             = it }
        request.accessToken?.let        { entity.accessToken        = it }
        request.scenarioConfigPath?.let { entity.scenarioConfigPath = it }
        request.dockerfilePath?.let     { entity.dockerfilePath     = it }
        entity.updatedAt = Instant.now()
        return repository.save(entity)
    }

    @Transactional
    fun delete(id: String) {
        val entity = get(id)
        repository.delete(entity)
        log.info("Deleted project '{}'", id)
    }
}
