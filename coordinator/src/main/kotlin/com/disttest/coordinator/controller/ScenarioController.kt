package com.disttest.coordinator.controller

import com.disttest.coordinator.dto.ScenarioDetailDto
import com.disttest.coordinator.dto.ScenarioSummaryDto
import com.disttest.coordinator.model.*
import com.disttest.coordinator.scenario.ScenarioDetailAssembler
import com.disttest.coordinator.scenario.ScenarioService
import com.disttest.coordinator.scenario.ScenarioValidationException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

// ─── DTOs ────────────────────────────────────────────────────────────────────

private fun ScenarioEntity.toSummaryDto() = ScenarioSummaryDto(
    id          = id,
    name        = name,
    description = description,
    status      = when (status) {
        ExecutionStatus.TIMEOUT -> "FAILED"
        else -> status.name
    },
    blockCount  = blocksTotal,
    createdAt   = createdAt,
)

data class CreateScenarioRequest(
    val configYaml: String,
)

data class ValidationErrorDto(val path: String, val message: String)


// ─── Controller ──────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/scenarios")
@Tag(name = "Scenarios", description = "Управление тестовыми сценариями")
class ScenarioController(
    private val scenarioService: ScenarioService,
    private val scenarioRepository: ScenarioRepository,
) {

    /** Полная карточка сценария для SPA (DAG + YAML). */
    @GetMapping("/{id}/detail")
    @Operation(summary = "Детальная информация о сценарии для веб-интерфейса")
    fun getScenarioDetail(@PathVariable id: String): ResponseEntity<ScenarioDetailDto> {
        val entity = findWithGraphOrThrow(id)
        val yaml = try {
            scenarioService.readConfigYaml(id)
        } catch (_: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario '$id' not found")
        }
        return ResponseEntity.ok(ScenarioDetailAssembler.toDetail(entity, yaml))
    }
    // ── CRUD ─────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Список всех сценариев")
    fun listScenarios(): ResponseEntity<List<ScenarioSummaryDto>> =
        ResponseEntity.ok(scenarioRepository.findAllByOrderByCreatedAtDesc().map { it.toSummaryDto() })

    @GetMapping("/{id}")
    @Operation(summary = "Получить сценарий по ID")
    fun getScenario(@PathVariable id: String): ResponseEntity<ScenarioSummaryDto> =
        ResponseEntity.ok(findOrThrow(id).toSummaryDto())

    /**
     * Создаёт сценарий из YAML-конфига.
     * Логика (parse → S3 → DB) делегирована в [ScenarioService.create].
     *
     * Возвращает 422 со списком ошибок валидации в теле ответа при невалидном конфиге.
     */
    @PostMapping
    @Operation(summary = "Создать сценарий из YAML-конфига")
    fun createScenario(@RequestBody request: CreateScenarioRequest): ResponseEntity<*> {
        val entity = try {
            scenarioService.create(request.configYaml)
        } catch (e: ScenarioValidationException) {
            val body = mapOf(
                "message" to "Scenario config is invalid",
                "errors"  to e.errors.map { ValidationErrorDto(it.path, it.message) },
            )
            return ResponseEntity.unprocessableEntity().body(body)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(entity.toSummaryDto())
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить сценарий (только не в статусе RUNNING)")
    fun deleteScenario(@PathVariable id: String): ResponseEntity<Void> {
        try {
            scenarioService.delete(id)
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
        return ResponseEntity.noContent().build()
    }

    /** Возвращает presigned URL для прямого скачивания YAML-конфига из S3 (15 мин). */
    @GetMapping("/{id}/config")
    @Operation(summary = "Получить ссылку на исходный YAML-конфиг сценария")
    fun getConfigUrl(@PathVariable id: String): ResponseEntity<Map<String, String>> {
        val url = try {
            scenarioService.configDownloadUrl(id)
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
        return ResponseEntity.ok(mapOf("url" to url))
    }

    // ── Execution control ─────────────────────────────────────────────────────

    /**
     * @deprecated Используйте POST /api/projects/{projectId}/runs для запуска через Project-модель.
     */
    @PostMapping("/{id}/start")
    @Operation(summary = "[DEPRECATED] Используйте /api/projects/{id}/runs")
    fun startExecution(@PathVariable id: String): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(HttpStatus.GONE).body(
            mapOf("message" to "Manual scenario start is deprecated. Use POST /api/projects/{projectId}/runs instead.")
        )
    }

    /**
     * @deprecated Используйте POST /api/runs/{runId}/cancel для отмены прогона.
     */
    @PostMapping("/{id}/stop")
    @Operation(summary = "[DEPRECATED] Используйте /api/runs/{runId}/cancel")
    fun stopExecution(@PathVariable id: String): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.GONE).body(
            mapOf("message" to "Use POST /api/runs/{runId}/cancel instead.")
        )

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun findOrThrow(id: String): ScenarioEntity =
        scenarioRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario '$id' not found")
        }

    private fun findWithGraphOrThrow(id: String): ScenarioEntity =
        scenarioRepository.loadAggregateById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario '$id' not found")
        }
}
