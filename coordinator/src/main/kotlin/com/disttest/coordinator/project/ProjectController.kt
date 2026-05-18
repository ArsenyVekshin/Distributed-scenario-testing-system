package com.disttest.coordinator.project

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/projects")
@Tag(name = "Projects", description = "Управление GitLab-проектами")
class ProjectController(private val service: ProjectService) {

    @GetMapping
    @Operation(summary = "Список всех проектов")
    fun list(): List<ProjectDto> = service.list().map { it.toDto() }

    @GetMapping("/{id}")
    @Operation(summary = "Получить проект по ID")
    fun get(@PathVariable id: String): ProjectDto =
        try { service.get(id).toDto() }
        catch (e: NoSuchElementException) { throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message) }

    @PostMapping
    @Operation(summary = "Создать проект")
    fun create(@RequestBody request: CreateProjectRequest): ResponseEntity<ProjectDto> {
        val dto = service.create(request).toDto()
        return ResponseEntity.status(HttpStatus.CREATED).body(dto)
    }

    @PutMapping("/{id}")
    @Operation(summary = "Обновить проект")
    fun update(@PathVariable id: String, @RequestBody request: UpdateProjectRequest): ProjectDto =
        try { service.update(id, request).toDto() }
        catch (e: NoSuchElementException) { throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message) }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить проект")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        try { service.delete(id) }
        catch (e: NoSuchElementException) { throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message) }
        return ResponseEntity.noContent().build()
    }
}
