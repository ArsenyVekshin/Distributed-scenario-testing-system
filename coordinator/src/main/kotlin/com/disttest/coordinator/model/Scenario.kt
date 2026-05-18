package com.disttest.coordinator.model

import com.disttest.coordinator.project.ProjectEntity
import jakarta.persistence.*
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional

/**
 * Единственная сущность сценария — объединяет хранение в БД и доменную логику DAG.
 *
 * Граф сценария:
 *   узлы  → [BlockEntity]
 *   рёбра → [BlockEntity.dependsOn] (configId ссылается на configId другого блока)
 *
 * YAML-конфиг сценария хранится в S3; в БД записан только ключ [configYamlKey].
 * Блоки и тесты хранятся в отдельных таблицах (`blocks`, `test_cases`),
 * поэтому пересобирать граф из YAML при каждом обращении не требуется.
 */
@Entity
@Table(name = "scenarios")
class ScenarioEntity(

    @Id
    val id: String,

    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ExecutionStatus = ExecutionStatus.DRAFT,

    /** S3-ключ исходного YAML-конфига, например: `scenarios/{id}/config.yaml`. */
    @Column(name = "config_yaml_key", nullable = false)
    var configYamlKey: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    var project: ProjectEntity? = null,

    @Column(name = "last_commit_sha")
    var lastCommitSha: String? = null,

    @Column(name = "last_imported_at")
    var lastImportedAt: Instant? = null,

    @Column(name = "workflow_run_id")
    var workflowRunId: String? = null,

    @Column(name = "blocks_passed", nullable = false)
    var blocksPassed: Int = 0,

    /**
     * Денормализованное число блоков — читается без загрузки коллекции [blocks].
     * Устанавливается при создании сценария и не меняется после этого.
     */
    @Column(name = "blocks_total", nullable = false)
    var blocksTotal: Int = 0,

    /**
     * Максимальное время выполнения сценария целиком в секундах.
     * 0 — без ограничений. По умолчанию 86400 (24 часа).
     */
    @Column(name = "timeout_seconds", nullable = false)
    var timeoutSeconds: Int = 86_400,
) {
    @OneToMany(mappedBy = "scenario", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderColumn(name = "position")
    val blocks: MutableList<BlockEntity> = mutableListOf()

    // ── Scenario → Block ──────────────────────────────────────────────────────

    /** Находит блок по его [configId] (идентификатор из YAML). */
    fun findBlock(configId: String): BlockEntity? =
        blocks.find { it.configId == configId }

    // ── Scenario → Block → TestCase ───────────────────────────────────────────

    /** Прямой путь scenario → block → test. `null` если блок или тест не найден. */
    fun findTest(blockConfigId: String, testConfigId: String): TestCaseEntity? =
        findBlock(blockConfigId)?.findTest(testConfigId)

    // ── Обход графа ───────────────────────────────────────────────────────────

    /** Блоки без зависимостей — точки входа в граф. */
    fun rootBlocks(): List<BlockEntity> =
        blocks.filter { it.isRoot() }

    /**
     * Блоки, которые разблокируются после завершения [blockConfigId],
     * т.е. те, чей [BlockEntity.dependsOn] содержит [blockConfigId].
     */
    fun successors(blockConfigId: String): List<BlockEntity> =
        blocks.filter { blockConfigId in it.dependsOn }

    /** Непосредственные предшественники [blockConfigId] — блоки, от которых он зависит. */
    fun predecessors(blockConfigId: String): List<BlockEntity> =
        findBlock(blockConfigId)
            ?.dependsOn
            ?.mapNotNull { findBlock(it) }
            ?: emptyList()

    /**
     * `true` если все зависимости [blockConfigId] присутствуют в [completedConfigIds]
     * — блок готов к отправке агенту.
     */
    fun isReady(blockConfigId: String, completedConfigIds: Set<String>): Boolean =
        findBlock(blockConfigId)?.isReady(completedConfigIds) ?: false

    /**
     * Разбивает блоки на упорядоченные уровни выполнения (алгоритм Кана).
     *
     * Все блоки одного уровня не зависят друг от друга и могут быть отправлены
     * агентам параллельно.  Уровень N начинается только после завершения уровня N−1.
     *
     * Сложность: O(V·E) — приемлемо для типичных сценариев (< 50 блоков).
     */
    fun executionLevels(): List<List<BlockEntity>> {
        val inDegree  = blocks.associate { it.configId to it.dependsOn.size }.toMutableMap()
        val levels    = mutableListOf<List<BlockEntity>>()
        var frontier  = blocks.filter { inDegree[it.configId] == 0 }

        while (frontier.isNotEmpty()) {
            levels.add(frontier)
            val next = mutableListOf<BlockEntity>()
            for (block in frontier) {
                for (successor in successors(block.configId)) {
                    val remaining = (inDegree[successor.configId] ?: 1) - 1
                    inDegree[successor.configId] = remaining
                    if (remaining == 0) next.add(successor)
                }
            }
            frontier = next
        }
        return levels
    }

    // ── Агрегаты ─────────────────────────────────────────────────────────────

    /**
     * Суммирует количество тестов во всех блоках.
     * Требует загруженного графа — вызывай только после [ScenarioRepository.loadAggregateById].
     */
    fun totalTests(): Int = blocks.sumOf { it.tests.size }
}

// ─── Repository ───────────────────────────────────────────────────────────────

@Repository
interface ScenarioRepository : JpaRepository<ScenarioEntity, String> {

    /**
     * Лёгкий запрос для списка: загружает только поля `scenarios`, коллекция [blocks] не трогается.
     * Число блоков берётся из денормализованного [ScenarioEntity.blocksTotal].
     */
    fun findAllByOrderByCreatedAtDesc(): List<ScenarioEntity>

    fun findByStatus(status: ExecutionStatus): List<ScenarioEntity>

    /**
     * Полная загрузка графа одним запросом (LEFT JOIN FETCH через `@EntityGraph`).
     *
     * Загружает: scenario → blocks → tests и scenario → blocks → dependsOn.
     * `dependsOn` — единственный bag (List без @OrderColumn); остальные коллекции
     * имеют @OrderColumn и не являются bags, поэтому MultipleBagFetchException не возникает.
     *
     * Используй перед обходом графа: [ScenarioEntity.executionLevels], [ScenarioEntity.findTest] и т.д.
     *
     * Имя метода не `findByIdWith…` — Spring Data иначе парсит суффикс как свойство (`id.withGraph`)
     * и контекст не поднимается.
     */
    @EntityGraph(attributePaths = ["blocks", "blocks.tests", "blocks.dependsOn"])
    @Query("SELECT s FROM ScenarioEntity s WHERE s.id = :id")
    fun loadAggregateById(id: String): Optional<ScenarioEntity>

    fun findByProjectId(projectId: String): List<ScenarioEntity>

    @EntityGraph(attributePaths = ["blocks", "blocks.tests", "blocks.dependsOn"])
    @Query("SELECT s FROM ScenarioEntity s WHERE s.project.id = :projectId ORDER BY s.createdAt DESC")
    fun loadLatestAggregateByProjectId(projectId: String): List<ScenarioEntity>
}

