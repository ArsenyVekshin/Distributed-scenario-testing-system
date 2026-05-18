package com.disttest.coordinator.run

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@Tag(name = "Runs", description = "Запуск и мониторинг тестовых прогонов")
class RunController(private val service: RunService) {

    @PostMapping("/api/projects/{projectId}/runs")
    @Operation(summary = "Запустить прогон для проекта")
    fun startRun(@PathVariable projectId: String): ResponseEntity<StartRunResponse> {
        val run = try {
            service.startRun(projectId)
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
            StartRunResponse(
                id        = run.id,
                projectId = run.project.id,
                status    = run.status.name,
                branch    = run.branch,
                startedAt = run.startedAt,
            )
        )
    }

    @GetMapping("/api/projects/{projectId}/runs")
    @Operation(summary = "Список прогонов проекта")
    fun listRuns(@PathVariable projectId: String): List<RunSummaryDto> =
        service.listByProject(projectId).map { it.toSummaryDto() }

    @GetMapping("/api/runs/{runId}")
    @Operation(summary = "Детали прогона (блоки, тесты, логи)")
    fun getRun(@PathVariable runId: String): RunDetailDto =
        try { service.getDetail(runId) }
        catch (e: NoSuchElementException) { throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message) }

    @PostMapping("/api/runs/{runId}/cancel")
    @Operation(summary = "Отменить прогон")
    fun cancelRun(@PathVariable runId: String): ResponseEntity<Void> {
        try { service.cancelRun(runId) }
        catch (e: NoSuchElementException)    { throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message) }
        catch (e: IllegalStateException)     { throw ResponseStatusException(HttpStatus.CONFLICT, e.message) }
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/runs/blocks/{blockRunId}/started")
    @Operation(summary = "РџРѕРјРµС‚РёС‚СЊ Р±Р»РѕРє РєР°Рє СЂРµР°Р»СЊРЅРѕ Р·Р°РїСѓС‰РµРЅРЅС‹Р№ Р°РіРµРЅС‚РѕРј")
    fun markBlockStarted(
        @PathVariable blockRunId: String,
        @RequestBody request: MarkBlockStartedRequest,
    ): ResponseEntity<Void> {
        try { service.markBlockStarted(blockRunId, request.agentId) }
        catch (e: NoSuchElementException) { throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message) }
        catch (e: IllegalArgumentException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message) }
        return ResponseEntity.noContent().build()
    }
}
