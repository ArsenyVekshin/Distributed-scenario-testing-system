package com.disttest.coordinator.dto

import java.time.Instant

data class NodeInfo(
    val id: String,
    val address: String,
    val status: String,
    val currentBlockId: String?,
    val registeredAt: Instant,
    val lastHeartbeatAt: Instant? = null,
)

data class RegisterNodeRequest(
    val id: String,
    val address: String,
)

data class NodeHeartbeatRequest(
    val id: String,
    val address: String,
    val status: String = "IDLE",
    val currentBlockId: String? = null,
)
