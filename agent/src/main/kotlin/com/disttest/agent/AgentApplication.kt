package com.disttest.agent

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class AgentApplication

fun main(args: Array<String>) {
    runApplication<AgentApplication>(*args)
}
