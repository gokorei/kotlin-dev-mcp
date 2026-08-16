package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * C0SCWQZV: package API dumper (package_api action).
 */
class PackageApiTest {

    @TempDir
    lateinit var project: Path

    private fun writeKt(relative: String, content: String): Path {
        val file = project.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
        return file
    }

    @Test
    fun `package_api lists public declarations with visibility`() {
        writeKt("src/main/kotlin/com/example/app/Api.kt", """
            package com.example.app

            /**
             * A greeting service.
             */
            class Greeter {
                fun greet(name: String): String = "hi ${'$'}name"
            }

            internal fun internalHelper(): Int = 1

            private val secret: Int = 2

            val publicValue: Int = 3

            interface Speaker {
                fun speak(): String
            }

            fun calculate(): Int = 42
        """)
        writeKt("src/main/kotlin/com/example/app/other/Other.kt", """
            package com.example.other

            class OtherType
        """)

        val service = DefaultProjectService()
        val result = service.packageApi(project.toString(), "com.example.app")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content

        assertTrue(content.contains("Public API Surface"), "expected header in: $content")
        assertTrue(content.contains("class Greeter"), "expected class in: $content")
        assertTrue(content.contains("interface Speaker"), "expected interface in: $content")
        assertTrue(content.contains("fun calculate"), "expected function in: $content")
        assertTrue(content.contains("publicValue"), "expected public property in: $content")
        assertFalse(content.contains("internalHelper"), "internal must be excluded: $content")
        assertFalse(content.contains("secret"), "private must be excluded: $content")
        assertFalse(content.contains("OtherType"), "other package must be excluded: $content")
        assertTrue(content.contains("greeting service"), "expected doc summary in: $content")
    }

    @Test
    fun `package_api resolves inferred return type in semantic mode`() {
        writeKt("src/main/kotlin/com/example/app/Inferred.kt", """
            package com.example.app

            fun calculate(): Int = 42

            fun inferred() = "hello"

            val derived = listOf(1, 2, 3)
        """)

        val service = DefaultProjectService()
        val result = service.packageApi(project.toString(), "com.example.app")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains(": String"), "expected inferred String return type in: ${success.content}")
    }

    @Test
    fun `package_api without package filter lists all packages`() {
        writeKt("A.kt", "package one\nclass A\n")
        writeKt("B.kt", "package two\nclass B\n")

        val service = DefaultProjectService()
        val result = service.packageApi(project.toString(), null)
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("class A") && content.contains("class B"), "expected both packages in: $content")
    }

    @Test
    fun `package_api via execute honors the packageName parameter`() {
        writeKt("src/main/kotlin/com/example/app/Api.kt", """
            package com.example.app
            class Greeter
            fun calculate(): Int = 42
        """)
        writeKt("src/main/kotlin/com/example/other/Other.kt", """
            package com.example.other
            class OtherType
        """)

        val service = DefaultProjectService()
        val result = service.execute(
            action = ProjectAction.PACKAGE_API,
            buildScriptContent = "",
            projectPath = project.toString(),
            packageName = "com.example.app"
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("Greeter"), "expected requested package symbols in: $content")
        assertTrue(content.contains("calculate"), "expected requested package function in: $content")
        assertFalse(content.contains("OtherType"), "packageName must filter to the requested package: $content")
    }

    @Test
    fun `package_api returns error for missing projectPath`() {
        val result = DefaultProjectService().packageApi(null, "com.example")
        assertTrue(result is KotlinMcpResult.Error)
    }

    @Test
    fun `package_api returns error when package not found`() {
        writeKt("A.kt", "package one\nclass A\n")
        val result = DefaultProjectService().packageApi(project.toString(), "com.missing")
        assertTrue(result is KotlinMcpResult.Error)
    }
}
