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
    fun `detects direct aliased getRuntime import calls`() {
        val snippet = """
            import java.lang.Runtime.getRuntime as hostRuntime
            fun main() {
                hostRuntime().halt(1)
            }
        """.trimIndent()

        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet))
    }

    @Test
    fun `detects aliased Runtime even when unrelated local Runtime class is declared`() {
        val snippet = """
            import java.lang.Runtime as SysRuntime
            class Runtime { val value = 1 }

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
    fun `detects variable assigned Runtime halt and exit calls`() {
        val snippet1 = """
            fun main() {
                val r = Runtime.getRuntime()
                r.halt(1)
            }
        """.trimIndent()
        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet1))

        val snippet2 = """
            fun main() {
                val r = java.lang.Runtime.getRuntime()
                r.exit(1)
            }
        """.trimIndent()
        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet2))
    }

    @Test
    fun `detects callable references to exit methods`() {
        val snippet1 = """
            fun main() {
                val fn = System::exit
                fn(0)
            }
        """.trimIndent()
        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet1))

        val snippet2 = """
            import kotlin.system.exitProcess
            fun main() {
                listOf(1).forEach(::exitProcess)
            }
        """.trimIndent()
        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet2))
    }

    @Test
    fun `detects ProcessHandle current destroy calls`() {
        val snippet1 = """
            fun main() {
                ProcessHandle.current().destroy()
            }
        """.trimIndent()
        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet1))

        val snippet2 = """
            fun main() {
                val p = java.lang.ProcessHandle.current()
                p.destroyForcibly()
            }
        """.trimIndent()
        assertTrue(SnippetAstSafetyChecker.containsHostTerminatingCalls(snippet2))
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

