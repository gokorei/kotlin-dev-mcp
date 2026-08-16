package com.gokorei.kotlinmcp.shared

/**
 * Pure utility for encoding list payloads and tabular objects into TOON (Token-Optimized Object Notation).
 */
object ToonUtils {

    fun <T> encodeToonTable(
        headerName: String,
        columns: List<String>,
        items: List<T>,
        rowExtractor: (T) -> List<Any?>
    ): String = buildString {
        appendLine("[$headerName: ${columns.joinToString("|")}]")
        items.forEach { item ->
            appendLine(rowExtractor(item).joinToString("|") { (it?.toString() ?: "").replace("|", "/") })
        }
    }.trimEnd()
}
