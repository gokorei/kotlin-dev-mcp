package com.gokorei.kotlinmcp.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedStreamDrainerTest {

    @Test
    fun `drain captures small stream completely without truncation`() {
        val content = "Hello, bounded stream drainer!"
        val input = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        val handle = BoundedStreamDrainer.drain(input, maxBytes = 1024)
        handle.join(1000)

        assertFalse(handle.isTruncated())
        assertEquals(content, handle.readString(Charsets.UTF_8))
        assertEquals(content, handle.readUtf8())
    }

    @Test
    fun `drain bounds stream to maxBytes and reports truncation without crashing`() {
        val largeChunk = "A".repeat(10_000)
        val totalBytes = 100 * 10_000
        val infiniteLikeStream = object : InputStream() {
            var emitted = 0
            override fun read(): Int {
                return if (emitted++ < totalBytes) 'A'.code else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (emitted >= totalBytes) return -1
                val toEmit = minOf(len, totalBytes - emitted)
                java.util.Arrays.fill(b, off, off + toEmit, 'A'.code.toByte())
                emitted += toEmit
                return toEmit
            }
        }

        val maxBytes = 32 * 1024 // 32 KB limit
        val handle = BoundedStreamDrainer.drain(infiniteLikeStream, maxBytes = maxBytes)
        handle.join(3000)

        assertTrue(handle.isTruncated(), "expected stream to be truncated")
        assertEquals(maxBytes, handle.toByteArray().size, "retained bytes must equal maxBytes cap")
    }

    @Test
    fun `drain handles empty input cleanly`() {
        val input = ByteArrayInputStream(ByteArray(0))
        val handle = BoundedStreamDrainer.drain(input, maxBytes = 512)
        handle.join(500)

        assertFalse(handle.isTruncated())
        assertEquals("", handle.readUtf8())
        assertEquals(0, handle.toByteArray().size)
    }
}
