package com.disttest.coordinator.config

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Только корень `/swagger-ui` — без перехвата `/swagger-ui/index.html`: иначе MVC-контроллер
 * перекрывает статику springdoc и получается цикл редиректов с `/swagger-ui.html`.
 */
@Controller
class SwaggerRedirectController {

    @GetMapping("/swagger-ui", "/swagger-ui/")
    fun redirectSwaggerUiRoot(): String = "redirect:/swagger-ui.html"
}
