package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.execution.bootstrap.BOOTSTRAP_ERROR_MARKER
import com.gokorei.kotlinmcp.execution.bootstrap.BOOTSTRAP_SETUP_EXIT_CODE
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis

@Suppress("LargeClass")
class FastSnippetRunnerTest {
    @Test
    fun `executes compiled snippet in-memory and captures standard output`() {
        val code =
            """
            fun main() {
                println("in-memory-fast-execution")
            }
            """.trimIndent()

        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val res = (compiled as CompileResult.Compiled)

        DefaultFastSnippetRunner().use { runner ->
            val result = runner.run(res.outDir, timeoutMillis = 5_000L)
            SnippetCompiler.cleanup(res)

            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("in-memory-fast-execution"))
            assertEquals("in_memory", success.metadata["mode"])
            assertEquals("0", success.metadata["exitCode"])
        }
    }

    @Test
    fun `captures runtime exceptions with formatted error details`() {
        val code =
            """
            fun main() {
                error("Deliberate fast runner test error")
            }
            """.trimIndent()

        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val res = (compiled as CompileResult.Compiled)

        DefaultFastSnippetRunner().use { runner ->
            val result = runner.run(res.outDir, timeoutMillis = 5_000L)
            SnippetCompiler.cleanup(res)

            assertTrue(result.isError)
            val err = result as KotlinMcpResult.Error
            assertEquals("RUNTIME_ERROR", err.code)
            assertTrue(err.message.contains("Deliberate fast runner test error"))
        }
    }

    @Test
    fun `host runner command passes generated main class to readiness bootstrap`() {
        val outDir = Files.createTempDirectory("host-runner-main-selection")
        val bootstrapDir = Files.createTempDirectory("host-runner-bootstrap-selection")
        try {
            Files.writeString(outDir.resolve("CustomObj.class"), "")
            Files.writeString(outDir.resolve("SnippetKt.class"), "")
            val readinessToken = "test-readiness-token"

            val command = buildCompiledCommand(outDir, bootstrapDir, emptyList(), File("java"), readinessToken)

            assertEquals(HOST_EXECUTION_BOOTSTRAP_CLASS, command[3])
            assertEquals(listOf(SnippetCompiler.MAIN_CLASS, readinessToken), command.drop(4))
            assertTrue(command[2].contains(outDir.toString()), "snippet output dir must be on the child classpath")
            assertTrue(command[2].contains(bootstrapDir.toString()), "bootstrap dir must be on the child classpath")
        } finally {
            outDir.toFile().deleteRecursively()
            bootstrapDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `host runner command never selects the bootstrap class for a packaged snippet`() {
        val outDir = Files.createTempDirectory("host-runner-packaged-selection")
        val bootstrapDir = Files.createTempDirectory("host-runner-packaged-bootstrap")
        try {
            val packaged = outDir.resolve("app")
            Files.createDirectories(packaged)
            Files.writeString(packaged.resolve("SnippetKt.class"), "")
            Files.writeString(packaged.resolve("Cart.class"), "")
            writeBootstrapClassStub(outDir)

            val command = buildCompiledCommand(outDir, bootstrapDir, emptyList(), File("java"), "token")

            assertEquals("app.SnippetKt", command[4])
        } finally {
            outDir.toFile().deleteRecursively()
            bootstrapDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `host runner command ignores a bootstrap class left in the snippet output directory`() {
        val outDir = Files.createTempDirectory("host-runner-bootstrap-only")
        val bootstrapDir = Files.createTempDirectory("host-runner-bootstrap-only-child")
        try {
            writeBootstrapClassStub(outDir)

            val command = buildCompiledCommand(outDir, bootstrapDir, emptyList(), File("java"), "token")

            assertEquals(SnippetCompiler.MAIN_CLASS, command[4])
        } finally {
            outDir.toFile().deleteRecursively()
            bootstrapDir.toFile().deleteRecursively()
        }
    }

    private fun writeBootstrapClassStub(outDir: java.nio.file.Path) {
        val bootstrapDir = outDir.resolve(HOST_EXECUTION_BOOTSTRAP_CLASS.replace('.', '/').substringBeforeLast('/'))
        Files.createDirectories(bootstrapDir)
        Files.writeString(bootstrapDir.resolve("${HOST_EXECUTION_BOOTSTRAP_CLASS.substringAfterLast('.')}.class"), "")
    }

    @Test
    fun `host runner executes a packaged snippet`() {
        val code =
            """
            package app

            fun main() {
                println("packaged-execution")
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 5_000L)
                }

            assertTrue(executionResult.isSuccess, "expected success, got: ${executionResult.toFormattedText()}")
            val success = executionResult as KotlinMcpResult.Success
            assertTrue(success.content.contains("packaged-execution"), success.content)
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner executes snippet after readiness handshake`() {
        val code =
            """
            fun main() {
                println("host-ready-execution")
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 1_000L)
                }

            assertTrue(executionResult.isSuccess, "expected success, got: ${executionResult.toFormattedText()}")
            val success = executionResult as KotlinMcpResult.Success
            assertTrue(success.content.contains("host-ready-execution"))
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner times out finite snippet after readiness`() {
        val code =
            """
            fun main() {
                Thread.sleep(750)
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 200L)
                }

            assertTrue(executionResult.isError)
            val error = executionResult as KotlinMcpResult.Error
            assertEquals("EXECUTION_TIMEOUT", error.code)
            assertEquals("execution", error.details["phase"])
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `enforces execution timeout when snippet runs infinitely`() {
        val code =
            """
            fun main() {
                while (true) {
                    Thread.sleep(50)
                }
            }
            """.trimIndent()

        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val res = (compiled as CompileResult.Compiled)

        DefaultFastSnippetRunner().use { runner ->
            val result = runner.run(res.outDir, timeoutMillis = 500L)
            SnippetCompiler.cleanup(res)

            assertTrue(result.isError)
            val err = result as KotlinMcpResult.Error
            assertEquals("EXECUTION_TIMEOUT", err.code)
        }
    }

    @Test
    fun `host runner cleans descendant processes after snippet exits`() {
        val code =
            """
            fun main() {
                val child = ProcessBuilder("sleep", "37").start()
                println("DESCENDANT_PID=${'$'}{child.pid()}")
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 5_000L)
                }

            assertTrue(executionResult.isSuccess, "expected success, got: ${executionResult.toFormattedText()}")
            val success = executionResult as KotlinMcpResult.Success
            val pid =
                success.content
                    .lineSequence()
                    .first { it.startsWith("DESCENDANT_PID=") }
                    .substringAfter('=')
                    .toLong()
            val child = ProcessHandle.of(pid).orElse(null)
            assertTrue(child == null || !child.isAlive)
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner preserves an existing interrupt status`() {
        val compiled = SnippetCompiler.compile("fun main() { Thread.sleep(1_000) }")
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            Thread.currentThread().interrupt()
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 5_000L)
                }

            assertTrue(executionResult.isError)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner preserves an interrupt received during execution`() {
        val marker = Files.createTempFile("host-runner-interrupt", ".ready")
        val markerPath = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val code =
            """
            fun main() {
                java.nio.file.Files.writeString(java.nio.file.Path.of("$markerPath"), "ready")
                Thread.sleep(5_000)
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled
        val interrupted = arrayOfNulls<Boolean>(1)
        val executionThread =
            Thread({
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 10_000L)
                }
                interrupted[0] = Thread.currentThread().isInterrupted
            }, "host-runner-interrupt-test")

        try {
            executionThread.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!Files.exists(marker) && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(Files.exists(marker), "snippet did not reach execution")
            executionThread.interrupt()
            executionThread.join(5_000)
            assertTrue(!executionThread.isAlive)
            assertEquals(true, interrupted[0])
        } finally {
            executionThread.interrupt()
            executionThread.join(5_000)
            Files.deleteIfExists(marker)
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner includes target class initialization in execution timeout`() {
        val code =
            """
            val initializationDelay = run {
                Thread.sleep(750)
                true
            }
            fun main() {
                println("initialized=${'$'}initializationDelay")
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 200L)
                }

            assertTrue(executionResult.isError)
            val error = executionResult as KotlinMcpResult.Error
            assertEquals("EXECUTION_TIMEOUT", error.code)
            assertEquals("execution", error.details["phase"])
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner can execute the same compiled output repeatedly`() {
        val compiled = SnippetCompiler.compile("fun main() { println(\"repeated-bootstrap\") }")
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            HostJvmCompiledSnippetRunner().use { runner ->
                repeat(2) {
                    val executionResult = runner.run(result.outDir, timeoutMillis = 1_000L)
                    assertTrue(executionResult.isSuccess, "expected success, got: ${executionResult.toFormattedText()}")
                }
            }
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner reports isolated classpath failures as bootstrap errors`() {
        val code =
            """
            class BrokenStaticInit {
                init {
                    throw NoClassDefFoundError("com/example/Absent")
                }
            }

            fun main() {
                BrokenStaticInit()
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 5_000L)
                }

            assertTrue(executionResult.isError, "expected failure, got: ${executionResult.toFormattedText()}")
            val error = executionResult as KotlinMcpResult.Error
            assertEquals(BOOTSTRAP_ERROR_CODE, error.code)
            assertEquals("bootstrap", error.details["phase"])
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner still reports genuine snippet failures as runtime errors`() {
        val code =
            """
            fun main() {
                check(1 == 2) { "genuine-assertion-failure" }
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 5_000L)
                }

            assertTrue(executionResult.isError, "expected failure, got: ${executionResult.toFormattedText()}")
            val error = executionResult as KotlinMcpResult.Error
            assertEquals("RUNTIME_ERROR", error.code)
            assertEquals("execution", error.details["phase"])
            assertTrue(error.message.contains("genuine-assertion-failure"), error.message)
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `bootstrap setup failure still cleans descendants via the shutdown hook`() {
        val pidFile = Files.createTempFile("host-runner-setup-descendant", ".pid")
        Files.deleteIfExists(pidFile)
        val pidPath = pidFile.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val code =
            """
            fun main() {
                val child = ProcessBuilder("sleep", "43").start()
                java.nio.file.Files.writeString(java.nio.file.Path.of("$pidPath"), child.pid().toString())
                Thread.sleep(400)
                linkageFailure()
            }

            private fun linkageFailure(): Nothing = throw NoClassDefFoundError("com/example/Absent")
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 10_000L)
                }

            assertTrue(executionResult.isError, "expected failure, got: ${executionResult.toFormattedText()}")
            val error = executionResult as KotlinMcpResult.Error
            assertEquals(BOOTSTRAP_ERROR_CODE, error.code)
            assertEquals("bootstrap", error.details["phase"])
            assertEquals("2", error.details["exitCode"])
            assertTrue(Files.exists(pidFile), "snippet did not record the descendant pid")
            val pid = Files.readString(pidFile).toLong()
            val grandchild = ProcessHandle.of(pid).orElse(null)
            assertTrue(grandchild == null || !grandchild.isAlive, "descendant $pid survived the setup failure")
        } finally {
            Files.deleteIfExists(pidFile)
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `bootstrap child cleans its own descendants on setup failure`() {
        val pidFile = Files.createTempFile("host-runner-hook-descendant", ".pid")
        Files.deleteIfExists(pidFile)
        val pidPath = pidFile.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val code =
            """
            fun main() {
                val child = ProcessBuilder("sleep", "43").start()
                java.nio.file.Files.writeString(java.nio.file.Path.of("$pidPath"), child.pid().toString())
                Thread.sleep(300)
                linkageFailure()
            }

            private fun linkageFailure(): Nothing = throw NoClassDefFoundError("com/example/Absent")
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled
        val stagingRoot = Files.createTempDirectory("host-runner-hook-staging")
        val secret =
            java.util.UUID
                .randomUUID()
                .toString()

        try {
            val javaExecutable = DefaultJavaResolver().resolve(null)
            assertNotNull(javaExecutable, "no Java executable available for the bootstrap hook check")
            val bootstrapDir = Files.createTempDirectory(stagingRoot, "kmcp-bootstrap")
            val resource =
                HostJvmCompiledSnippetRunner::class.java.classLoader
                    .getResourceAsStream(HOST_EXECUTION_BOOTSTRAP_RESOURCE)
            assertNotNull(resource)
            resource!!.use { stageHostExecutionBootstrap(bootstrapDir, it) }
            val command =
                buildCompiledCommand(result.outDir, bootstrapDir, emptyList(), javaExecutable!!, "hook-token")
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.use { it.write("$secret\nstart\n".toByteArray(Charsets.UTF_8)) }
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "bootstrap JVM did not exit")
            assertEquals(BOOTSTRAP_SETUP_EXIT_CODE, process.exitValue(), "output:\n$output")
            assertTrue(Files.exists(pidFile), "snippet did not record the descendant pid")
            val pid = Files.readString(pidFile).toLong()
            val grandchild = ProcessHandle.of(pid).orElse(null)
            assertTrue(
                grandchild == null || !grandchild.isAlive,
                "descendant $pid survived; the child JVM must clean up via its shutdown hook",
            )
        } finally {
            stagingRoot.toFile().deleteRecursively()
            Files.deleteIfExists(pidFile)
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `target that exits with the bootstrap exit code is not classified as a bootstrap error`() {
        val code =
            """
            fun main() {
                println("target-controlled-exit")
                kotlin.system.exitProcess(2)
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 10_000L)
                }

            assertTrue(executionResult.isError, "expected failure, got: ${executionResult.toFormattedText()}")
            val error = executionResult as KotlinMcpResult.Error
            assertEquals("RUNTIME_ERROR", error.code)
            assertEquals("execution", error.details["phase"])
            assertEquals("2", error.details["exitCode"])
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `target that prints the bootstrap marker is not classified as a bootstrap error`() {
        val code =
            """
            fun main() {
                println("kmcp-bootstrap-error")
                println(":" + "kmcp-bootstrap-error")
                error("target-assertion-failure")
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 10_000L)
                }

            assertTrue(executionResult.isError, "expected failure, got: ${executionResult.toFormattedText()}")
            val error = executionResult as KotlinMcpResult.Error
            assertEquals("RUNTIME_ERROR", error.code)
            assertEquals("execution", error.details["phase"])
            assertTrue(error.message.contains("kmcp-bootstrap-error"), error.message)
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `isolated bootstrap staging contains only the bootstrap class file`() {
        val stagingRoot = Files.createTempDirectory("host-runner-bootstrap-staging")
        try {
            val staged = Files.createTempDirectory(stagingRoot, "kmcp-bootstrap")
            val resource =
                HostJvmCompiledSnippetRunner::class.java.classLoader
                    .getResourceAsStream(HOST_EXECUTION_BOOTSTRAP_RESOURCE)
            assertNotNull(resource, "bootstrap resource $HOST_EXECUTION_BOOTSTRAP_RESOURCE must exist on the classpath")
            resource!!.use { stageHostExecutionBootstrap(staged, it) }

            val stagedFiles =
                staged
                    .toFile()
                    .walkTopDown()
                    .filter { it.isFile }
                    .toList()
            assertEquals(
                1,
                stagedFiles.size,
                "bootstrap staging must hold exactly one file, found: ${stagedFiles.map { it.name }}",
            )
            val expectedRelative = HOST_EXECUTION_BOOTSTRAP_CLASS.replace('.', '/') + ".class"
            val stagedRelative =
                stagedFiles
                    .single()
                    .relativeTo(staged.toFile())
                    .invariantSeparatorsPath
            assertEquals(expectedRelative, stagedRelative)
        } finally {
            stagingRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `staged bootstrap class runs with no other server class on the child classpath`() {
        val stagingRoot = Files.createTempDirectory("host-runner-bootstrap-isolated")
        val javaExecutable = DefaultJavaResolver().resolve(null)
        try {
            assertNotNull(javaExecutable, "no Java executable available for the isolated bootstrap check")
            val staged = Files.createTempDirectory(stagingRoot, "kmcp-bootstrap")
            val resource =
                HostJvmCompiledSnippetRunner::class.java.classLoader
                    .getResourceAsStream(HOST_EXECUTION_BOOTSTRAP_RESOURCE)
            assertNotNull(resource)
            resource!!.use { stageHostExecutionBootstrap(staged, it) }

            val childClasspath =
                (listOf(staged.toString()) + isolatedMutationExecutionClasspath)
                    .joinToString(File.pathSeparator)
            val process =
                ProcessBuilder(
                    javaExecutable!!.absolutePath,
                    "-cp",
                    childClasspath,
                    HOST_EXECUTION_BOOTSTRAP_CLASS,
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "bootstrap JVM did not exit")
            val exit = process.exitValue()
            assertFalse(
                output.contains("NoClassDefFoundError"),
                "staged bootstrap needs additional server classes, output:\n$output",
            )
            assertEquals(BOOTSTRAP_SETUP_EXIT_CODE, exit, "bootstrap exited abnormally, output:\n$output")
            assertTrue(output.contains(BOOTSTRAP_ERROR_MARKER), "expected setup-failure marker, output:\n$output")
        } finally {
            stagingRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `host runner cleans descendants promptly on the execution timeout path`() {
        val pidFile = Files.createTempFile("host-runner-timeout-descendant", ".pid")
        Files.deleteIfExists(pidFile)
        val pidPath = pidFile.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val code =
            """
            fun main() {
                val child = ProcessBuilder("sleep", "41").start()
                java.nio.file.Files.writeString(java.nio.file.Path.of("$pidPath"), child.pid().toString())
                while (true) {
                    Thread.sleep(50)
                }
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val elapsedMs =
                measureTimeMillis {
                    val executionResult =
                        HostJvmCompiledSnippetRunner().use { runner ->
                            runner.run(result.outDir, timeoutMillis = 1_000L)
                        }
                    assertTrue(executionResult.isError)
                    val error = executionResult as KotlinMcpResult.Error
                    assertEquals("EXECUTION_TIMEOUT", error.code)
                    assertEquals("execution", error.details["phase"])
                }
            assertTrue(Files.exists(pidFile), "snippet did not record the descendant pid")
            val pid = Files.readString(pidFile).toLong()
            val grandchild = ProcessHandle.of(pid).orElse(null)
            assertTrue(grandchild == null || !grandchild.isAlive, "descendant $pid survived the timeout")
            assertTrue(elapsedMs < 10_000L, "cleanup must not wait per poll sample, took ${elapsedMs}ms")
        } finally {
            ProcessHandle
                .of(Files.readString(pidFile).toLong())
                .ifPresent { runCatching { it.destroyForcibly() } }
            Files.deleteIfExists(pidFile)
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner cleans descendants when the snippet halts its own JVM`() {
        val pidFile = Files.createTempFile("host-runner-halt", ".pid")
        Files.deleteIfExists(pidFile)
        val pidPath = pidFile.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val code =
            """
            fun main() {
                val child = ProcessBuilder("sleep", "37").start()
                java.nio.file.Files.writeString(java.nio.file.Path.of("$pidPath"), child.pid().toString())
                Thread.sleep(500)
                Runtime.getRuntime().halt(0)
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 10_000L)
                }

            assertTrue(
                executionResult.isSuccess,
                "halt(0) should exit cleanly, got: ${executionResult.toFormattedText()}",
            )
            assertTrue(Files.exists(pidFile), "snippet did not record the descendant pid")
            val pid = Files.readString(pidFile).toLong()
            val grandchild = ProcessHandle.of(pid).orElse(null)
            assertTrue(grandchild == null || !grandchild.isAlive, "descendant $pid survived the halted JVM")
        } finally {
            Files.deleteIfExists(pidFile)
            SnippetCompiler.cleanup(result)
        }
    }
}
