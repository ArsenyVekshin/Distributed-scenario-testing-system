package com.disttest.coordinator.temporal

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.disttest.coordinator.temporal.state.ScenarioStateActivitiesImpl
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Configuration
class TemporalConfig {

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
     * Single coordinator worker on [TaskQueues.COORDINATOR].
     * Registers all coordinator-side workflows and state activities.
     * Actual start is deferred to [TemporalWorkerStarter].
     */
    @Bean(destroyMethod = "shutdown")
    fun workerFactory(
        client: WorkflowClient,
        stateActivities: ScenarioStateActivitiesImpl,
    ): WorkerFactory {
        val factory = WorkerFactory.newInstance(client)
        val worker  = factory.newWorker(TaskQueues.COORDINATOR)

        // Workflows (coordinator orchestrates all scenario runs)
        worker.registerWorkflowImplementationTypes(
            ScenarioRunWorkflowImpl::class.java,
            TestScenarioWorkflowImpl::class.java,
        )

        // State activities (coordinator-side DB persistence)
        worker.registerActivitiesImplementations(stateActivities)

        return factory
    }
}

/**
 * Starts the Temporal worker after the application context is fully initialised.
 * Retries every 5 s until Temporal is available.
 */
@Component
class TemporalWorkerStarter(private val workerFactory: WorkerFactory) {

    private val log = LoggerFactory.getLogger(TemporalWorkerStarter::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        Thread(::startWithRetry, "temporal-worker-starter")
            .also { it.isDaemon = true }
            .start()
    }

    private fun startWithRetry() {
        var attempt = 0
        while (!Thread.currentThread().isInterrupted) {
            attempt++
            try {
                workerFactory.start()
                log.info("Coordinator Temporal worker started on queue '{}'", TaskQueues.COORDINATOR)
                return
            } catch (e: Exception) {
                log.warn("Temporal not reachable (attempt {}): {}. Retrying in 5 s…", attempt, e.message)
                Thread.sleep(5_000)
            }
        }
    }
}
