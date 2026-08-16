package com.gokorei.kotlinmcp.shared

/**
 * Shared functional utilities for line calculations, text snippet extraction,
 * and character balance checks across source files.
 */
object SourceUtils {

    /** 1-indexed line number for the given character [offset] within [text]. */
    fun lineOf(text: String, offset: Int): Int {
        if (text.isEmpty() || offset <= 0) return 1
        val bound = minOf(offset, text.length)
        var count = 1
        for (i in 0 until bound) {
            if (text[i] == '\n') count++
        }
        return count
    }

    /** 1-indexed (line, column) tuple for the given character [offset] within [text]. */
    fun lineAndColumnOf(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty() || offset <= 0) return 1 to 1
        val bound = minOf(offset, text.length)
        var line = 1
        var col = 1
        for (i in 0 until bound) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return line to col
    }

    /** Trimmed text of the line containing [offset] within [text]. */
    fun lineSnippet(text: String, offset: Int): String {
        if (text.isEmpty() || offset < 0) return ""
        val bound = minOf(offset, text.length - 1).coerceAtLeast(0)
        val start = text.lastIndexOf('\n', bound - 1).let { if (it == -1) 0 else it + 1 }
        val end = text.indexOf('\n', bound).let { if (it == -1) text.length else it }
        return text.substring(start, end).trim()
    }

    /**
     * Extracts content between balanced `{` and `}` starting at [openIndex],
     * ignoring braces inside string literals.
     */
    fun extractBalancedBraces(code: String, openIndex: Int): String? {
        if (openIndex !in code.indices || code[openIndex] != '{') return null
        var depth = 0
        var inString = false
        var i = openIndex
        while (i < code.length) {
            val c = code[i]
            when {
                inString -> if (c == '"' && !isEscapedQuote(code, i)) inString = false
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return code.substring(openIndex + 1, i).trim()
                }
            }
            i++
        }
        return null
    }

    /**
     * Checks if parenthetical, brace, and bracket delimiters are syntactically balanced.
     */
    fun isSyntacticallyBalanced(code: String): Boolean {
        var paren = 0; var brace = 0; var bracket = 0
        var inString = false
        for (i in code.indices) {
            val c = code[i]
            when {
                inString -> if (c == '"' && !isEscapedQuote(code, i)) inString = false
                c == '"' -> inString = true
                c == '(' -> paren++
                c == ')' -> paren--
                c == '{' -> brace++
                c == '}' -> brace--
                c == '[' -> bracket++
                c == ']' -> bracket--
            }
            if (paren < 0 || brace < 0 || bracket < 0) return false
        }
        return paren == 0 && brace == 0 && bracket == 0
    }

    private fun isEscapedQuote(text: String, index: Int): Boolean {
        var count = 0
        var j = index - 1
        while (j >= 0 && text[j] == '\\') {
            count++
            j--
        }
        return count % 2 != 0
    }
}
