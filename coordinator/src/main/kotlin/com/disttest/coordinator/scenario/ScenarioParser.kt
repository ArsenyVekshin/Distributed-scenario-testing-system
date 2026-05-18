package com.disttest.coordinator.scenario

import com.disttest.coordinator.model.*
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Парсит YAML-конфиг сценария, валидирует его и строит граф [ScenarioEntity]
 * с вложенными [BlockEntity] и [TestCaseEntity].
 *
 * Особенности:
 *  - Собирает **все** ошибки за один проход — не бросает исключение на первой.
 *  - Каждая ошибка содержит [ValidationError.path] в формате `blocks[i].tests[j].field`.
 *  - Переменные окружения мёрджатся при парсинге: сценарий ← блок ← тест.
 *    Агент получает итоговый плоский Map и не знает об иерархии наследования.
 *
 * Поддерживаемая версия формата конфига: [SUPPORTED_VERSION].
 */
@Component
class ScenarioParser {

    companion object {
        const val SUPPORTED_VERSION = "1"
    }

    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Разбирает [yaml], валидирует и строит граф сущностей.
     *
     * [ScenarioEntity.configYamlKey] при возврате пустой — контроллер/сервис
     * записывает файл в S3 и устанавливает ключ перед сохранением в БД.
     *
     * @throws ScenarioValidationException если YAML невалиден (список всех ошибок).
     * @throws IllegalArgumentException если [yaml] пустой.
     */
    fun parse(yaml: String): ScenarioEntity {
        require(yaml.isNotBlank()) { "Scenario config must not be empty" }

        val config = try {
            yamlMapper.readValue(yaml, ScenarioConfig::class.java)
        } catch (e: JacksonException) {
            throw ScenarioValidationException("$", "YAML parse error: ${e.originalMessage}")
        }

        val errors = mutableListOf<ValidationError>()
        validate(config, errors)

        if (errors.isNotEmpty()) throw ScenarioValidationException(errors)

        return config.toEntity(id = UUID.randomUUID().toString())
    }

    // ─── Validation ──────────────────────────────────────────────────────────

    private fun validate(config: ScenarioConfig, errors: MutableList<ValidationError>) {

        // ── Версия ────────────────────────────────────────────────────────────
        if (config.version != SUPPORTED_VERSION) {
            errors += ValidationError(
                "version",
                "Unsupported config version '${config.version}'. Supported: $SUPPORTED_VERSION"
            )
        }

        // ── Корневые поля ─────────────────────────────────────────────────────
        if (config.name.isBlank()) {
            errors += ValidationError("name", "Must not be blank")
        }
        if (config.timeout < 0) {
            errors += ValidationError("timeout", "Must be >= 0, got ${config.timeout}")
        }
        if (config.blocks.isEmpty()) {
            errors += ValidationError("blocks", "Scenario must contain at least one block")
            return  // дальнейшая валидация блоков бессмысленна
        }

        // ── Уникальность ID блоков ────────────────────────────────────────────
        val blockIds = config.blocks.map { it.id }
        blockIds.groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .forEach { dup ->
                errors += ValidationError("blocks", "Duplicate block id '$dup'")
            }
        val blockIdSet = blockIds.toSet()

        // ── Блоки ─────────────────────────────────────────────────────────────
        config.blocks.forEachIndexed { bi, block ->
            val bp = "blocks[$bi]"

            if (block.id.isBlank()) {
                errors += ValidationError("$bp.id", "Must not be blank")
            }
            if (block.parallelism < 1) {
                errors += ValidationError(
                    "$bp.parallelism",
                    "Must be >= 1, got ${block.parallelism}"
                )
            }
            if (block.timeout < 0) {
                errors += ValidationError("$bp.timeout", "Must be >= 0, got ${block.timeout}")
            }
            if (block.retries < 0) {
                errors += ValidationError("$bp.retries", "Must be >= 0, got ${block.retries}")
            }

            // dependsOn: все ID должны существовать
            block.dependsOn.forEachIndexed { di, dep ->
                if (dep !in blockIdSet) {
                    errors += ValidationError(
                        "$bp.dependsOn[$di]",
                        "References unknown block '$dep'"
                    )
                }
                if (dep == block.id) {
                    errors += ValidationError(
                        "$bp.dependsOn[$di]",
                        "Block '${block.id}' cannot depend on itself"
                    )
                }
            }

            if (block.tests.isEmpty()) {
                errors += ValidationError("$bp.tests", "Block '${block.id}' must have at least one test")
            }

            // Уникальность ID тестов внутри блока
            val testIds = block.tests.map { it.id }
            testIds.groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .forEach { dup ->
                    errors += ValidationError("$bp.tests", "Duplicate test id '$dup'")
                }

            // ── Тесты ─────────────────────────────────────────────────────────
            block.tests.forEachIndexed { ti, test ->
                val tp = "$bp.tests[$ti]"

                if (test.id.isBlank()) {
                    errors += ValidationError("$tp.id", "Must not be blank")
                }
                if (test.command.isBlank()) {
                    errors += ValidationError("$tp.command", "Must not be blank")
                }
                if (test.timeout <= 0) {
                    errors += ValidationError(
                        "$tp.timeout",
                        "Must be > 0, got ${test.timeout}"
                    )
                }
                if (test.retries < 0) {
                    errors += ValidationError(
                        "$tp.retries",
                        "Must be >= 0, got ${test.retries}"
                    )
                }
            }
        }

        // ── Цикличность (DFS) ─────────────────────────────────────────────────
        if (errors.none { it.path.endsWith("dependsOn") || it.path.contains("dependsOn") }) {
            // Проверяем циклы только если зависимости синтаксически корректны
            if (hasCycle(config.blocks)) {
                errors += ValidationError(
                    "blocks",
                    "Cycle detected in block dependency graph"
                )
            }
        }
    }

    // ─── Cycle detection (DFS, 3-colour) ─────────────────────────────────────

    private fun hasCycle(blocks: List<BlockConfig>): Boolean {
        val deps = blocks.associate { it.id to it.dependsOn }
        val color = mutableMapOf<String, Color>().withDefault { Color.WHITE }

        fun dfs(id: String): Boolean {
            color[id] = Color.GRAY
            for (dep in deps[id].orEmpty()) {
                when (color.getValue(dep)) {
                    Color.GRAY  -> return true
                    Color.WHITE -> if (dfs(dep)) return true
                    Color.BLACK -> Unit
                }
            }
            color[id] = Color.BLACK
            return false
        }

        return blocks.any { color.getValue(it.id) == Color.WHITE && dfs(it.id) }
    }

    // ─── Mapping: Config → Entity ─────────────────────────────────────────────

    private fun ScenarioConfig.toEntity(id: String): ScenarioEntity {
        val scenario = ScenarioEntity(
            id             = id,
            name           = name,
            description    = description,
            configYamlKey  = "",
            timeoutSeconds = timeout,
        )
        blocks.forEach { blockConfig ->
            val block = blockConfig.toEntity()
            block.scenario = scenario
            blockConfig.tests.forEach { testConfig ->
                // Мёрджим env: сценарий ← блок ← тест (наивысший приоритет у теста)
                val mergedEnv = env.toMutableMap()
                    .also { it.putAll(blockConfig.env) }
                    .also { it.putAll(testConfig.env) }

                val test = testConfig.toEntity(mergedEnv)
                test.block = block
                block.tests.add(test)
            }
            scenario.blocks.add(block)
        }
        return scenario
    }

    private fun BlockConfig.toEntity() = BlockEntity(
        id             = UUID.randomUUID().toString(),
        configId       = id,
        name           = name.ifBlank { id },
        parallelism    = parallelism,
        timeoutSeconds = timeout,
        retries        = retries,
    ).also {
        it.dependsOn.addAll(dependsOn)
        it.tags.addAll(tags)
    }

    private fun TestCaseConfig.toEntity(mergedEnv: Map<String, String>) = TestCaseEntity(
        id             = UUID.randomUUID().toString(),
        configId       = id,
        name           = name.ifBlank { id },
        command        = normalizeCommand(command),
        timeoutSeconds = timeout,
        retries        = retries,
        workdir        = workdir,
    ).also {
        it.env.putAll(mergedEnv)
        it.tags.addAll(tags)
    }

    private fun normalizeCommand(command: String): String {
        val trimmed = command.trim()
        val pythonInline = Regex("""^((?:python|python3)(?:\.\d+)?\s+-c\s+)(["'])([\s\S]*)\2$""")
        val match = pythonInline.matchEntire(trimmed) ?: return trimmed

        val code = match.groupValues[3]
        val normalizedCode = if ('\n' in code || '\r' in code) {
            code.trimIndent().trim()
        } else {
            code.trim()
        }

        return "${match.groupValues[1]}${match.groupValues[2]}$normalizedCode${match.groupValues[2]}"
    }

    private enum class Color { WHITE, GRAY, BLACK }
}
