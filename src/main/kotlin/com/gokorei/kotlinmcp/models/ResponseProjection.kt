package com.gokorei.kotlinmcp.models

/**
 * Output preset for controlling token verbosity in MCP tool responses.
 */
enum class ResponsePreset {
    FULL,
    COMPACT,
    SUMMARY;

    companion object {
        fun fromString(value: String?): ResponsePreset = when (value?.trim()?.lowercase()) {
            "compact" -> COMPACT
            "summary" -> SUMMARY
            else -> FULL
        }
    }
}

/**
 * Projection configuration specifying output presets and selective field filters
 * to conserve context-window tokens.
 */
data class ResponseProjection(
    val preset: ResponsePreset = ResponsePreset.FULL,
    val fields: Set<String> = emptySet()
)

/**
 * Utility for applying token-optimization projections to [KotlinMcpResult].
 */
object ProjectionFilter {

    private val verboseMetadataKeys = setOf(
        "raw", "rawAst", "internalAstOffset", "debug", "verboseDebugInfo", "astDump"
    )

    fun apply(result: KotlinMcpResult, projection: ResponseProjection): KotlinMcpResult {
        if (projection.preset == ResponsePreset.FULL && projection.fields.isEmpty()) {
            return result
        }

        return when (result) {
            is KotlinMcpResult.Success -> {
                val filteredMetadata = filterMap(result.metadata, projection)
                val content = if (projection.preset == ResponsePreset.COMPACT) {
                    compactContent(result.content)
                } else {
                    result.content
                }
                result.copy(content = content, metadata = filteredMetadata)
            }
            is KotlinMcpResult.Error -> {
                val filteredDetails = filterMap(result.details, projection)
                result.copy(details = filteredDetails)
            }
        }
    }

    private fun filterMap(map: Map<String, String>, projection: ResponseProjection): Map<String, String> {
        if (map.isEmpty()) return map

        var filtered = map
        if (projection.fields.isNotEmpty()) {
            filtered = filtered.filterKeys { it in projection.fields }
        }
        if (projection.preset == ResponsePreset.COMPACT) {
            filtered = filtered.filterKeys { it !in verboseMetadataKeys }
        }
        return filtered
    }

    private fun compactContent(content: String): String {
        // Strip lines that belong to debug/AST dumps between separators or until blank line boundary
        val lines = content.lines()
        val compacted = mutableListOf<String>()
        var skipping = false

        for (line in lines) {
            if (line.contains("--- Internal AST Dump ---") || line.contains("--- Debug Trace ---")) {
                skipping = true
                continue
            }
            if (skipping && (line.startsWith("--- ") || line.isBlank())) {
                skipping = false
                if (line.isBlank()) continue
            }
            if (!skipping) {
                compacted.add(line)
            }
        }
        return compacted.joinToString("\n").trim()
    }
}
