package com.gokorei.kotlinmcp.execution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SnippetAstSafetyCheckerTest {

    @Test
    fun `detects System exit calls`() {
        val snippet = """
            fun main() {
                System.exit(0)
            }
        """.trimIndent()

        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `detects aliased System import exit calls`() {
        val snippet = """
            import java.lang.System as Sys
            fun main() {
                Sys.exit(0)
            }
        """.trimIndent()

        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `detects direct aliased System exit method import`() {
        val snippet = """
            import java.lang.System.exit as terminateVm
            fun main() {
                terminateVm(0)
            }
        """.trimIndent()

        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `detects kotlin system exitProcess calls`() {
        val snippet = """
            import kotlin.system.exitProcess
            fun main() {
                exitProcess(1)
            }
        """.trimIndent()

        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `detects wildcard kotlin system exitProcess calls`() {
        val snippet = """
            import kotlin.system.*
            fun main() {
                exitProcess(1)
            }
        """.trimIndent()

        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `detects aliased kotlin system exitProcess calls`() {
        val snippet = """
            import kotlin.system.exitProcess as terminateHost
            fun main() {
                terminateHost(1)
            }
        """.trimIndent()

        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `detects Runtime halt calls`() {
        val snippet = """
            fun main() {
                Runtime.getRuntime().halt(1)
            }
        """.trimIndent()

        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `detects aliased Runtime halt calls`() {
        val snippet = """
            import java.lang.Runtime as SysRuntime
            fun main() {
                SysRuntime.getRuntime().halt(1)
            }
        """.trimIndent()

        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `ignores user-defined exit functions`() {
        val snippet = """
            fun exit(code: Int = 0) {
                println("custom exit function with code: ${'$'}code")
            }

            fun main() {
                exit(1)
            }
        """.trimIndent()

        assertFalse(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `ignores user-defined MySystem objects with exit methods`() {
        val snippet = """
            object MySystem {
                fun exit(code: Int) = println("safe custom object")
            }

            fun main() {
                MySystem.exit(0)
            }
        """.trimIndent()

        assertFalse(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `allows safe ordinary code`() {
        val snippet = """
            fun main() {
                val list = listOf(1, 2, 3)
                println(list.map { it * 2 })
            }
        """.trimIndent()

        assertFalse(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }
}
