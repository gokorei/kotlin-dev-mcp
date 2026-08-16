package com.gokorei.kotlinmcp.shared

/**
 * Pure utility for tailing and truncating large log outputs to protect LLM context windows.
 */
object LogTruncator {

    const val DEFAULT_MAX_LINES = 100
    const val DEFAULT_MAX_BYTES = 8192

    fun truncate(
        text: String,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): String {
        if (text.isBlank()) return text

        val lines = text.lines()
        var effectiveLines = lines

        var lineTruncatedHeader: String? = null
        if (lines.size > maxLines) {
            val omittedCount = lines.size - maxLines
            effectiveLines = lines.takeLast(maxLines)
            lineTruncatedHeader = "[... truncated $omittedCount preceding lines ...]"
        }

        var resultText = effectiveLines.joinToString("\n")
        if (lineTruncatedHeader != null) {
            resultText = "$lineTruncatedHeader\n$resultText"
        }

        val bytes = resultText.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) {
            val trimmedBytes = bytes.copyOfRange(bytes.size - maxBytes, bytes.size)
            val byteTruncatedText = String(trimmedBytes, Charsets.UTF_8).substringAfter("\n", String(trimmedBytes, Charsets.UTF_8))
            return "[... truncated output to last ${maxBytes} bytes ...]\n$byteTruncatedText"
        }

        return resultText
    }
}
