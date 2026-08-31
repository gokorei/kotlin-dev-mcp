package com.gokorei.kotlinmcp.server

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Progress notification payload model matching MCP $/progress specification.
 *
 * @property progressToken Identifier of the progress token (String or numeric token).
 * @property progress Numeric progress value reported.
 * @property total Total expected progress, if known and positive.
 * @property message Optional human-readable progress message.
 */
data class ProgressNotification(
    val progressToken: Any,
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
     *
     * @param progressToken Active MCP progress token (String, Long, Int, etc.).
     * @param progress Current progress value.
     * @param total Total expected progress, if known and positive.
     * @param message Optional human-readable status message.
     */
    fun reportProgress(
        progressToken: Any?,
        progress: Double,
        total: Double? = null,
        message: String? = null
    )
}

/**
 * Default thread-safe implementation of [ProgressNotifier].
 *
 * @property notificationSink Callback invoked with each constructed [ProgressNotification].
 */
class DefaultProgressNotifier(
    private val notificationSink: (ProgressNotification) -> Unit = {}
) : ProgressNotifier {

    private val logger = KotlinLogging.logger {}

    /**
     * Reports an incremental progress update to the [notificationSink].
     *
     * @param progressToken Active MCP progress token.
     * @param progress Current progress value.
     * @param total Total expected progress, if known and positive.
     * @param message Optional human-readable status message.
     */
    override fun reportProgress(
        progressToken: Any?,
        progress: Double,
        total: Double?,
        message: String?
    ) {
        if (progressToken == null) return
        val tokenString = progressToken.toString()
        if (tokenString.isBlank()) return

        val hasValidTotal = total != null && total > 0.0
        val clampedProgress = if (hasValidTotal) {
            progress.coerceIn(0.0, total!!)
        } else {
            progress.coerceAtLeast(0.0)
        }

        val notif = ProgressNotification(
            progressToken = progressToken,
            progress = clampedProgress,
            total = if (hasValidTotal) total else null,
            message = message
        )
        logger.debug { "Progress [token=$progressToken]: ${notif.progress}/${notif.total ?: "unbounded"} (${notif.message.orEmpty()})" }
        runCatching { notificationSink(notif) }
    }

    companion object {
        /**
         * No-op singleton implementation of [ProgressNotifier] that discards all reported updates.
         */
        val NOOP: ProgressNotifier = object : ProgressNotifier {
            override fun reportProgress(
                progressToken: Any?,
                progress: Double,
                total: Double?,
                message: String?
            ) {}
        }
    }
}
