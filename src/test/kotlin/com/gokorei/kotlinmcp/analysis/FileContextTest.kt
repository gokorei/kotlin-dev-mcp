package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 1VJ77TYD: cross-file context summarizer (file_context action).
 */
class FileContextTest {

    @TempDir
    lateinit var workspace: Path

    internal fun writeKt(relative: String, content: String): Path {
        val file = workspace.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
        return file
    }

    internal fun sampleWorkspace(): Path {
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
    fun `file_context reports package imports and outbound usage`() {
        val target = sampleWorkspace()
        val service = DefaultCodeAnalysisService()

        val result = service.execute(CodeAnalysisAction.FILE_CONTEXT, target.toString(), workspacePath = workspace.toString())
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content

        assertTrue(content.contains("File Context"), "expected header in: $content")
        assertTrue(content.contains("Package: app"), "expected package in: $content")
        assertTrue(content.contains("app.service.checkout"), "expected import FQN in: $content")
        assertTrue(content.contains("checkout") || content.contains("Order"), "expected inbound symbol in: $content")
    }

    @Test
    fun `file_context inbound dependency finds symbols declared elsewhere`() {
        val target = writeKt("service/Checkout.kt", """
            package app.service

            import app.model.Order
            import app.model.totalWithTax

            fun checkout(order: Order, rate: Double): Double = totalWithTax(order, rate)
        """)
        writeKt("model/Order.kt", """
            package app.model

            data class Order(val id: Long, val total: Double)

            fun totalWithTax(o: Order, rate: Double): Double = o.total * rate
        """)
        val service = DefaultCodeAnalysisService()

        val result = service.execute(CodeAnalysisAction.FILE_CONTEXT, target.toString(), workspacePath = workspace.toString())
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("Inbound"), "expected inbound section in: $content")
        assertTrue(content.contains("Order") || content.contains("totalWithTax"), "expected inbound symbol in: $content")
    }

    @Test
    fun `file_context semantic mode resolves cross-file edges to FQN`() {
        sampleWorkspace()
        val service = DefaultCodeAnalysisService(fileContextAnalyzer = FileContextAnalyzer(indexer = WorkspaceSemanticIndexer()))

        val result = service.execute(CodeAnalysisAction.FILE_CONTEXT, workspace.resolve("Main.kt").toString(), workspacePath = workspace.toString())
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("app.model.Order") || success.content.contains("app.service.checkout"),
            "expected resolved FQN in: ${success.content}")
    }

    @Test
    fun `file_context returns error for non-kt path`() {
        val result = DefaultCodeAnalysisService().execute(CodeAnalysisAction.FILE_CONTEXT, "not-a-file.txt", workspacePath = workspace.toString())
        assertTrue(result is KotlinMcpResult.Error)
    }
}
