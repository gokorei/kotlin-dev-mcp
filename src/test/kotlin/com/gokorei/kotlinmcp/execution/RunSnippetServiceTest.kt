package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RunSnippetServiceTest {

    private val service: RunSnippetService = DefaultRunSnippetService()

    @Test
    fun `run_snippet executes a valid main and returns its output`() {
        val code = """
            fun main() {
                println("hello-from-snippet")
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L)

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("hello-from-snippet"), "expected output, got: ${success.content}")
    }

    @Test
    fun `run_snippet wraps expressions without main function and executes them`() {
        val code = """
            val x = 40
            val y = 2
            println("result=${'$'}{x + y}")
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L)

        assertTrue(result.isSuccess, "expected success for scratchpad expression, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("result=42"), "expected evaluated output result=42, got: ${success.content}")
    }

    @Test
    fun `run_snippet preserves imports when wrapping in synthetic main`() {
        val code = """
            import java.time.Instant
            val now = Instant.EPOCH
            println("epoch=${'$'}now")
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L)

        assertTrue(result.isSuccess, "expected success with imports, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("epoch=1970-01-01T00:00:00Z"), "expected epoch output, got: ${success.content}")
    }

    @Test
    fun `run_snippet surfaces runtime errors with exit code and requireAnotherCall`() {
        val code = """
            fun main() {
                val a = 0
                val b = 1 / a
                println(b)
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L)

        assertTrue(result.isError, "expected runtime error, got: ${result.toFormattedText()}")
        val text = result.toFormattedText()
        assertTrue(text.contains("RUNTIME_ERROR"), "expected RUNTIME_ERROR, got: $text")
        assertTrue(text.contains("requireAnotherCall"), "expected loop hint, got: $text")
    }

    @Test
    fun `run_snippet times out on an infinite loop`() {
        val code = """
            fun main() {
                while (true) {}
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 2_000L)

        assertTrue(result.isError, "expected timeout, got: ${result.toFormattedText()}")
        assertTrue(result.toFormattedText().contains("EXECUTION_TIMEOUT"), "got: ${result.toFormattedText()}")
    }

    @Test
    fun `parseTestReport reads JUnit XML files and returns structured failure summary`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-junit")
        val reportDir = dir.resolve("build/test-results/test")
        java.nio.file.Files.createDirectories(reportDir)
        reportDir.resolve("TEST-SampleTest.xml").toFile().writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="SampleTest" tests="2" failures="1">
              <testcase name="testPass" classname="SampleTest" time="0.01"/>
              <testcase name="testFail" classname="SampleTest" time="0.02">
                <failure message="expected true">AssertionFailedError</failure>
              </testcase>
            </testsuite>
        """.trimIndent())

        try {
            val result = service.parseTestReport(dir.toString())
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("JUnit Test Execution Report"))
            assertTrue(success.content.contains("SampleTest > testFail"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run_snippet with projectPath compiles and runs a workspace project type`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-workspace-run")
        try {
            val lib = SnippetCompiler.compile("""
                package demo
                class Greeter(val name: String) { fun greet(): String = "hi, ${'$'}name" }
            """.trimIndent())
            val libOut = (lib as CompileResult.Compiled).outDir

            val classesDir = workspace.resolve("build/classes/kotlin/main")
            java.nio.file.Files.createDirectories(classesDir)
            libOut.toFile().walkTopDown().forEach { f ->
                if (f.isFile) {
                    val dest = classesDir.resolve(libOut.relativize(f.toPath()).toString())
                    java.nio.file.Files.createDirectories(dest.parent)
                    java.nio.file.Files.copy(f.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }

            val code = """
                import demo.Greeter
                fun main() { println(Greeter("kota").greet()) }
            """.trimIndent()

            val result = service.execute(code, timeoutMillis = 30_000L, runner = "host_jvm", projectPath = workspace.toString())

            assertTrue(result.isSuccess, "expected success from host_jvm, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("hi, kota"), "expected workspace type output, got: ${success.content}")

            SnippetCompiler.cleanup(lib)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run_snippet with host_jvm runner executes code in isolated subprocess`() {
        val code = """
            fun main() {
                println("hello-from-host-jvm")
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L, runner = "host_jvm")

        assertTrue(result.isSuccess, "expected success from host_jvm, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("hello-from-host-jvm"), "expected host_jvm output, got: ${success.content}")
        assertEquals("host_jvm", success.metadata["mode"])
    }

    @Test
    fun `run_snippet with host_jvm runner captures output larger than the pipe buffer in full`() {
        // Emit enough output to overflow the OS pipe buffer so the process and the
        // reader thread must drain concurrently; the captured result (truncated to
        // its tail) must still contain the final marker line, proving the reader
        // was drained to completion rather than reporting partial output as complete.
        val code = """
            fun main() {
                val chunk = "A".repeat(1_000_000) + "\n"
                repeat(16) { print(chunk) }
                print("END-OF-OUTPUT")
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L, runner = "host_jvm")

        assertTrue(result.isSuccess, "expected success from host_jvm, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(
            success.content.contains("END-OF-OUTPUT"),
            "output tail must be fully captured, got: ${success.content.takeLast(200)}"
        )
    }

    @Test
    fun `run_snippet with invalid javaPath returns actionable error asking LLM to provide javaPath`() {
        val code = """
            fun main() {
                println("should-not-run")
            }
        """.trimIndent()

        val result = service.execute(
            code = code,
            timeoutMillis = 30_000L,
            runner = "host_jvm",
            javaPath = "/non/existent/path/to/java_invalid_bin"
        )

        assertTrue(result.isError, "expected error for missing java executable")
        val errorText = result.toFormattedText()
        assertTrue(errorText.contains("MISSING_JAVA_HOME") || errorText.contains("No Java installation detected"), "got: $errorText")
        assertTrue(errorText.contains("javaPath"), "expected actionable message asking for javaPath, got: $errorText")
    }

    @Test
    fun `host_jvm runner does not expose server internals to the snippet`() {
        val code = """
            fun main() {
                try {
                    Class.forName("com.gokorei.kotlinmcp.server.KotlinMcpServer")
                    println("LEAKED")
                } catch (e: ClassNotFoundException) {
                    println("ISOLATED")
                }
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L, runner = "host_jvm")
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("ISOLATED"), "server class must not be on the host_jvm classpath: ${success.content}")
        assertFalse(success.content.contains("LEAKED"), "server internals must not be reachable: ${success.content}")
    }

    @Test
    fun `embedded runner isolates the server classloader from the snippet`() {
        val code = """
            fun main() {
                try {
                    Class.forName("com.gokorei.kotlinmcp.server.KotlinMcpServer")
                    println("LEAKED")
                } catch (e: ClassNotFoundException) {
                    println("ISOLATED")
                }
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L, runner = "embedded")
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("ISOLATED"), "embedded runner must not expose server internals: ${success.content}")
        assertFalse(success.content.contains("LEAKED"), "embedded runner must isolate server internals: ${success.content}")
    }

    @Test
    fun `embedded runner times out a CPU-bound infinite loop`() {
        val code = """
            fun main() {
                while (true) {}
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 1_500L, runner = "embedded")
        assertTrue(result.isError, "expected timeout, got: ${result.toFormattedText()}")
        assertTrue(result.toFormattedText().contains("EXECUTION_TIMEOUT"), "got: ${result.toFormattedText()}")
    }

    @Test
    fun `parseTestReport distinguishes failure error and skipped and does not false-positive on name substrings`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-junit2")
        val reportDir = dir.resolve("build/test-results/test")
        java.nio.file.Files.createDirectories(reportDir)
        // testFailLike is a PASSING test whose name CONTAINS the failing test's name.
        reportDir.resolve("TEST-SampleTest.xml").toFile().writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="SampleTest" tests="5" failures="1" errors="1" skipped="1">
              <testcase name="testPass" classname="SampleTest" time="0.01"/>
              <testcase name="testFailLike" classname="SampleTest" time="0.01"/>
              <testcase name="testFail" classname="SampleTest" time="0.02">
                <failure message="expected true">AssertionFailedError: expected true but was false
            at SampleTest.testFail(SampleTest.kt:10)</failure>
              </testcase>
              <testcase name="testErr" classname="SampleTest" time="0.02">
                <error message="boom">java.lang.IllegalStateException: boom</error>
              </testcase>
              <testcase name="testSkip" classname="SampleTest" time="0.0">
                <skipped/>
              </testcase>
            </testsuite>
        """.trimIndent())

        try {
            val result = service.parseTestReport(dir.toString())
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertEquals("5", success.metadata["total"])
            assertEquals("1", success.metadata["failures"])
            assertEquals("1", success.metadata["errors"])
            assertEquals("1", success.metadata["skipped"])
            assertTrue(success.content.contains("testFail") && success.content.contains("FAILED"), "failure must be reported: ${success.content}")
            assertTrue(success.content.contains("testErr") && success.content.contains("ERROR"), "error must be reported separately: ${success.content}")
            assertTrue(success.content.contains("testSkip") && success.content.contains("SKIPPED"), "skipped must be reported: ${success.content}")
            assertFalse(success.content.contains("testPass"), "passing test must not be reported as failed: ${success.content}")
            assertFalse(success.content.contains("testFailLike"), "passing test whose name contains failing name must not be flagged: ${success.content}")
            assertTrue(success.content.contains("SampleTest.kt:10"), "failure stack-trace body must be captured: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `main function detection is PSI-based and does not fire inside comments`() {
        val code = """
            // fun main(args: Array<String>) { println("nope") }
            class NoMain {
                fun main() { println("member, not top-level") }
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L)
        assertTrue(result.isSuccess, "expected code to be wrapped in synthetic main and run successfully, got: ${result.toFormattedText()}")
    }

    @Test
    fun `main function detection accepts suspend main and multi-line declarations`() {
        val suspendMain = """
            suspend fun main() {
                println("suspend-main")
            }
        """.trimIndent()
        val suspendResult = service.execute(suspendMain, timeoutMillis = 30_000L)
        assertFalse(suspendResult.toFormattedText().contains("NO_MAIN_FOUND"), "suspend main must be accepted: ${suspendResult.toFormattedText()}")

        val multiLine = """
            fun main(
                args: Array<String>
            ) {
                println("multi-line-main")
            }
        """.trimIndent()
        val multiResult = service.execute(multiLine, timeoutMillis = 30_000L)
        assertFalse(multiResult.toFormattedText().contains("NO_MAIN_FOUND"), "multi-line main must be accepted: ${multiResult.toFormattedText()}")
    }

    @Test
    fun `main function detection accepts JvmStatic main`() {
        val code = """
            object Launcher {
                @JvmStatic
                fun main(args: Array<String>) {
                    println("jvmstatic-main")
                }
            }
        """.trimIndent()
        val result = service.execute(code, timeoutMillis = 30_000L)
        assertFalse(result.toFormattedText().contains("NO_MAIN_FOUND"), "@JvmStatic main in an object must be accepted: ${result.toFormattedText()}")
    }

    @Test
    fun `run_snippet rejects dangerous jvmArgs like agentlib or javaagent`() {
        val code = """
            fun main() {
                println("should-not-run")
            }
        """.trimIndent()

        val dangerousArgs = listOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005", "-javaagent:/path/to/agent.jar")
        for (arg in dangerousArgs) {
            val result = service.execute(code, timeoutMillis = 30_000L, runner = "host_jvm", jvmArgs = listOf(arg))
            assertTrue(result.isError, "expected error for dangerous jvmArg: $arg")
            val text = result.toFormattedText()
            assertTrue(text.contains("UNSAFE_JVM_ARGUMENT") || text.contains("Rejected unsafe JVM argument"), "expected UNSAFE_JVM_ARGUMENT error, got: $text")
        }
    }

    @Test
    fun `run_snippet rejects dangerous jvmArgs including add-opens add-exports and allow-attach-self`() {
        val code = "fun main() { println(\"should-not-run\") }"
        val dangerousArgs = listOf(
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005",
            "-javaagent:/path/to/agent.jar",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-exports=java.base/sun.security.x509=ALL-UNNAMED",
            "--allow-attach-self"
        )
        for (arg in dangerousArgs) {
            val result = service.execute(code, timeoutMillis = 30_000L, runner = "host_jvm", jvmArgs = listOf(arg))
            assertTrue(result.isError, "expected error for dangerous jvmArg: $arg")
            val text = result.toFormattedText()
            assertTrue(text.contains("UNSAFE_JVM_ARGUMENT") || text.contains("Rejected unsafe JVM argument"), "expected UNSAFE_JVM_ARGUMENT error, got: $text")
        }
    }

    @Test
    fun `javaResolver falls back to java home or environment when passed path is null`() {
        val resolver: JavaResolver = DefaultJavaResolver()
        val javaBin = resolver.resolve(null)
        assertNotNull(javaBin, "expected java executable to resolve on current system")
        assertTrue(javaBin!!.exists())
        assertTrue(javaBin.isFile)
    }

    @Test
    fun `parseTestReport finds XML reports in multi-module subproject build test-results directories`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-junit-multimodule")
        val appReportDir = dir.resolve("app/build/test-results/testDebugUnitTest")
        val coreReportDir = dir.resolve("core/domain/build/test-results/test")
        java.nio.file.Files.createDirectories(appReportDir)
        java.nio.file.Files.createDirectories(coreReportDir)

        appReportDir.resolve("TEST-MainActivityTest.xml").toFile().writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="MainActivityTest" tests="1" failures="0">
              <testcase name="testLaunch" classname="MainActivityTest" time="0.01"/>
            </testsuite>
        """.trimIndent())

        coreReportDir.resolve("TEST-UserUseCaseTest.xml").toFile().writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="UserUseCaseTest" tests="1" failures="1">
              <testcase name="testExecute" classname="UserUseCaseTest" time="0.02">
                <failure message="bad error">IllegalStateException</failure>
              </testcase>
            </testsuite>
        """.trimIndent())

        try {
            val result = service.parseTestReport(dir.toString())
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertEquals("2", success.metadata["total"], "expected 2 tests across submodules")
            assertEquals("1", success.metadata["failures"], "expected 1 failure in core/domain")
            assertTrue(success.content.contains("UserUseCaseTest > testExecute"), "core failure must be reported: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run_snippet executes snippet with package declaration`() {
        val code = """
            package com.example.demo
            
            fun main() {
                println("hello-from-package")
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L)

        assertTrue(result.isSuccess, "expected success for packaged snippet, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("hello-from-package"), "expected packaged output, got: ${success.content}")
    }

    @Test
    fun `run_snippet executes snippet with custom file JvmName`() {
        val code = """
            @file:JvmName("CustomLauncher")
            package com.example.launcher

            fun main() {
                println("custom-jvmname-executed")
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L)

        assertTrue(result.isSuccess, "expected success for custom JvmName snippet, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("custom-jvmname-executed"), "expected custom JvmName output, got: ${success.content}")
    }

    @Test
    fun `run_snippet executes snippet with JvmStatic object main`() {
        val code = """
            package com.example.app

            object AppRunner {
                @JvmStatic
                fun main(args: Array<String>) {
                    println("object-main-executed")
                }
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L)

        assertTrue(result.isSuccess, "expected success for object main snippet, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("object-main-executed"), "expected object main output, got: ${success.content}")
    }

    @Test
    fun `run_snippet redirects in_process execution to host_jvm when terminating calls are detected`() {
        val code = """
            fun main() {
                kotlin.system.exitProcess(0)
            }
        """.trimIndent()

        val result = service.execute(code, timeoutMillis = 30_000L, runner = "in_process")

        assertTrue(result.isSuccess, "expected safe host JVM execution, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertEquals("host_jvm", success.metadata["mode"], "expected redirection to host_jvm for terminating snippet")
    }
}





