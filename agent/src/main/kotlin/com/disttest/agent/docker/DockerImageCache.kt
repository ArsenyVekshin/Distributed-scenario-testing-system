package com.disttest.agent.docker

import com.disttest.agent.process.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

private const val MANAGED_LABEL = "disttest.managed=true"
private const val MAX_MANAGED_IMAGES = 10

@Component
class DockerImageCache {

    private val log = LoggerFactory.getLogger(DockerImageCache::class.java)

    /** Returns true if the image is already loaded on this agent. */
    fun imageExists(imageTag: String): Boolean {
        val result = ProcessRunner.run(listOf("docker", "image", "inspect", imageTag))
        return result.exitCode == 0
    }

    /** Loads a Docker image from a tar file. */
    fun loadImage(tarPath: Path) {
        log.info("Loading Docker image from {}", tarPath)
        val result = ProcessRunner.run(listOf("docker", "load", "-i", tarPath.toString()))
        if (result.exitCode != 0) {
            throw RuntimeException("docker load failed (exit=${result.exitCode}): ${result.stderr}")
        }
    }

    /**
     * Evicts oldest managed images if count exceeds [MAX_MANAGED_IMAGES].
     * Only manages images with label [MANAGED_LABEL].
     */
    fun evictIfNeeded() {
        // List managed images sorted by creation time (oldest first)
        val result = ProcessRunner.run(
            listOf(
                "docker", "images",
                "--filter", "label=$MANAGED_LABEL",
                "--format", "{{.ID}}\t{{.CreatedAt}}",
                "--no-trunc"
            )
        )
        if (result.exitCode != 0) return

        val lines = result.stdout.lines().filter { it.isNotBlank() }
        if (lines.size <= MAX_MANAGED_IMAGES) return

        val toEvict = lines.dropLast(MAX_MANAGED_IMAGES)
        toEvict.forEach { line ->
            val imageId = line.substringBefore("\t").trim()
            log.info("Evicting managed image: {}", imageId)
            ProcessRunner.run(listOf("docker", "rmi", "-f", imageId))
        }
    }
}
