package com.gokorei.kotlinmcp.server

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Progress notification payload model matching MCP $/progress specification.
 */
data class ProgressNotification(
    val progressToken: String,
    val progress: Double,
    val total: Double? = null,
    val message: String? = null
)

/**
 * Explicit interface for reporting progress notifications during long-running tasks.
 */
interface ProgressNotifier {
    /**
     * Reports an incremental progress update for an active [progressToken].
     */
    fun reportProgress(
        progressToken: String?,
        progress: Double,
        total: Double? = null,
        message: String? = null
    )
}

/**
 * Default thread-safe implementation of [ProgressNotifier].
 */
class DefaultProgressNotifier(
    private val notificationSink: (ProgressNotification) -> Unit = {}
) : ProgressNotifier {

    private val logger = KotlinLogging.logger {}

    override fun reportProgress(
        progressToken: String?,
        progress: Double,
        total: Double?,
        message: String?
    ) {
        if (progressToken.isNullOrBlank()) return
        val notif = ProgressNotification(
            progressToken = progressToken.trim(),
            progress = progress.coerceIn(0.0, total ?: 100.0),
            total = total,
            message = message
        )
        logger.debug { "Progress [token=${notif.progressToken}]: ${notif.progress}/${notif.total ?: 100.0} (${notif.message.orEmpty()})" }
        runCatching { notificationSink(notif) }
    }

    companion object {
        val NOOP: ProgressNotifier = object : ProgressNotifier {
            override fun reportProgress(
                progressToken: String?,
                progress: Double,
                total: Double?,
                message: String?
            ) {}
        }
    }
}
