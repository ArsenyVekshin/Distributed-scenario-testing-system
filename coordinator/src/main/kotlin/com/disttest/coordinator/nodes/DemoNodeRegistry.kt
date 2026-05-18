package com.disttest.coordinator.nodes

import com.disttest.coordinator.dto.NodeHeartbeatRequest
import com.disttest.coordinator.dto.NodeInfo
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry fed by agent heartbeats.
 * The UI reads this through Coordinator REST API, so agent state does not live in frontend stubs.
 */
@Component
class DemoNodeRegistry(
    @Value("\${nodes.offline-timeout-seconds:20}")
    private val offlineTimeoutSeconds: Long,
) {

    private val nodes = ConcurrentHashMap<String, RegisteredNode>()

    fun list(): List<NodeInfo> = nodes.values
        .map { it.toInfo() }
        .sortedBy { it.registeredAt }

    fun get(id: String): NodeInfo? = nodes[id]?.toInfo()

    fun register(id: String, address: String): NodeInfo {
        val now = Instant.now()
        val node = nodes.compute(id) { _, existing ->
            existing?.copy(
                address = address,
                status = "IDLE",
                currentBlockId = null,
                lastHeartbeatAt = now,
            ) ?: RegisteredNode(
                id = id,
                address = address,
                status = "IDLE",
                currentBlockId = null,
                registeredAt = now,
                lastHeartbeatAt = now,
            )
        }!!
        return node.toInfo(now)
    }

    fun heartbeat(request: NodeHeartbeatRequest): NodeInfo {
        val now = Instant.now()
        val node = nodes.compute(request.id) { _, existing ->
            RegisteredNode(
                id = request.id,
                address = request.address,
                status = normalizeStatus(request.status),
                currentBlockId = request.currentBlockId?.takeIf { it.isNotBlank() },
                registeredAt = existing?.registeredAt ?: now,
                lastHeartbeatAt = now,
            )
        }!!
        return node.toInfo(now)
    }

    fun remove(id: String): Boolean = nodes.remove(id) != null

    private fun RegisteredNode.toInfo(now: Instant = Instant.now()): NodeInfo {
        val offline = Duration.between(lastHeartbeatAt, now).seconds > offlineTimeoutSeconds
        return NodeInfo(
            id = id,
            address = address,
            status = if (offline) "OFFLINE" else status,
            currentBlockId = if (offline) null else currentBlockId,
            registeredAt = registeredAt,
            lastHeartbeatAt = lastHeartbeatAt,
        )
    }

    private fun normalizeStatus(status: String): String =
        when (status.uppercase()) {
            "BUSY" -> "BUSY"
            "OFFLINE" -> "OFFLINE"
            else -> "IDLE"
        }
}

private data class RegisteredNode(
    val id: String,
    val address: String,
    val status: String,
    val currentBlockId: String?,
    val registeredAt: Instant,
    val lastHeartbeatAt: Instant,
)
