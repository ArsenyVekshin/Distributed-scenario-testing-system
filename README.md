# Distributed Scenario Testing System

Распределённая система тестирования программного обеспечения с поддержкой параллельного
выполнения тестовых сценариев на нескольких вычислительных узлах.

---

## Архитектура

```
┌──────────────────────────────────────────────────────────────┐
│  Web UI  (порт 3000)                                         │
│  Статическая страница мониторинга + проксирование на API     │
└──────────────────┬───────────────────────────────────────────┘
                   │ HTTP /api/*
┌──────────────────▼───────────────────────────────────────────┐
│  Coordinator  (порт 8080)                                    │
│  · Загрузка и хранение сценариев                             │
│  · Формирование плана выполнения (граф блоков)               │
│  · Распределение блоков между агентами                       │
│  · Сбор и агрегация результатов                              │
└──────┬──────────────────────────────────┬────────────────────┘
       │ Temporal workflow                │ PostgreSQL
┌──────▼──────────┐            ┌──────────▼──────────┐
│  Temporal       │            │  PostgreSQL          │
│  (порт 7233)    │            │  (порт 5432)         │
│  + Temporal UI  │            │  БД: coordinator     │
│  (порт 8088)    │            │  БД: temporal*       │
└──────┬──────────┘            └─────────────────────┘
       │ task queue
┌──────▼──────────────────────────────────────────────────────┐
│  Agent  (порт 8081)                                         │
│  · Приём блоков от координатора                             │
│  · Параллельный запуск тестов внутри блока                  │
│  · Отправка результатов обратно                             │
└─────────────────────────────────────────────────────────────┘

* создаётся автоматически контейнером temporalio/auto-setup
```

---

## Требования

| Компонент   | Версия  |
|-------------|---------|
| Docker      | 27.x +  |
| Docker Compose | v2 (встроен в Docker Desktop) |

Всё остальное (JDK 21, Gradle, Kotlin) устанавливается внутри контейнеров.

---

## Быстрый старт

### 1. Клонировать / открыть репозиторий

```powershell
cd "d:\Documents\Distributed-scenario-testing-system"
```

### 2. Собрать и запустить все сервисы

```powershell
docker compose up --build
```

> **Первый запуск занимает 5–10 минут** — Gradle скачивает зависимости,
> Docker собирает образы. Последующие запуски значительно быстрее (кэш слоёв).

### 3. Дождаться готовности

Следить за логами можно в отдельном терминале:

```powershell
docker compose logs -f
```

Сервисы стартуют в следующем порядке:

1. **postgresql** → готов когда в логах появится `database system is ready to accept connections`
2. **temporal** → готов когда появится `Started Temporal server`
3. **coordinator / agent** → готовы когда появится `Started CoordinatorApplication` / `Started AgentApplication`

---

## Адреса сервисов после запуска

| Сервис             | URL                                      | Описание                              |
|--------------------|------------------------------------------|---------------------------------------|
| Web UI             | http://localhost:3000                    | Дашборд мониторинга (заглушка)        |
| Coordinator API    | http://localhost:8080/swagger-ui.html    | Swagger UI координатора (springdoc)   |
| Coordinator Health | http://localhost:8080/actuator/health    | Health-check координатора             |
| Agent API          | http://localhost:8081/swagger-ui         | Swagger UI агента                     |
| Agent Health       | http://localhost:8081/actuator/health    | Health-check агента                   |
| Temporal UI        | http://localhost:8088                    | Веб-интерфейс Temporal                |
| PostgreSQL         | localhost:5433                           | БД (user: disttest / pass: disttest)  |

---

## Управление окружением

### Остановить все сервисы (сохранить данные)

```powershell
docker compose stop
```

### Запустить после остановки

```powershell
docker compose start
```

### Остановить и удалить контейнеры (данные БД сохраняются в volume)

```powershell
docker compose down
```

### Полный сброс (удалить контейнеры И данные БД)

```powershell
docker compose down -v
```

### Пересобрать только один сервис

```powershell
docker compose up --build coordinator
```

### Просмотр логов конкретного сервиса

```powershell
docker compose logs -f coordinator
docker compose logs -f agent
docker compose logs -f temporal
```

### Запустить несколько агентов (масштабирование)

Агент — чистый Temporal worker без HTTP-порта, готов к горизонтальному масштабированию:

```powershell
docker compose up --scale agent=3
```

Temporal автоматически распределяет задачи (блоки тестов) между всеми запущенными воркерами.

---

## Структура проекта

```
.
├── coordinator/                  # Сервис-координатор (Kotlin + Spring Boot)
│   ├── src/main/kotlin/
│   │   └── com/disttest/coordinator/
│   │       ├── CoordinatorApplication.kt
│   │       └── controller/
│   │           ├── ScenarioController.kt   # CRUD сценариев + запуск
│   │           ├── NodeController.kt       # Управление агентами
│   │           └── ReportController.kt     # Отчёты
│   ├── src/main/resources/application.yml
│   ├── build.gradle.kts
│   └── Dockerfile
│
├── agent/                        # Сервис-агент (Kotlin + Spring Boot)
│   ├── src/main/kotlin/
│   │   └── com/disttest/agent/
│   │       ├── AgentApplication.kt
│   │       └── controller/
│   │           └── BlockController.kt      # Выполнение блоков тестов
│   ├── src/main/resources/application.yml
│   ├── build.gradle.kts
│   └── Dockerfile
│
├── web-ui/                       # Фронтенд (nginx + статический HTML)
│   ├── index.html
│   ├── nginx.conf
│   └── Dockerfile
│
├── infrastructure/
│   └── init-db.sql               # Инициализация БД coordinator
│
├── docker-compose.yml            # Оркестрация всех сервисов
└── README.md
```

---

## Конфигурация

Все настройки передаются через переменные окружения в `docker-compose.yml`.

### Coordinator

| Переменная                  | По умолчанию                              | Описание                        |
|-----------------------------|-------------------------------------------|---------------------------------|
| `SPRING_DATASOURCE_URL`     | `jdbc:postgresql://postgresql:5432/coordinator` | URL базы данных            |
| `SPRING_DATASOURCE_USERNAME`| `disttest`                                | Пользователь PostgreSQL         |
| `SPRING_DATASOURCE_PASSWORD`| `disttest`                                | Пароль PostgreSQL               |
| `TEMPORAL_SERVICE_ADDRESS`  | `temporal:7233`                           | Адрес Temporal сервера          |
| `SERVER_PORT`               | `8080`                                    | HTTP-порт сервиса               |

### Agent

| Переменная                 | По умолчанию      | Описание                                              |
|----------------------------|-------------------|-------------------------------------------------------|
| `TEMPORAL_SERVICE_ADDRESS` | `temporal:7233`   | Адрес Temporal сервера                                |
| `TEMPORAL_NAMESPACE`       | `default`         | Namespace Temporal                                    |
| `AGENT_TASK_QUEUE`         | `agent-task-queue`| Очередь, из которой агент забирает задачи на блоки    |
| `SERVER_PORT`              | `8081`            | HTTP-порт для `/actuator/health` (опционально)        |

---

## Технологии

| Технология              | Версия  | Роль                                               |
|-------------------------|---------|----------------------------------------------------|
| Kotlin                  | 2.1.0   | Основной язык реализации                           |
| Java (Eclipse Temurin)  | 21      | Целевая JVM                                        |
| Spring Boot             | 3.4.2   | REST-фреймворк (по ТЗ — 4.0.x, будет обновлено)   |
| Temporal                | 1.27.2  | Оркестрация распределённого выполнения             |
| PostgreSQL              | 16      | Хранение сценариев и результатов                   |
| springdoc-openapi       | 2.8.6   | Swagger UI для REST API                            |
| nginx                   | 1.27    | Сервер статики + reverse proxy для Web UI          |
| Docker Compose          | v2      | Локальное окружение                                |

---
