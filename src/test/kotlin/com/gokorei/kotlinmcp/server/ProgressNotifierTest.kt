package com.gokorei.kotlinmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProgressNotifierTest {

    @Test
    fun `reportProgress invokes notification sink with valid coordinates`() {
        val notifications = mutableListOf<ProgressNotification>()
        val notifier = DefaultProgressNotifier { notif ->
            notifications.add(notif)
        }

        notifier.reportProgress("token-123", 25.0, 100.0, "Compiling snippet")
        notifier.reportProgress("token-123", 100.0, 100.0, "Compilation finished")

        assertEquals(2, notifications.size)
        assertEquals("token-123", notifications[0].progressToken)
        assertEquals(25.0, notifications[0].progress)
        assertEquals(100.0, notifications[0].total)
        assertEquals("Compiling snippet", notifications[0].message)

        assertEquals(100.0, notifications[1].progress)
        assertEquals("Compilation finished", notifications[1].message)
    }

    @Test
    fun `reportProgress ignores null or blank progress tokens`() {
        val notifications = mutableListOf<ProgressNotification>()
        val notifier = DefaultProgressNotifier { notif ->
            notifications.add(notif)
        }

        notifier.reportProgress(null, 50.0, 100.0, "Ignored")
        notifier.reportProgress("", 50.0, 100.0, "Ignored")
        notifier.reportProgress("   ", 50.0, 100.0, "Ignored")

        assertTrue(notifications.isEmpty(), "No notifications should be sent for blank tokens")
    }

    @Test
    fun `NOOP progress notifier safely executes without errors`() {
        assertDoesNotThrow {
            DefaultProgressNotifier.NOOP.reportProgress("token-456", 50.0, 100.0, "No-op")
            DefaultProgressNotifier.NOOP.reportProgress(null, 0.0, 100.0, null)
        }
    }
}
