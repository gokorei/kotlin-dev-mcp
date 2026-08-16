package com.gokorei.kotlinmcp.models

import kotlinx.serialization.Serializable

/**
 * Errors-as-values domain result model for MCP tool responses.
 * Follows the MCP design principle: return structured error results rather than throwing unhandled exceptions.
 */
@Serializable
sealed class KotlinMcpResult {

    abstract val isSuccess: Boolean
    val isError: Boolean get() = !isSuccess

    abstract fun toFormattedText(): String

    @Serializable
    data class Success(
        val content: String,
        val metadata: Map<String, String> = emptyMap(),
        val requireAnotherCall: Boolean = false
    ) : KotlinMcpResult() {
        override val isSuccess: Boolean = true

        override fun toFormattedText(): String {
            val text = if (metadata.isEmpty()) {
                content
            } else {
                val metaString = metadata.entries.joinToString(separator = ", ") { "${it.key}: ${it.value}" }
                "$content\n\n--- Metadata ---\n$metaString"
            }
            return if (requireAnotherCall) {
                "$text\n\nrequireAnotherCall: true — apply the diagnostics above and re-run this tool until it reports no issues."
            } else {
                text
            }
        }
    }

    @Serializable
    data class Error(
        val message: String,
        val code: String = "GENERIC_ERROR",
        val details: Map<String, String> = emptyMap(),
        val requireAnotherCall: Boolean = false
    ) : KotlinMcpResult() {
        override val isSuccess: Boolean = false

        override fun toFormattedText(): String {
            val builder = StringBuilder()
            builder.appendLine("Error [$code]: $message")
            if (details.isNotEmpty()) {
                builder.appendLine("Details:")
                details.forEach { (k, v) ->
                    builder.appendLine(" - $k: $v")
                }
            }
            if (requireAnotherCall) {
                builder.appendLine("requireAnotherCall: true — apply the diagnostics above and re-run this tool until it reports no issues.")
            }
            return builder.toString().trimEnd()
        }
    }
}
