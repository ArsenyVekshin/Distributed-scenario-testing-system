package com.disttest.coordinator.scenario

import com.disttest.coordinator.dto.BlockDetailDto
import com.disttest.coordinator.dto.ScenarioDetailDto
import com.disttest.coordinator.dto.TestDetailDto
import com.disttest.coordinator.model.ExecutionStatus
import com.disttest.coordinator.model.ScenarioEntity
import com.disttest.coordinator.model.TestCaseEntity
import kotlin.random.Random

/**
 * Собирает DTO детальной карточки сценария для UI.
 * Структура графа и тестов — из БД; статусы выполнения блоков/тестов — демонстрационная
 * проекция по [ScenarioEntity.status] и [ScenarioEntity.blocksPassed], пока нет живых отчётов.
 */
object ScenarioDetailAssembler {

    fun toDetail(entity: ScenarioEntity, configYaml: String): ScenarioDetailDto {
        val orderedBlocks = entity.blocks.toList()
        val blocks = orderedBlocks.mapIndexed { index, block ->
            val blockUiStatus = blockUiStatus(entity, index)
            val tests = block.tests.mapIndexed { ti, test ->
                testToDto(test, blockUiStatus, ti)
            }
            val passed = tests.count { it.status == "PASSED" }
            val failed = tests.count { it.status == "FAILED" || it.status == "ERROR" }
            val duration = tests.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()
            BlockDetailDto(
                id = block.configId,
                name = block.name,
                status = blockUiStatus,
                parallelism = block.parallelism,
                dependsOn = block.dependsOn.toList(),
                nodeId = null,
                totalTests = tests.size,
                passedTests = passed,
                failedTests = failed,
                durationMs = duration,
                tests = tests,
            )
        }

        return ScenarioDetailDto(
            id = entity.id,
            name = entity.name,
            description = entity.description,
            status = uiScenarioStatus(entity.status),
            blockCount = entity.blocksTotal,
            createdAt = entity.createdAt,
            configYaml = configYaml,
            workflowRunId = entity.workflowRunId,
            completedBlocks = entity.blocksPassed.coerceAtMost(entity.blocksTotal.coerceAtLeast(0)),
            totalBlocks = entity.blocksTotal,
            blocks = blocks,
        )
    }

    private fun uiScenarioStatus(status: ExecutionStatus): String =
        when (status) {
            ExecutionStatus.DRAFT -> "DRAFT"
            ExecutionStatus.RUNNING -> "RUNNING"
            ExecutionStatus.COMPLETED -> "COMPLETED"
            ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT -> "FAILED"
            ExecutionStatus.STOPPED -> "STOPPED"
        }

    private fun blockUiStatus(entity: ScenarioEntity, blockIndex: Int): String {
        val p = entity.blocksPassed
        val n = entity.blocksTotal
        if (n == 0) return "PENDING"
        return when (entity.status) {
            ExecutionStatus.DRAFT -> "PENDING"
            ExecutionStatus.COMPLETED -> "PASSED"
            ExecutionStatus.STOPPED ->
                if (blockIndex < p.coerceAtMost(n)) "PASSED" else "PENDING"
            ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT -> {
                if (n == 0) return "PENDING"
                val failIdx = if (p < n) p else (n - 1).coerceAtLeast(0)
                when {
                    blockIndex < failIdx -> "PASSED"
                    blockIndex == failIdx -> "FAILED"
                    else -> "SKIPPED"
                }
            }
            ExecutionStatus.RUNNING ->
                when {
                    blockIndex < p -> "PASSED"
                    blockIndex == p && p < n -> "RUNNING"
                    else -> "PENDING"
                }
        }
    }

    private fun testToDto(test: TestCaseEntity, blockStatus: String, indexInBlock: Int): TestDetailDto {
        val id = test.configId
        val name = test.name
        return when (blockStatus) {
            "PASSED" -> TestDetailDto(
                id = id,
                name = name,
                status = "PASSED",
                durationMs = 800L + Random.Default.nextLong(4200),
                output = "exit 0",
            )
            "RUNNING" -> when {
                indexInBlock == 0 -> TestDetailDto(id = id, name = name, status = "RUNNING")
                indexInBlock == 1 -> TestDetailDto(
                    id = id,
                    name = name,
                    status = "PASSED",
                    durationMs = 1200L + Random.Default.nextLong(2000),
                )
                else -> TestDetailDto(id = id, name = name, status = "PENDING")
            }
            "FAILED" -> when (indexInBlock) {
                0 -> TestDetailDto(
                    id = id,
                    name = name,
                    status = "FAILED",
                    durationMs = 5000L + Random.Default.nextLong(8000),
                    error = "Command exited with non-zero status (demo projection)",
                )
                else -> TestDetailDto(id = id, name = name, status = "PENDING")
            }
            "SKIPPED" -> TestDetailDto(id = id, name = name, status = "SKIPPED")
            else -> TestDetailDto(id = id, name = name, status = "PENDING")
        }
    }
}
