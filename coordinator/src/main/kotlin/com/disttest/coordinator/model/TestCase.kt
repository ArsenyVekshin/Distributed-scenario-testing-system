package com.disttest.coordinator.model

import jakarta.persistence.*

/**
 * Leaf node of the scenario graph — an individual test executed by an agent.
 *
 * [configId] is the user-defined identifier from the YAML config (e.g. "test-auth").
 * It is unique within its parent [BlockEntity].
 *
 * [env] stores the **merged** environment variables (scenario → block → test),
 * so the agent receives the final effective set without knowing about inheritance.
 */
@Entity
@Table(name = "test_cases")
class TestCaseEntity(

    @Id
    val id: String,

    @Column(name = "config_id", nullable = false)
    val configId: String,

    var name: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    var command: String,

    @Column(name = "timeout_seconds", nullable = false)
    var timeoutSeconds: Int = 60,

    /** Повторные попытки при ненулевом exit code. 0 — не повторять. */
    @Column(nullable = false)
    var retries: Int = 0,

    /** Рабочая директория команды. Пустая строка — директория агента по умолчанию. */
    @Column(nullable = false)
    var workdir: String = "",
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "block_id", nullable = false)
    lateinit var block: BlockEntity

    /**
     * Итоговые переменные окружения после мёрджа всех уровней
     * (сценарий → блок → тест).  Агент использует их напрямую.
     */
    @ElementCollection
    @CollectionTable(name = "test_case_env", joinColumns = [JoinColumn(name = "test_case_id")])
    @MapKeyColumn(name = "env_key")
    @Column(name = "env_value", columnDefinition = "TEXT")
    val env: MutableMap<String, String> = mutableMapOf()

    /** Метки для фильтрации и отчётов. */
    @ElementCollection
    @CollectionTable(name = "test_case_tags", joinColumns = [JoinColumn(name = "test_case_id")])
    @Column(name = "tag")
    val tags: MutableList<String> = mutableListOf()
}
