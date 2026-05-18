package com.disttest.coordinator.dto

import java.time.Instant

/** Сводка сценария — совпадает по полям с ответом списка и с фронтенд-типом `ScenarioSummary`. */
data class ScenarioSummaryDto(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val blockCount: Int,
    val createdAt: Instant,
)

data class TestDetailDto(
    val id: String,
    val name: String,
    val status: String,
    val durationMs: Long? = null,
    val output: String? = null,
    val error: String? = null,
)

data class BlockDetailDto(
    val id: String,
    val name: String,
    val status: String,
    val parallelism: Int,
    val dependsOn: List<String>,
    val nodeId: String? = null,
    val totalTests: Int,
    val passedTests: Int,
    val failedTests: Int,
    val durationMs: Long? = null,
    val tests: List<TestDetailDto>,
)

/** Полная карточка сценария для UI (DAG, тесты, YAML). */
data class ScenarioDetailDto(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val blockCount: Int,
    val createdAt: Instant,
    val configYaml: String,
    val workflowRunId: String? = null,
    val completedBlocks: Int,
    val totalBlocks: Int,
    val blocks: List<BlockDetailDto>,
)
