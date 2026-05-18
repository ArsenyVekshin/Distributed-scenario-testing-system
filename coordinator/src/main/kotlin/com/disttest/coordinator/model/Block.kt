package com.disttest.coordinator.model

import jakarta.persistence.*

/**
 * A node in the scenario DAG — a named group of tests that runs on a single agent.
 *
 * [configId] is the user-defined block identifier from the YAML config (e.g. "unit-tests").
 * It is unique within the parent [ScenarioEntity].
 *
 * [dependsOn] stores the [configId]s of blocks that must complete successfully before
 * this block can be dispatched.
 */
@Entity
@Table(name = "blocks")
class BlockEntity(

    @Id
    val id: String,

    @Column(name = "config_id", nullable = false)
    val configId: String,

    var name: String,

    @Column(nullable = false)
    var parallelism: Int = 1,

    /** Максимальное время выполнения блока в секундах. 0 — без ограничений. */
    @Column(name = "timeout_seconds", nullable = false)
    var timeoutSeconds: Int = 0,

    /** Количество повторных попыток блока. В MVP поле зарезервировано, не используется автоматически. */
    @Column(nullable = false)
    var retries: Int = 0,
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false)
    lateinit var scenario: ScenarioEntity

    @ElementCollection
    @CollectionTable(name = "block_dependencies", joinColumns = [JoinColumn(name = "block_id")])
    @Column(name = "depends_on_config_id")
    val dependsOn: MutableList<String> = mutableListOf()

    @OneToMany(mappedBy = "block", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderColumn(name = "position")
    val tests: MutableList<TestCaseEntity> = mutableListOf()

    /** Метки для фильтрации и отчётов. */
    @ElementCollection
    @CollectionTable(name = "block_tags", joinColumns = [JoinColumn(name = "block_id")])
    @Column(name = "tag")
    val tags: MutableList<String> = mutableListOf()

    // ── Domain methods ────────────────────────────────────────────────────────

    /** Returns the test with [configId], or `null` if not found. */
    fun findTest(configId: String): TestCaseEntity? =
        tests.find { it.configId == configId }

    /**
     * `true` when all dependencies are present in [completedConfigIds],
     * i.e. this block is eligible for dispatch.
     */
    fun isReady(completedConfigIds: Set<String>): Boolean =
        dependsOn.all { it in completedConfigIds }

    /** `true` if this block has no dependencies — a root node in the DAG. */
    fun isRoot(): Boolean = dependsOn.isEmpty()
}
