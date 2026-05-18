package com.disttest.coordinator.controller

import com.disttest.coordinator.dto.NodeInfo
import com.disttest.coordinator.dto.NodeHeartbeatRequest
import com.disttest.coordinator.dto.RegisterNodeRequest
import com.disttest.coordinator.nodes.DemoNodeRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/nodes")
@Tag(name = "Nodes", description = "Управление вычислительными узлами (агентами)")
class NodeController(
    private val registry: DemoNodeRegistry,
) {

    @GetMapping
    @Operation(summary = "Список зарегистрированных узлов")
    fun listNodes(): ResponseEntity<List<NodeInfo>> =
        ResponseEntity.ok(registry.list())

    @GetMapping("/{id}")
    @Operation(summary = "Информация об узле по ID")
    fun getNode(@PathVariable id: String): ResponseEntity<NodeInfo> {
        val node = registry.get(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Node '$id' not found")
        return ResponseEntity.ok(node)
    }

    @PostMapping
    @Operation(summary = "Зарегистрировать новый вычислительный узел")
    fun registerNode(@RequestBody request: RegisterNodeRequest): ResponseEntity<NodeInfo> {
        if (request.id.isBlank() || request.address.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "id and address must not be blank")
        }
        val info = registry.register(request.id, request.address)
        return ResponseEntity.status(HttpStatus.CREATED).body(info)
    }

    @PostMapping("/heartbeat")
    @Operation(summary = "Heartbeat от агента с текущим статусом")
    fun heartbeat(@RequestBody request: NodeHeartbeatRequest): ResponseEntity<NodeInfo> {
        if (request.id.isBlank() || request.address.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "id and address must not be blank")
        }
        return ResponseEntity.ok(registry.heartbeat(request))
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить вычислительный узел")
    fun removeNode(@PathVariable id: String): ResponseEntity<Void> {
        if (!registry.remove(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Node '$id' not found")
        }
        return ResponseEntity.noContent().build()
    }
}
