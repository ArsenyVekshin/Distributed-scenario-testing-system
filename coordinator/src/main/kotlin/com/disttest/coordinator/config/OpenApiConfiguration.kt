package com.disttest.coordinator.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Документация REST API (OpenAPI 3) и Swagger UI.
 *
 * **Без** [org.springdoc.core.models.GroupedOpenApi]: при группах и кастомном `api-docs.path`
 * спецификация оказывается на URL вида `/api-docs/{group}`, а Swagger UI часто не подхватывает
 * это автоматически — страница не грузится или пустая. Одна дефолтная спека + пути springdoc
 * по умолчанию (`/v3/api-docs`, `/swagger-ui.html`) стабильнее, в том числе под Podman.
 */
@Configuration
class OpenApiConfiguration {

    @Bean
    fun openAPI(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Distributed Scenario Testing — Coordinator")
                    .description(
                        "REST API координатора: сценарии, узлы-агенты, отчёты. " +
                            "Веб-интерфейс обращается к тем же эндпоинтам под префиксом `/api`."
                    )
                    .version("0.1.0")
                    .contact(Contact().name("DST Coordinator"))
            )
            .servers(
                listOf(
                    Server().url("http://localhost:8080").description("Локальный запуск"),
                    Server().url("/").description("Относительно текущего хоста (Docker / reverse proxy)"),
                )
            )
}
