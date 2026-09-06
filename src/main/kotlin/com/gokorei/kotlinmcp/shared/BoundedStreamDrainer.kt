package com.gokorei.kotlinmcp.shared

import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handle to an asynchronous stream draining task.
 */
class BoundedDrainHandle(
    private val thread: Thread,
    private val buffer: ByteArray,
    private val countSupplier: () -> Int,
    private val headSupplier: () -> Int,
    private val truncatedSupplier: () -> Boolean
) {
    fun join(timeoutMillis: Long? = null) {
        try {
            if (timeoutMillis != null) {
                thread.join(timeoutMillis)
            } else {
                thread.join()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun isTruncated(): Boolean = truncatedSupplier()

    fun toByteArray(): ByteArray = synchronized(buffer) {
        val count = countSupplier()
        val head = headSupplier()
        val result = ByteArray(count)
        if (count < buffer.size) {
            System.arraycopy(buffer, 0, result, 0, count)
        } else {
            val part1 = buffer.size - head
            System.arraycopy(buffer, head, result, 0, part1)
            System.arraycopy(buffer, 0, result, part1, head)
        }
        result
    }

    fun readUtf8(): String = String(toByteArray(), Charsets.UTF_8)

    fun readString(charset: Charset = Charsets.UTF_8): String =
        String(toByteArray(), charset)

    override fun toString(): String = readUtf8()
}

/**
 * Utility for reading from an [InputStream] with an upper byte bound on a daemon thread.
 * Protects the host JVM from OutOfMemoryError when reading subprocess stdout/stderr streams
 * that may produce unbounded or high-volume output. Employs a circular ring buffer to retain
 * the most recent [maxBytes] of output (tail-preserving) while continuously discarding older
 * bytes to prevent blocking the subprocess write buffer.
 */
object BoundedStreamDrainer {

    const val DEFAULT_MAX_BYTES: Int = 1024 * 1024 // 1 MB

    fun drain(input: InputStream, maxBytes: Int = DEFAULT_MAX_BYTES): BoundedDrainHandle {
        val ring = ByteArray(maxBytes)
        var head = 0
        var count = 0
        val isTruncated = AtomicBoolean(false)

        val thread = Thread({
            try {
                input.use { stream ->
                    val chunk = ByteArray(8192)
                    var bytesRead: Int
                    while (stream.read(chunk).also { bytesRead = it } != -1) {
                        synchronized(ring) {
                            if (bytesRead >= maxBytes) {
                                System.arraycopy(chunk, bytesRead - maxBytes, ring, 0, maxBytes)
                                head = 0
                                count = maxBytes
                                isTruncated.set(true)
                            } else {
                                for (i in 0 until bytesRead) {
                                    ring[head] = chunk[i]
                                    head = (head + 1) % maxBytes
                                    if (count < maxBytes) {
                                        count++
                                    } else {
                                        isTruncated.set(true)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "BoundedStreamDrainer-Worker").apply {
            isDaemon = true
            start()
        }

        return BoundedDrainHandle(
            thread = thread,
            buffer = ring,
            countSupplier = { synchronized(ring) { count } },
            headSupplier = { synchronized(ring) { if (count < maxBytes) 0 else head } },
            truncatedSupplier = { isTruncated.get() }
        )
    }
}
