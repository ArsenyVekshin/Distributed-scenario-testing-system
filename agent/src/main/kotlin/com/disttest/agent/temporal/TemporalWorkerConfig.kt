package com.disttest.agent.temporal

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

const val BUILD_TASK_QUEUE  = "build-task-queue"
const val AGENT_TASK_QUEUE  = "agent-task-queue"

@Configuration
class TemporalWorkerConfig(
    private val testBlockActivities: TestBlockActivitiesImpl,
    private val buildProjectActivities: BuildProjectActivitiesImpl,
) {
    @Value("\${temporal.service-address}")
    private lateinit var serviceAddress: String

    @Value("\${temporal.namespace:default}")
    private lateinit var namespace: String

    @Bean
    fun workflowServiceStubs(): WorkflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(serviceAddress)
                .build()
        )

    @Bean
    fun temporalDataConverter(): DataConverter {
        val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        return DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(JacksonJsonPayloadConverter(objectMapper))
    }

    @Bean
    fun workflowClient(stubs: WorkflowServiceStubs, temporalDataConverter: DataConverter): WorkflowClient =
        WorkflowClient.newInstance(
            stubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .setDataConverter(temporalDataConverter)
                .build()
        )

    /**
     * Creates a [WorkerFactory] with two workers:
     *  - build-task-queue  → [BuildProjectActivitiesImpl]
     *  - agent-task-queue  → [TestBlockActivitiesImpl]
     * Starting is deferred to [AgentWorkerStarter].
     */
    @Bean(destroyMethod = "shutdown")
    fun workerFactory(client: WorkflowClient): WorkerFactory {
        val factory = WorkerFactory.newInstance(client)

        // Build worker — runs git clone, docker build, MinIO upload
        val buildWorker = factory.newWorker(
            BUILD_TASK_QUEUE,
            WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(1)
                .build()
        )
        buildWorker.registerActivitiesImplementations(buildProjectActivities)

        // Test worker — runs docker run for each test block
        val testWorker = factory.newWorker(
            AGENT_TASK_QUEUE,
            WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(1)
                .build()
        )
        testWorker.registerActivitiesImplementations(testBlockActivities)

        return factory
    }
}

@Component
class AgentWorkerStarter(private val workerFactory: WorkerFactory) {

    private val log = LoggerFactory.getLogger(AgentWorkerStarter::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        Thread(::startWithRetry, "temporal-agent-starter")
            .also { it.isDaemon = true }
            .start()
    }

    private fun startWithRetry() {
        var attempt = 0
        while (!Thread.currentThread().isInterrupted) {
            attempt++
            try {
                workerFactory.start()
                log.info("Agent Temporal workers started (queues: {}, {})", BUILD_TASK_QUEUE, AGENT_TASK_QUEUE)
                return
            } catch (e: Exception) {
                log.warn("Temporal not reachable (attempt {}): {}. Retrying in 5 s…", attempt, e.message)
                Thread.sleep(5_000)
            }
        }
    }
}
