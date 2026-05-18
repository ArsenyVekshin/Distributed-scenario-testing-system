package com.disttest.coordinator.scenario

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Единственная ошибка валидации с точным местом в конфиге.
 *
 * [path]    — путь к полю в формате JSONPath-style, например:
 *             `blocks[1].tests[0].command`
 * [message] — человекочитаемое описание проблемы.
 */
data class ValidationError(
    val path: String,
    val message: String,
) {
    override fun toString() = "[$path] $message"
}

/**
 * Исключение, которое бросает [ScenarioParser].
 * Содержит **полный список** ошибок (парсер не останавливается на первой).
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
class ScenarioValidationException(
    val errors: List<ValidationError>,
) : RuntimeException(
    "Scenario config is invalid (${errors.size} error${if (errors.size == 1) "" else "s"}):\n" +
        errors.joinToString("\n") { "  • $it" }
) {
    /** Удобный конструктор для одиночной ошибки. */
    constructor(path: String, message: String) : this(listOf(ValidationError(path, message)))
}
