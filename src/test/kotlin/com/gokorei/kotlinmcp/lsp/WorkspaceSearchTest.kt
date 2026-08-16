package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.doc.DefaultDocService

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 2TJT9EVP: multi-file workspace symbol search and reference location tracking.
 */
class WorkspaceSearchTest {

    @TempDir
    lateinit var workspace: Path

    private fun writeKt(relative: String, content: String): Path {
        val file = workspace.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
        return file
    }

    private fun sampleWorkspace(): Path {
        writeKt("model/Order.kt", """
            package app.model

            data class Order(val id: Long, val total: Double)

            fun totalWithTax(o: Order, rate: Double): Double = o.total * rate
        """)
        writeKt("service/Checkout.kt", """
            package app.service

            import app.model.Order
            import app.model.totalWithTax

            fun checkout(order: Order, rate: Double): Double = totalWithTax(order, rate)
        """)
        return writeKt("Main.kt", """
            package app

            import app.service.checkout

            fun main() {
                val result = checkout(Order(1, 10.0), 0.2)
                println(result)
            }
        """)
    }

    @Test
    fun `workspace_references resolves cross-file FQN via mandatory K2 semantics`() {
        sampleWorkspace()
        val service = DefaultLspService()

        val result = service.execute(LspAction.WORKSPACE_REFERENCES, "", symbol = "Order", workspacePath = workspace.toString())
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("app.model.Order"), "expected resolved FQN in: ${success.content}")
        assertTrue(success.content.contains("Checkout.kt"), "expected cross-file usage: ${success.content}")
    }

    @Test
    fun `workspace_search returns error when workspacePath missing`() {
        val result = DefaultLspService().execute(LspAction.WORKSPACE_SEARCH, "", symbol = "x", workspacePath = null)
        assertTrue(result is KotlinMcpResult.Error)
    }

    @Test
    fun `workspace_references resolves same-package symbols across files without explicit imports`() {
        writeKt("model/User.kt", """
            package app.model

            data class User(val id: Long, val name: String)
        """)
        writeKt("model/UserService.kt", """
            package app.model

            class UserService {
                fun findUser(): User = User(1, "test")
            }
        """)

        val service = DefaultLspService()
        val result = service.execute(LspAction.WORKSPACE_REFERENCES, "", symbol = "User", workspacePath = workspace.toString())

        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("app.model.User"), "expected resolved FQN in same-package reference: ${success.content}")
        assertTrue(success.content.contains("UserService.kt"), "expected usage in UserService.kt: ${success.content}")
    }

    @Test
    fun `WorkspaceSemanticIndexer reuses cached entries when files are unmodified`() {
        sampleWorkspace()
        val indexer = WorkspaceSemanticIndexer()

        val firstRun = indexer.index(workspace.toString(), maxFiles = 10)
        val secondRun = indexer.index(workspace.toString(), maxFiles = 10)

        assertEquals(firstRun.fileCount, secondRun.fileCount)
        assertEquals(firstRun.occurrences.size, secondRun.occurrences.size)
    }

    @Test
    fun `WorkspaceSemanticIndexer respects defaultMaxFiles override`() {
        sampleWorkspace()
        val indexer = WorkspaceSemanticIndexer(defaultMaxFiles = 2)

        val run = indexer.index(workspace.toString())
        assertTrue(run.truncated, "expected truncated to be true with defaultMaxFiles=2")
        assertEquals(2, run.fileCount)
    }

    @Test
    fun `renameSymbol skips build directories`() {
        val tempDir = java.nio.file.Files.createTempDirectory("kmcp-rename-test")
        try {
            val srcFile = tempDir.resolve("src/Main.kt")
            val buildFile = tempDir.resolve("build/Generated.kt")
            java.nio.file.Files.createDirectories(srcFile.parent)
            java.nio.file.Files.createDirectories(buildFile.parent)

            java.io.File(srcFile.toString()).writeText("val oldName = 10")
            java.io.File(buildFile.toString()).writeText("val oldName = 20")

            val docService = DefaultDocService()
            val lspService = DefaultLspService(docService)

            val result = lspService.execute(
                LspAction.RENAME_SYMBOL,
                code = "",
                symbol = "oldName",
                newName = "newName",
                workspacePath = tempDir.toString()
            )

            assertTrue(result is KotlinMcpResult.Success)
            val srcText = java.io.File(srcFile.toString()).readText()
            val buildText = java.io.File(buildFile.toString()).readText()

            assertTrue(srcText.contains("newName"), "src file should be renamed: $srcText")
            assertTrue(buildText.contains("oldName"), "build file should NOT be renamed: $buildText")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `WorkspaceSemanticIndexer indexes buildSrc and convention plugin kts files`() {
        val tempDir = java.nio.file.Files.createTempDirectory("kmcp-buildsrc-test")
        try {
            val conventionPlugin = tempDir.resolve("buildSrc/src/main/kotlin/my/MyConventionPlugin.kt")
            val scriptFile = tempDir.resolve("buildSrc/my-build.gradle.kts")
            java.nio.file.Files.createDirectories(conventionPlugin.parent)

            java.io.File(conventionPlugin.toString()).writeText("""
                package my
                class MyConventionPlugin
            """.trimIndent())
            java.io.File(scriptFile.toString()).writeText("""
                val conventionVersion = "1.0.0"
            """.trimIndent())

            val indexer = WorkspaceSemanticIndexer()
            val run = indexer.index(tempDir.toString())

            val foundPlugin = run.declarations.any { it.name == "MyConventionPlugin" }
            val foundVersion = run.declarations.any { it.name == "conventionVersion" }

            assertTrue(foundPlugin, "expected MyConventionPlugin in indexed declarations")
            assertTrue(foundVersion, "expected conventionVersion in indexed declarations")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}