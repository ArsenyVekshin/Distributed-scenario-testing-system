package com.disttest.agent.nodes

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Component
class AgentNodeClient(
    @Value("\${coordinator.base-url:http://localhost:8080}")
    private val coordinatorBaseUrl: String,
    @Value("\${agent.id:}")
    private val configuredAgentId: String,
    @Value("\${agent.address:}")
    private val configuredAddress: String,
    @Value("\${server.port:8081}")
    private val serverPort: String,
) {

    private val log = LoggerFactory.getLogger(AgentNodeClient::class.java)
    private val restClient = RestClient.builder()
        .baseUrl(coordinatorBaseUrl.trimEnd('/'))
        .build()
    private val activeWork = AtomicInteger(0)
    private val currentWorkId = AtomicReference<String?>(null)

    val agentId: String by lazy { resolveAgentId() }
    private val address: String by lazy { resolveAddress() }

    @Scheduled(
        fixedDelayString = "\${agent.heartbeat-interval-ms:5000}",
        initialDelayString = "\${agent.heartbeat-initial-delay-ms:1000}",
    )
    fun idleHeartbeat() {
        heartbeatCurrentState()
    }

    fun busy(currentBlockId: String) {
        activeWork.incrementAndGet()
        currentWorkId.set(currentBlockId)
        heartbeatCurrentState()
    }

    fun blockStarted(blockRunId: String, currentBlockId: String) {
        busy(currentBlockId)
        runCatching {
            restClient.post()
                .uri("/api/runs/blocks/{blockRunId}/started", blockRunId)
                .body(MarkBlockStartedRequest(agentId))
                .retrieve()
                .toBodilessEntity()
        }.onFailure {
            log.debug("Could not mark block '{}' as started: {}", blockRunId, it.message)
        }
    }

    fun idle() {
        if (activeWork.decrementAndGet() <= 0) {
            activeWork.set(0)
            currentWorkId.set(null)
        }
        heartbeatCurrentState()
    }

    private fun heartbeatCurrentState() {
        val busy = activeWork.get() > 0
        heartbeat(
            status = if (busy) "BUSY" else "IDLE",
            currentBlockId = if (busy) currentWorkId.get() else null,
        )
    }

    private fun heartbeat(status: String, currentBlockId: String?) {
        runCatching {
            restClient.post()
                .uri("/api/nodes/heartbeat")
                .body(NodeHeartbeatRequest(agentId, address, status, currentBlockId))
                .retrieve()
                .toBodilessEntity()
        }.onFailure {
            log.debug("Could not send agent heartbeat to '{}': {}", coordinatorBaseUrl, it.message)
        }
    }

    private fun resolveAgentId(): String =
        configuredAgentId.ifBlank { localHostName() }.ifBlank { "unknown-agent" }

    private fun resolveAddress(): String =
        configuredAddress.ifBlank { "${localHostName()}:$serverPort" }

    private fun localHostName(): String =
        runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("")
}

private data class NodeHeartbeatRequest(
    val id: String,
    val address: String,
    val status: String,
    val currentBlockId: String?,
)

private data class MarkBlockStartedRequest(
    val agentId: String,
)
