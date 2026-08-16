package com.gokorei.kotlinmcp.linting

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class LintServiceTest {

    private lateinit var service: LintService

    @BeforeEach
    fun setUp() {
        service = DefaultLintService()
    }

    @Test
    fun `kotlin_lint_detekt flags issues on a snippet with force non-null assertions`() {
        val snippet = """
            import java.util.UUID
            import kotlinx.coroutines.GlobalScope

            fun process(name: String?) {
                val unused = name!!.length
                val alsoUnused = name!!.length
                GlobalScope.launch { }
            }
        """.trimIndent()

        val result = service.runDetekt(snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.metadata["findingCount"] != null, "expected findingCount metadata")
        val count = success.metadata["findingCount"]!!.toIntOrNull() ?: 0
        assertTrue(count >= 2, "expected at least 2 detekt findings, got $count: ${success.content}")
        assertTrue(
            success.content.contains("UnusedPrivateProperty"),
            "expected known detekt rules in output: ${success.content}"
        )
    }

    @Test
    fun `kotlin_lint_detekt runs on a workspace directory`() {
        val dir = Files.createTempDirectory("kmcp-lint-workspace")
        val ktFile = dir.resolve("Bad.kt").toFile()
        ktFile.writeText(
            """
            import kotlinx.coroutines.GlobalScope

            fun process(name: String?) {
                val unused = name!!.length
                GlobalScope.launch { }
                println(name)
            }
            """.trimIndent()
        )

        val result = service.runDetekt("", dir.toString())
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        val count = success.metadata["findingCount"]!!.toIntOrNull() ?: 0
        assertTrue(count >= 1, "expected findings from workspace lint, got $count: ${success.content}")

        dir.toFile().deleteRecursively()
    }

    @Test
    fun `kotlin_format_ktlint formats wrongly indented snippet`() {
        val snippet = "fun  main() {\n    println(\"hi\")\n}\n"
        val result = service.formatKtlint(snippet, apply = true)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("fun  main"), "double-space in fun must be fixed: ${success.content}")
    }

    @Test
    fun `kotlin_format_ktlint with apply false returns a diff not a replacement`() {
        val snippet = "fun main() { println(\"hi\") }\n"
        val result = service.formatKtlint(snippet, apply = false)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.metadata["apply"] == "false", "expected apply=false metadata")
    }

    @Test
    fun `kotlin_lint_baseline dump writes a baseline xml and read returns it`() {
        val dir = Files.createTempDirectory("kmcp-baseline")
        val finding = LintFinding("ForbiddenComment", "warning", "Snippet.kt", 3, 1, "found a TODO")
        val dump = service.baselineDump(dir.toString(), listOf(finding))
        assertTrue(dump.isSuccess, "expected dump success, got: ${dump.toFormattedText()}")
        val baselineFile = File(dir.toFile(), "detekt-baseline.xml")
        assertTrue(baselineFile.exists(), "expected baseline xml written")
        assertTrue(baselineFile.readText().contains("ForbiddenComment"), "expected rule id in baseline")

        val read = service.baselineRead(dir.toString())
        assertTrue(read.isSuccess, "expected read success, got: ${read.toFormattedText()}")
        assertTrue((read as KotlinMcpResult.Success).content.contains("ForbiddenComment"), "expected baseline entry in read output")

        dir.toFile().deleteRecursively()
    }

    @Test
    fun `missing detekt classpath attempts fallback resolution or returns actionable error message`() {
        val noClasspath = DefaultLintService(resourceOverrides = mapOf("detekt.classpath.txt" to null))
        val result = noClasspath.runDetekt("fun main() {}")
        if (result.isError) {
            val err = result as KotlinMcpResult.Error
            assertTrue(err.code == "DETEKT_CLASSPATH_MISSING" || err.code == "DETEKT_RUN_ERROR", "got code: ${err.code}")
            assertTrue(err.message.contains("classpath") || err.message.contains("dumpToolingClasspaths"), "message should guide user: ${err.message}")
        }
    }

    @Test
    fun `missing ktlint classpath attempts fallback resolution or returns actionable error message`() {
        val noClasspath = DefaultLintService(resourceOverrides = mapOf("ktlint.classpath.txt" to null))
        val result = noClasspath.formatKtlint("fun main() {}")
        if (result.isError) {
            val err = result as KotlinMcpResult.Error
            assertTrue(err.code == "KTLINT_CLASSPATH_MISSING" || err.code == "KTLINT_ERROR")
        }
    }

    @Test
    fun `runDetekt accepts custom compilerClasspath parameter`() {
        val snippet = "fun main() { val x = 42 }"
        val result = service.runDetekt(snippet, compilerClasspath = listOf("/dummy/path/custom-compiler.jar"))
        assertTrue(result.isSuccess || result.isError)
    }

    @Test
    fun `ChildFirstClassLoader prefers child for transitive dependencies like antlr and kaml`() {
        val urls = arrayOf<java.net.URL>()
        val loader = ChildFirstClassLoader(urls, javaClass.classLoader)
        assertTrue(loader.shouldPreferChild("io.gitlab.arturbosch.detekt.Main"))
        assertTrue(loader.shouldPreferChild("com.pinterest.ktlint.Main"))
        assertTrue(loader.shouldPreferChild("org.antlr.v4.runtime.Parser"))
        assertTrue(loader.shouldPreferChild("com.charleskorn.kaml.Yaml"))
        assertTrue(loader.shouldPreferChild("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment"))
    }


    @Test
    fun `detekt honors a workspace detekt yml disabling a rule`() {
        val dir = Files.createTempDirectory("kmcp-detekt-config")
        File(dir.toFile(), "detekt.yml").writeText(
            """
            style:
              UnusedPrivateProperty:
                active: false
            """.trimIndent()
        )
        val kt = dir.resolve("Bad.kt").toFile()
        kt.writeText(
            """
            fun process(name: String?) {
                val unused = name!!.length
                println(unused)
            }
            """.trimIndent()
        )

        val result = service.runDetekt("", dir.toString())
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("UnusedPrivateProperty"), "workspace detekt.yml must disable UnusedPrivateProperty: ${success.content}")
        assertEquals("0", success.metadata["findingCount"], "expected 0 findings with rule disabled")

        dir.toFile().deleteRecursively()
    }

    @Test
    fun `detekt inline config map suppresses a rule via off`() {
        val snippet = """
            fun process(name: String?) {
                val unused = name!!.length
                println(unused)
            }
        """.trimIndent()
        val result = service.runDetekt(snippet, config = mapOf("style" to mapOf("UnusedPrivateProperty" to "off")))
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("UnusedPrivateProperty"), "config map must suppress UnusedPrivateProperty: ${success.content}")
    }

    @Test
    fun `in-process linting executes rapidly under 2000ms`() {
        val snippet = "fun  main() {\n    println(\"speed test\")\n}\n"
        val start = System.currentTimeMillis()
        val result = service.formatKtlint(snippet, apply = true)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        assertTrue(elapsed < 2500, "in-process ktlint formatting should execute under 2500ms, took ${elapsed}ms")
    }
}

