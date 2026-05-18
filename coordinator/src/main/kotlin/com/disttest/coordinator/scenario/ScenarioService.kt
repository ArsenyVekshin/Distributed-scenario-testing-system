package com.disttest.coordinator.scenario

import com.disttest.coordinator.model.ExecutionStatus
import com.disttest.coordinator.model.ScenarioEntity
import com.disttest.coordinator.model.ScenarioRepository
import com.disttest.coordinator.storage.ConfigStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сервисный слой для управления сценариями.
 *
 * Оркестрирует полный жизненный цикл сценария:
 *  1. **Парсинг** — YAML → граф сущностей ([ScenarioParser])
 *  2. **Хранение конфига** — YAML → S3 ([ConfigStore])
 *  3. **Сохранение метаданных** — граф → PostgreSQL ([ScenarioRepository])
 *
 * Порядок операций в [create] гарантирует согласованность:
 * сначала конфиг попадает в S3, затем запись создаётся в БД.
 * При ошибке сохранения в БД конфиг в S3 остаётся как «сирота» —
 * TODO: реализовать компенсирующую транзакцию (удаление из S3 при откате).
 */
@Service
class ScenarioService(
    private val parser: ScenarioParser,
    private val configStore: ConfigStore,
    private val repository: ScenarioRepository,
) {
    private val log = LoggerFactory.getLogger(ScenarioService::class.java)

    /**
     * Создаёт новый сценарий из YAML-строки.
     *
     * Шаги:
     *  1. Парсинг и валидация YAML → [ScenarioEntity] + граф блоков/тестов.
     *  2. Загрузка YAML в S3 (ключ: `scenarios/{id}/config.yaml`).
     *  3. Сохранение сущности в PostgreSQL (каскадное — блоки и тесты).
     *
     * @param yaml Содержимое конфигурационного файла.
     * @return Сохранённая [ScenarioEntity] с заполненными ID и [ScenarioEntity.configYamlKey].
     * @throws ScenarioValidationException если YAML не соответствует схеме.
     */
    @Transactional
    fun create(yaml: String): ScenarioEntity {
        val entity = parser.parse(yaml)

        val yamlKey = ConfigStore.yamlKey(entity.id)
        configStore.upload(yamlKey, yaml)
        log.info("Uploaded config for scenario '{}' to S3 key '{}'", entity.id, yamlKey)

        entity.configYamlKey = yamlKey
        entity.blocksTotal   = entity.blocks.size

        val saved = repository.save(entity)
        log.info(
            "Saved scenario '{}' ('{}') with {} blocks and {} tests",
            saved.id, saved.name, saved.blocksTotal, saved.totalTests()
        )
        return saved
    }

    /**
     * Удаляет сценарий и его YAML-конфиг из S3.
     * Нельзя удалить сценарий в статусе [ExecutionStatus.RUNNING].
     *
     * @throws IllegalStateException если сценарий выполняется.
     */
    @Transactional
    fun delete(id: String) {
        val entity = repository.findById(id).orElseThrow {
            NoSuchElementException("Scenario '$id' not found")
        }
        check(entity.status != ExecutionStatus.RUNNING) {
            "Cannot delete scenario '$id': currently RUNNING"
        }

        configStore.delete(entity.configYamlKey)
        repository.delete(entity)
        log.info("Deleted scenario '{}'", id)
    }

    /**
     * Возвращает presigned URL для прямого скачивания YAML-конфига из S3.
     * URL действителен 15 минут.
     */
    fun configDownloadUrl(id: String): String {
        val entity = repository.findById(id).orElseThrow {
            NoSuchElementException("Scenario '$id' not found")
        }
        return configStore.presignedDownloadUrl(entity.configYamlKey)
    }

    /** Содержимое YAML-конфига из S3 (для карточки сценария в UI). */
    fun readConfigYaml(id: String): String {
        val entity = repository.findById(id).orElseThrow {
            NoSuchElementException("Scenario '$id' not found")
        }
        return configStore.download(entity.configYamlKey)
    }
}
