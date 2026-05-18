package com.disttest.coordinator.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

// ─── DTOs ────────────────────────────────────────────────────────────────────

data class TestResult(
    val testId: String,
    val testName: String,
    val status: String,
    val durationMs: Long,
    val logs: String,
    val errorMessage: String?
)

data class BlockReport(
    val blockId: String,
    val blockName: String,
    val nodeId: String,
    val status: String,
    val totalTests: Int,
    val passed: Int,
    val failed: Int,
    val errors: Int,
    val durationMs: Long,
    val tests: List<TestResult>
)

data class ScenarioReport(
    val scenarioId: String,
    val scenarioName: String,
    val executionId: String,
    val status: String,
    val totalTests: Int,
    val passed: Int,
    val failed: Int,
    val errors: Int,
    val durationMs: Long,
    val blocks: List<BlockReport>,
    val startedAt: Instant?,
    val completedAt: Instant?
)

// ─── Controller ──────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports", description = "Отчёты о выполнении сценариев и блоков тестирования")
class ReportController {

    @GetMapping("/scenarios/{id}")
    @Operation(summary = "Итоговый отчёт о выполнении сценария")
    fun getScenarioReport(@PathVariable id: String): ResponseEntity<ScenarioReport> {
        // TODO: агрегировать результаты всех блоков и сформировать сводный отчёт
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
    }

    @GetMapping("/scenarios/{id}/blocks")
    @Operation(summary = "Список отчётов по блокам сценария")
    fun listBlockReports(@PathVariable id: String): ResponseEntity<List<BlockReport>> {
        // TODO: вернуть отчёты по каждому выполненному блоку сценария
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
    }

    @GetMapping("/scenarios/{id}/blocks/{blockId}")
    @Operation(summary = "Отчёт по конкретному блоку")
    fun getBlockReport(
        @PathVariable id: String,
        @PathVariable blockId: String
    ): ResponseEntity<BlockReport> {
        // TODO: загрузить и вернуть детальный отчёт по блоку с результатами тестов
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
    }
}
