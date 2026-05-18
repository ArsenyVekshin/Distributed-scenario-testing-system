# Язык описания тестовых сценариев

Формат конфигурационного файла для системы распределённого тестирования.  
Версия спецификации: **1**.

---

## Содержание

1. [Обзор](#1-обзор)
2. [Быстрый старт](#2-быстрый-старт)
3. [Структура файла](#3-структура-файла)
4. [Справочник полей](#4-справочник-полей)
   - [Сценарий (корневой объект)](#41-сценарий-корневой-объект)
   - [Блок](#42-блок)
   - [Тест](#43-тест)
5. [Граф зависимостей (DAG)](#5-граф-зависимостей-dag)
6. [Наследование переменных окружения](#6-наследование-переменных-окружения)
7. [Правила валидации](#7-правила-валидации)
8. [Ошибки парсинга](#8-ошибки-парсинга)
9. [Примеры](#9-примеры)
10. [Механика сохранения](#10-механика-сохранения)

---

## 1. Обзор

Конфигурационный файл описывает **тестовый сценарий** — ориентированный ациклический граф (DAG)
блоков выполнения. Каждый **блок** содержит один или несколько **тестов**, которые агент запускает
параллельно.

Ключевые концепции:

| Понятие | Что это |
|---|---|
| **Сценарий** | Корневой объект. Задаёт имя, глобальный таймаут и общие переменные окружения. |
| **Блок** | Узел графа. Группа тестов с одинаковым набором зависимостей. |
| **Тест** | Лист графа. Одна команда, выполняемая агентом. |
| **dependsOn** | Рёбра графа. Блок запускается только после успешного завершения всех блоков из `dependsOn`. |

Файл пишется в формате **YAML**. Рекомендуемое расширение: `.dst.yaml`.

---

## 2. Быстрый старт

Минимальный рабочий конфиг:

```yaml
version: "1"
name: "Smoke Tests"

blocks:
  - id: smoke
    tests:
      - id: health-check
        command: "curl -f http://localhost:8080/actuator/health"
```

---

## 3. Структура файла

```
scenario
├── version          # обязательное
├── name             # обязательное
├── description
├── timeout
├── env              # глобальные переменные окружения
└── blocks[]
    ├── id           # обязательное, уникально в сценарии
    ├── name
    ├── parallelism
    ├── timeout
    ├── dependsOn[]
    ├── tags[]
    ├── env          # env уровня блока
    └── tests[]
        ├── id       # обязательное, уникально в блоке
        ├── name
        ├── command  # обязательное
        ├── timeout
        ├── retries
        ├── workdir
        ├── tags[]
        └── env      # env уровня теста (наивысший приоритет)
```

---

## 4. Справочник полей

### 4.1. Сценарий (корневой объект)

| Поле | Тип | Обязательное | По умолчанию | Описание |
|---|---|---|---|---|
| `version` | string | да | — | Версия формата. Единственное значение: `"1"`. |
| `name` | string | да | — | Название сценария. Не может быть пустым. |
| `description` | string | нет | `""` | Произвольное описание. |
| `timeout` | integer | нет | `86400` | Максимальное время выполнения всего сценария, секунды. `0` — без ограничений. |
| `env` | map[string]string | нет | `{}` | Переменные окружения уровня сценария. Наследуются всеми тестами. |
| `blocks` | list[Block] | да | — | Список блоков. Минимум 1. |

### 4.2. Блок

| Поле | Тип | Обязательное | По умолчанию | Описание |
|---|---|---|---|---|
| `id` | string | да | — | Уникальный идентификатор блока. Используется в `dependsOn`. Допустимы: буквы, цифры, `-`, `_`. |
| `name` | string | нет | = `id` | Отображаемое имя в интерфейсе. |
| `parallelism` | integer | нет | `1` | Количество параллельных потоков для запуска тестов. Минимум `1`. |
| `timeout` | integer | нет | `0` | Таймаут блока целиком, секунды. `0` — только ограничен таймаутом сценария. |
| `dependsOn` | list[string] | нет | `[]` | ID блоков-предшественников. Все они должны завершиться успешно перед запуском этого блока. |
| `tags` | list[string] | нет | `[]` | Метки для фильтрации и группировки в отчётах. |
| `env` | map[string]string | нет | `{}` | Переменные окружения уровня блока. Мерджатся поверх глобальных. |
| `tests` | list[Test] | да | — | Список тестов. Минимум 1. |

### 4.3. Тест

| Поле | Тип | Обязательное | По умолчанию | Описание |
|---|---|---|---|---|
| `id` | string | да | — | Уникальный идентификатор теста внутри блока. |
| `name` | string | нет | = `id` | Отображаемое имя. |
| `command` | string | да | — | Команда, выполняемая агентом в shell. Не может быть пустой. |
| `timeout` | integer | нет | `60` | Таймаут теста, секунды. Должен быть `> 0`. Псевдонимы: `timeoutSeconds`, `timeout_seconds`. |
| `retries` | integer | нет | `0` | Количество повторных попыток при ненулевом exit code. `0` — не повторять. |
| `workdir` | string | нет | `""` | Рабочая директория для команды. Пустая строка — директория по умолчанию агента. |
| `tags` | list[string] | нет | `[]` | Метки теста. |
| `env` | map[string]string | нет | `{}` | Переменные окружения теста. Переопределяют env блока и сценария. |

---

## 5. Граф зависимостей (DAG)

Блоки образуют **ориентированный ациклический граф** (DAG). Ребро направлено от предшественника
к преемнику: если `B` содержит `dependsOn: [A]`, то A → B.

### Уровни выполнения

Граф разбивается на **уровни** по алгоритму Кана:

- Уровень 0 — все блоки без зависимостей (корни графа).
- Уровень N — блоки, все предшественники которых входят в уровни 0..N-1.
- Все блоки одного уровня выполняются **параллельно**.
- Уровень N запускается только после успешного завершения уровня N-1.

**Пример:**

```
prepare ──┬── unit-tests ──── integration
          └── lint
```

```yaml
blocks:
  - id: prepare
    ...
  - id: unit-tests
    dependsOn: [prepare]
    ...
  - id: lint
    dependsOn: [prepare]
    ...
  - id: integration
    dependsOn: [unit-tests]
    ...
```

Уровни выполнения:
- Уровень 0: `prepare`
- Уровень 1: `unit-tests`, `lint` ← параллельно
- Уровень 2: `integration`

### Ограничения

- Граф должен быть **ациклическим** — цикл в зависимостях вызывает ошибку валидации.
- Блок **не может зависеть от самого себя**.
- Каждый `id` в `dependsOn` должен ссылаться на блок, существующий в этом же сценарии.

---

## 6. Наследование переменных окружения

Переменные окружения наследуются по трёхуровневой иерархии:

```
сценарий.env
    ↓  (переопределяется)
блок.env
    ↓  (переопределяется)
тест.env
    ↓
итоговый env, который получает агент
```

Мёрдж происходит **при парсинге**: агент получает уже вычисленный плоский `Map<String, String>`.

**Пример:**

```yaml
env:
  APP_ENV: testing          # глобально
  LOG_LEVEL: info

blocks:
  - id: api-tests
    env:
      LOG_LEVEL: debug      # переопределяет глобальное
    tests:
      - id: auth-test
        command: "pytest tests/auth/"
        env:
          BASE_URL: http://localhost:9090   # только для этого теста

      - id: order-test
        command: "pytest tests/orders/"
        # унаследует APP_ENV=testing, LOG_LEVEL=debug
```

Итоговый env для `auth-test`:
```
APP_ENV=testing
LOG_LEVEL=debug
BASE_URL=http://localhost:9090
```

Итоговый env для `order-test`:
```
APP_ENV=testing
LOG_LEVEL=debug
```

---

## 7. Правила валидации

Парсер проверяет все правила за **один проход** и возвращает полный список ошибок.

| # | Правило | Пример ошибки |
|---|---|---|
| 1 | `version` должен быть `"1"` | `[version] Unsupported config version '2'. Supported: 1` |
| 2 | `name` не пустой | `[name] Must not be blank` |
| 3 | `timeout` ≥ 0 | `[timeout] Must be >= 0, got -1` |
| 4 | Минимум 1 блок | `[blocks] Scenario must contain at least one block` |
| 5 | ID блоков уникальны | `[blocks] Duplicate block id 'prepare'` |
| 6 | `block.id` не пустой | `[blocks[0].id] Must not be blank` |
| 7 | `block.parallelism` ≥ 1 | `[blocks[1].parallelism] Must be >= 1, got 0` |
| 8 | `block.timeout` ≥ 0 | `[blocks[1].timeout] Must be >= 0, got -5` |
| 9 | Каждый `dependsOn[i]` ссылается на существующий блок | `[blocks[2].dependsOn[0]] References unknown block 'missing'` |
| 10 | Блок не ссылается на себя | `[blocks[1].dependsOn[0]] Block 'unit' cannot depend on itself` |
| 11 | Минимум 1 тест в блоке | `[blocks[0].tests] Block 'prepare' must have at least one test` |
| 12 | ID тестов уникальны в блоке | `[blocks[0].tests] Duplicate test id 'init-db'` |
| 13 | `test.id` не пустой | `[blocks[0].tests[0].id] Must not be blank` |
| 14 | `test.command` не пустой | `[blocks[0].tests[0].command] Must not be blank` |
| 15 | `test.timeout` > 0 | `[blocks[0].tests[0].timeout] Must be > 0, got 0` |
| 16 | `test.retries` ≥ 0 | `[blocks[0].tests[0].retries] Must be >= 0, got -1` |
| 17 | Граф ациклический | `[blocks] Cycle detected in block dependency graph` |

---

## 8. Ошибки парсинга

При невалидном конфиге API возвращает `422 Unprocessable Entity`:

```json
{
  "message": "Scenario config is invalid",
  "errors": [
    {
      "path": "blocks[1].dependsOn[0]",
      "message": "References unknown block 'missing-block'"
    },
    {
      "path": "blocks[2].tests[0].command",
      "message": "Must not be blank"
    }
  ]
}
```

Путь (`path`) следует нотации `blocks[i].tests[j].field`, что позволяет точно указать
на проблемное место в конфиге.

---

## 9. Примеры

### 9.1. Минимальный конфиг

```yaml
version: "1"
name: "Health Check"

blocks:
  - id: smoke
    tests:
      - id: ping
        command: "curl -sf http://app:8080/health"
```

### 9.2. Линейный пайплайн

```yaml
version: "1"
name: "Build → Test → Deploy"
description: "Standard CI pipeline"
timeout: 3600

blocks:
  - id: build
    name: "Build"
    tests:
      - id: compile
        command: "./gradlew build -x test"
        timeout: 300

  - id: test
    name: "Test"
    dependsOn: [build]
    parallelism: 4
    tests:
      - id: unit
        command: "./gradlew test"
        timeout: 120
      - id: lint
        command: "./gradlew ktlintCheck"
        timeout: 60

  - id: deploy
    name: "Deploy to staging"
    dependsOn: [test]
    tests:
      - id: push
        command: "docker push myapp:latest"
        timeout: 180
      - id: rollout
        command: "kubectl rollout restart deployment/myapp"
        timeout: 120
        retries: 2
```

### 9.3. Параллельные ветки с общим env и тегами

```yaml
version: "1"
name: "Full Regression Suite"
description: "Parallel regression with env inheritance"
timeout: 7200

env:
  APP_ENV: testing
  DB_URL: postgres://db:5432/test
  API_URL: http://api:8080

blocks:
  - id: setup
    name: "Database Setup"
    tags: [infrastructure]
    tests:
      - id: migrate
        command: "flyway migrate"
        timeout: 120
      - id: seed
        command: "python scripts/seed.py"
        timeout: 60
        env:
          SEED_PROFILE: full

  - id: unit-tests
    name: "Unit Tests"
    dependsOn: [setup]
    parallelism: 8
    tags: [unit, fast]
    tests:
      - id: auth
        command: "pytest tests/unit/auth/ -v"
        tags: [auth]
      - id: payments
        command: "pytest tests/unit/payments/ -v"
        tags: [payments]
      - id: notifications
        command: "pytest tests/unit/notifications/ -v"
        tags: [notifications]

  - id: api-tests
    name: "API Integration Tests"
    dependsOn: [setup]
    parallelism: 4
    tags: [integration, api]
    env:
      LOG_LEVEL: debug
    tests:
      - id: rest-auth
        command: "pytest tests/api/auth/"
        timeout: 120
      - id: rest-payments
        command: "pytest tests/api/payments/"
        timeout: 180
        retries: 1

  - id: e2e-tests
    name: "End-to-End Tests"
    dependsOn: [unit-tests, api-tests]
    parallelism: 2
    tags: [e2e, slow]
    tests:
      - id: checkout-flow
        command: "npx playwright test tests/e2e/checkout"
        timeout: 300
        workdir: /app/frontend
        env:
          HEADLESS: "true"
      - id: auth-flow
        command: "npx playwright test tests/e2e/auth"
        timeout: 240
        workdir: /app/frontend
        env:
          HEADLESS: "true"

  - id: report
    name: "Collect Reports"
    dependsOn: [e2e-tests]
    tests:
      - id: aggregate
        command: "python scripts/aggregate_reports.py"
        timeout: 60
```

**Граф уровней выполнения:**

```
Уровень 0:  setup
Уровень 1:  unit-tests  │  api-tests      ← параллельно
Уровень 2:  e2e-tests
Уровень 3:  report
```

---

## 10. Механика сохранения

### Жизненный цикл конфига

```
POST /api/scenarios
       │
       ▼
  ScenarioParser.parse(yaml)
  ├── YAML → ScenarioConfig (Jackson)
  ├── Валидация всех правил
  └── ScenarioConfig → ScenarioEntity + BlockEntity[] + TestCaseEntity[]
       │               (граф связан по полю scenario/block)
       │               (env мёрджится при этом шаге)
       ▼
  ConfigStore.upload(key, yaml)
  └── Загружает в MinIO S3
      Ключ: scenarios/{uuid}/config.yaml
       │
       ▼
  ScenarioRepository.save(entity)
  └── Каскадное сохранение в PostgreSQL:
      ├── scenarios
      ├── blocks
      ├── block_dependencies
      ├── block_tags
      ├── test_cases
      ├── test_case_env
      └── test_case_tags
       │
       ▼
  HTTP 201 Created  ←  ScenarioSummary
```

### Хранение в базе данных

| Таблица | Что хранит |
|---|---|
| `scenarios` | Метаданные сценария: имя, статус, таймаут, ключ S3 |
| `blocks` | Блоки: configId, parallelism, timeoutSeconds |
| `block_dependencies` | Рёбра графа: (block_id → depends_on_config_id) |
| `block_tags` | Метки блоков |
| `test_cases` | Тесты: команда, таймаут, retries, workdir |
| `test_case_env` | Итоговые переменные окружения тестов (уже смёрджены) |
| `test_case_tags` | Метки тестов |

### Хранение в S3 (MinIO)

Исходный YAML сохраняется в MinIO без изменений под ключом `scenarios/{id}/config.yaml`.

Это позволяет:
- Воспроизводить сценарий точно в том виде, в котором он был загружен.
- Скачивать конфиг через API (`GET /api/scenarios/{id}/config`), который возвращает presigned URL (действует 15 минут).
- Редактировать и повторно загружать в будущем (TODO).

### Удаление

`DELETE /api/scenarios/{id}` выполняет:
1. Удаление записей из PostgreSQL (каскадное — блоки и тесты).
2. Удаление YAML-файла из S3.

Удаление возможно только для сценариев не в статусе `RUNNING`.
