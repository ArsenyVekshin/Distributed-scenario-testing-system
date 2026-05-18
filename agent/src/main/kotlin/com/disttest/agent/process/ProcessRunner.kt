package com.disttest.agent.process

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long,
)

object ProcessRunner {

    private val log = LoggerFactory.getLogger(ProcessRunner::class.java)

    /**
     * Runs an external command, capturing stdout and stderr in parallel to avoid pipe-buffer deadlocks.
     * Output is bounded by [maxLogBytes] (last N bytes).
     */
    fun run(
        command: List<String>,
        workingDir: Path? = null,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long? = null,
        maxLogBytes: Int = 1_048_576,
    ): ProcessResult {
        log.debug("Executing: {}", command.joinToString(" "))

        val builder = ProcessBuilder(command)
            .redirectErrorStream(false)
        workingDir?.let { builder.directory(it.toFile()) }
        if (env.isNotEmpty()) {
            builder.environment().putAll(env)
        }

        val startedAt = System.currentTimeMillis()
        val process = builder.start()

        val stdoutBuf = BoundedLogBuffer(maxLogBytes)
        val stderrBuf = BoundedLogBuffer(maxLogBytes)

        val readerPool = Executors.newFixedThreadPool(2)
        val stdoutFuture = readerPool.submit { drain(process.inputStream, stdoutBuf) }
        val stderrFuture = readerPool.submit { drain(process.errorStream, stderrBuf) }

        val timedOut: Boolean
        val exitCode: Int?

        if (timeoutSeconds != null && timeoutSeconds > 0) {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                timedOut = true
                exitCode = null
                log.warn("Process timed out after {}s: {}", timeoutSeconds, command.firstOrNull())
            } else {
                timedOut = false
                exitCode = process.exitValue()
            }
        } else {
            exitCode = process.waitFor()
            timedOut = false
        }

        // Wait for reader threads (they will finish now that the process stream is closed)
        stdoutFuture.get(5, TimeUnit.SECONDS)
        stderrFuture.get(5, TimeUnit.SECONDS)
        readerPool.shutdown()

        val durationMs = System.currentTimeMillis() - startedAt
        return ProcessResult(
            exitCode  = exitCode,
            stdout    = stdoutBuf.get(),
            stderr    = stderrBuf.get(),
            timedOut  = timedOut,
            durationMs = durationMs,
        )
    }

    private fun drain(input: InputStream, buffer: BoundedLogBuffer) {
        try {
            val reader = input.bufferedReader(Charsets.UTF_8)
            reader.lineSequence().forEach { line ->
                buffer.append(line + "\n")
            }
        } catch (_: Exception) { /* stream closed */ }
    }
}
