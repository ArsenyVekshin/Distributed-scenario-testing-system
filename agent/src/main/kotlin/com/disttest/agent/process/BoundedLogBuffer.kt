package com.disttest.agent.process

/**
 * Накапливает вывод процесса, сохраняя только последние [maxBytes] байт.
 * При переполнении обрезает начало буфера (ring-like behaviour).
 */
class BoundedLogBuffer(private val maxBytes: Int = 1_048_576) {

    private val buffer = StringBuilder()

    @Synchronized
    fun append(chunk: String) {
        buffer.append(chunk)
        if (buffer.length > maxBytes) {
            buffer.delete(0, buffer.length - maxBytes)
        }
    }

    @Synchronized
    fun get(): String = buffer.toString()
}
