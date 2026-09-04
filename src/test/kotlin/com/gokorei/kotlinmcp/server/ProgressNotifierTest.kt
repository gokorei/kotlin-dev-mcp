package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProgressNotifierTest {

    @Test
    fun `reportProgress invokes notification sink with valid coordinates`() {
        val notifications = mutableListOf<ProgressNotification>()
        val notifier = DefaultProgressNotifier { notif ->
            notifications.add(notif)
        }

        val res1 = notifier.reportProgress("token-123", 25.0, 100.0, "Compiling snippet")
        val res2 = notifier.reportProgress("token-123", 100.0, 100.0, "Compilation finished")

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals(2, notifications.size)
        assertEquals("token-123", notifications[0].progressToken)
        assertEquals(25.0, notifications[0].progress)
        assertEquals(100.0, notifications[0].total)
        assertEquals("Compiling snippet", notifications[0].message)

        assertEquals(100.0, notifications[1].progress)
        assertEquals("Compilation finished", notifications[1].message)
    }

    @Test
    fun `reportProgress preserves numeric progress tokens`() {
        val notifications = mutableListOf<ProgressNotification>()
        val notifier = DefaultProgressNotifier { notif ->
            notifications.add(notif)
        }

        val res = notifier.reportProgress(42L, 10.0, 50.0, "Numeric token progress")

        assertTrue(res.isSuccess)
        assertEquals(1, notifications.size)
        assertEquals(42L, notifications[0].progressToken)
        assertEquals(10.0, notifications[0].progress)
        assertEquals(50.0, notifications[0].total)
    }

    @Test
    fun `reportProgress handles null and non-positive totals without errors`() {
        val notifications = mutableListOf<ProgressNotification>()
        val notifier = DefaultProgressNotifier { notif ->
            notifications.add(notif)
        }

        val r1 = notifier.reportProgress("token-unbounded", 150.0, null, "Unbounded")
        val r2 = notifier.reportProgress("token-zero-total", 50.0, 0.0, "Zero total")
        val r3 = notifier.reportProgress("token-negative-total", 50.0, -10.0, "Negative total")

        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        assertTrue(r3.isSuccess)

        assertEquals(3, notifications.size)
        assertEquals(150.0, notifications[0].progress)
        assertNull(notifications[0].total)

        assertEquals(50.0, notifications[1].progress)
        assertNull(notifications[1].total)

        assertEquals(50.0, notifications[2].progress)
        assertNull(notifications[2].total)
    }

    @Test
    fun `reportProgress catches sink exceptions and returns structured error`() {
        val notifier = DefaultProgressNotifier {
            throw IllegalStateException("Sink connection broken")
        }

        val res = notifier.reportProgress("token-error", 50.0, 100.0, "Failing")
        assertFalse(res.isSuccess)
        assertTrue(res is KotlinMcpResult.Error)
        val err = res as KotlinMcpResult.Error
        assertEquals("NOTIFICATION_ERROR", err.code)
        assertTrue(err.message.contains("Sink connection broken"))
    }

    @Test
    fun `reportProgress ignores null or blank progress tokens`() {
        val notifications = mutableListOf<ProgressNotification>()
        val notifier = DefaultProgressNotifier { notif ->
            notifications.add(notif)
        }

        val r1 = notifier.reportProgress(null, 50.0, 100.0, "Ignored")
        val r2 = notifier.reportProgress("", 50.0, 100.0, "Ignored")
        val r3 = notifier.reportProgress("   ", 50.0, 100.0, "Ignored")

        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        assertTrue(r3.isSuccess)
        assertTrue(notifications.isEmpty(), "No notifications should be sent for blank tokens")
    }

    @Test
    fun `NOOP progress notifier safely executes without errors`() {
        assertDoesNotThrow {
            val r1 = DefaultProgressNotifier.NOOP.reportProgress("token-456", 50.0, 100.0, "No-op")
            val r2 = DefaultProgressNotifier.NOOP.reportProgress(null, 0.0, 100.0, null)
            val r3 = DefaultProgressNotifier.NOOP.reportProgress(99, 10.0, -5.0, null)
            assertTrue(r1.isSuccess)
            assertTrue(r2.isSuccess)
            assertTrue(r3.isSuccess)
        }
    }
}
