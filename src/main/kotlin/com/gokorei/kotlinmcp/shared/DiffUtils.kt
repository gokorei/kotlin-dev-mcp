package com.gokorei.kotlinmcp.shared

/**
 * Functional utility for generating LLM-optimized diff patches between original and modified Kotlin code.
 */
object DiffUtils {

    enum class Format {
        TOON,
        UNIFIED
    }

    private sealed interface DiffOp {
        val line: String
        data class Keep(val oldIndex: Int, val newIndex: Int, override val line: String) : DiffOp
        data class Delete(val oldIndex: Int, override val line: String) : DiffOp
        data class Insert(val newIndex: Int, override val line: String) : DiffOp
    }

    fun generateUnifiedDiff(
        original: String,
        modified: String,
        fileName: String = "Snippet.kt",
        contextLines: Int = 2,
        format: Format = Format.TOON
    ): String {
        if (original == modified) {
            return "No changes (original and modified snippets are identical)."
        }

        val oldLines = original.lines()
        val newLines = modified.lines()
        val lcs = computeLcs(oldLines, newLines)
        val ops = generateOps(oldLines, newLines, lcs)

        return when (format) {
            Format.TOON -> renderToonDiff(ops)
            Format.UNIFIED -> renderUnifiedDiff(ops, oldLines, newLines, fileName, contextLines)
        }
    }

    private fun computeLcs(a: List<String>, b: List<String>): List<String> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                if (a[i - 1] == b[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val result = mutableListOf<String>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            if (a[i - 1] == b[j - 1]) {
                result.add(a[i - 1])
                i--
                j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }
        return result.reversed()
    }

    private fun generateOps(a: List<String>, b: List<String>, lcs: List<String>): List<DiffOp> {
        val ops = mutableListOf<DiffOp>()
        var i = 0
        var j = 0
        var k = 0

        while (i < a.size || j < b.size) {
            if (k < lcs.size && i < a.size && j < b.size && a[i] == lcs[k] && b[j] == lcs[k]) {
                ops.add(DiffOp.Keep(i, j, a[i]))
                i++
                j++
                k++
            } else if (i < a.size && (k >= lcs.size || a[i] != lcs[k])) {
                ops.add(DiffOp.Delete(i, a[i]))
                i++
            } else if (j < b.size && (k >= lcs.size || b[j] != lcs[k])) {
                ops.add(DiffOp.Insert(j, b[j]))
                j++
            }
        }
        return ops
    }

    private fun renderToonDiff(ops: List<DiffOp>): String {
        val modifiedOps = ops.filter { it !is DiffOp.Keep }
        return ToonUtils.encodeToonTable(
            headerName = "diff",
            columns = listOf("line", "op", "text"),
            items = modifiedOps
        ) { op ->
            when (op) {
                is DiffOp.Delete -> listOf(op.oldIndex + 1, "-", op.line)
                is DiffOp.Insert -> listOf(op.newIndex + 1, "+", op.line)
                is DiffOp.Keep -> listOf(0, " ", op.line)
            }
        }
    }

    private fun renderUnifiedDiff(
        ops: List<DiffOp>,
        oldLines: List<String>,
        newLines: List<String>,
        fileName: String,
        contextLines: Int
    ): String = buildString {
        appendLine("--- a/$fileName")
        appendLine("+++ b/$fileName")

        var idx = 0
        while (idx < ops.size) {
            if (ops[idx] is DiffOp.Keep) {
                idx++
                continue
            }

            val hunkStart = maxOf(0, idx - contextLines)
            var hunkEnd = idx
            while (hunkEnd < ops.size) {
                if (ops[hunkEnd] !is DiffOp.Keep) {
                    hunkEnd++
                } else {
                    var lookAhead = hunkEnd
                    var nextChanged = false
                    while (lookAhead < ops.size && lookAhead - hunkEnd <= contextLines * 2) {
                        if (ops[lookAhead] !is DiffOp.Keep) {
                            nextChanged = true
                            break
                        }
                        lookAhead++
                    }
                    if (nextChanged) {
                        hunkEnd = lookAhead
                    } else {
                        break
                    }
                }
            }
            val hunkEndWithContext = minOf(ops.size, hunkEnd + contextLines)
            val chunkOps = ops.subList(hunkStart, hunkEndWithContext)

            val oldStart = chunkOps.filterIsInstance<DiffOp.Keep>().firstOrNull()?.oldIndex
                ?: chunkOps.filterIsInstance<DiffOp.Delete>().firstOrNull()?.oldIndex ?: 0
            val oldLength = chunkOps.count { it is DiffOp.Keep || it is DiffOp.Delete }
            val newStart = chunkOps.filterIsInstance<DiffOp.Keep>().firstOrNull()?.newIndex
                ?: chunkOps.filterIsInstance<DiffOp.Insert>().firstOrNull()?.newIndex ?: 0
            val newLength = chunkOps.count { it is DiffOp.Keep || it is DiffOp.Insert }

            val symbolContext = findEnclosingSymbol(oldLines, oldStart)
            val headerSuffix = if (symbolContext.isNotBlank()) " $symbolContext" else ""
            appendLine("@@ -${oldStart + 1},$oldLength +${newStart + 1},$newLength @@$headerSuffix")

            for (op in chunkOps) {
                when (op) {
                    is DiffOp.Keep -> appendLine(" ${op.line}")
                    is DiffOp.Delete -> appendLine("-${op.line}")
                    is DiffOp.Insert -> appendLine("+${op.line}")
                }
            }
            idx = hunkEndWithContext
        }
    }.trimEnd()

    private fun findEnclosingSymbol(lines: List<String>, beforeIndex: Int): String {
        for (i in minOf(beforeIndex, lines.lastIndex) downTo 0) {
            val line = lines[i].trim()
            if (line.startsWith("fun ") || line.startsWith("class ") ||
                line.startsWith("interface ") || line.startsWith("object ") ||
                line.startsWith("sealed ") || line.startsWith("data class")
            ) {
                return line.take(50)
            }
        }
        return ""
    }
}
