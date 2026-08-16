package com.gokorei.kotlinmcp.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiffUtilsTest {

    @Test
    fun `generateUnifiedDiff uses TOON format by default`() {
        val original = "fun main() {\n    val x = 1\n    println(x)\n}"
        val modified = "fun main() {\n    val x = 2\n    println(x)\n}"

        val diff = DiffUtils.generateUnifiedDiff(original, modified, "Snippet.kt")
        assertTrue(diff.startsWith("[diff: line|op|text]"), "Expected TOON header in: $diff")
        assertTrue(diff.contains("2|-|    val x = 1"), "Expected old line in TOON diff: $diff")
        assertTrue(diff.contains("2|+|    val x = 2"), "Expected new line in TOON diff: $diff")
    }

    @Test
    fun `generateUnifiedDiff produces valid unified diff when requested`() {
        val original = "fun main() {\n    val x = 1\n    println(x)\n}"
        val modified = "fun main() {\n    val x = 2\n    println(x)\n}"

        val diff = DiffUtils.generateUnifiedDiff(
            original, modified, "Snippet.kt",
            format = DiffUtils.Format.UNIFIED
        )
        assertTrue(diff.contains("--- a/Snippet.kt"), "Expected --- header in: $diff")
        assertTrue(diff.contains("+++ b/Snippet.kt"), "Expected +++ header in: $diff")
        assertTrue(diff.contains("-    val x = 1"), "Expected deletion line in: $diff")
        assertTrue(diff.contains("+    val x = 2"), "Expected insertion line in: $diff")
        assertTrue(diff.contains("fun main()"), "Expected enclosing symbol header in: $diff")
    }

    @Test
    fun `generateUnifiedDiff returns empty notice when code is identical`() {
        val code = "val x = 10"
        val diff = DiffUtils.generateUnifiedDiff(code, code, "Snippet.kt")
        assertTrue(diff.contains("No changes"), "Expected No changes notice in: $diff")
    }

    @Test
    fun `LCS algorithm avoids cascading line offsets on line insertions`() {
        val original = "fun compute() {\n    val a = 1\n    val b = 2\n    return a + b\n}"
        val modified = "fun compute() {\n    val a = 1\n    val inserted = 99\n    val b = 2\n    return a + b\n}"

        val diff = DiffUtils.generateUnifiedDiff(original, modified, format = DiffUtils.Format.TOON)
        assertTrue(diff.contains("+|    val inserted = 99"), "Expected insertion of inserted line in: $diff")
        assertFalse(diff.contains("-|    val b = 2"), "val b = 2 should NOT be deleted in LCS diff: $diff")
    }
}
