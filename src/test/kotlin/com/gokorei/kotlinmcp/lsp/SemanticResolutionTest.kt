package com.gokorei.kotlinmcp.lsp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Proves K2 semantics (PSI AST + K2AnalysisSession) are embeddable
 * in-process via K2SnippetFrontend.
 */
class SemanticResolutionTest {

    @Test
    fun `parsePsi produces real KtFile PSI declarations from a snippet`() {
        val code = """
            package spike

            @JvmInline
            value class UserId(val raw: Long)

            data class User(val id: Long, val name: String)

            fun greet(u: User): String = "hi ${'$'}{u.name}"
        """.trimIndent()

        val psi = K2SnippetFrontend.parsePsi(code)
        assertTrue(psi != null, "expected KtFile PSI to be produced from snippet")
        val text = psi!!.text
        assertTrue(text.contains("data class User"), "PSI should contain the class: $text")
        assertTrue(text.contains("value class UserId"), "PSI should contain the value class: $text")

        val classNames = psi.declarations.mapNotNull { it.name }
        assertTrue(classNames.contains("User"), "expected User declaration, got: $classNames")
        assertTrue(classNames.contains("UserId"), "expected UserId declaration, got: $classNames")
    }

    @Test
    fun `analyzeSession returns file containing top-level declaration`() {
        val code = """
            package spike

            data class User(val id: Long, val name: String)

            fun greet(u: User): String = "hi ${'$'}{u.name}"
        """.trimIndent()

        val session = K2SnippetFrontend.analyzeSession(code)
        assertTrue(session != null, "expected a K2AnalysisSession from K2 analysis")

        val greet = session!!.file.declarations.firstOrNull { it.name == "greet" }
        assertTrue(greet != null, "expected greet() declaration in PSI")
        assertEquals("greet", greet?.name)
    }

    @Test
    fun `K2 PSI extracts declaration FQN and nullability information`() {
        val code = """
            package spike

            data class Profile(val id: Long, val email: String?)
        """.trimIndent()

        val session = K2SnippetFrontend.analyzeSession(code)
        assertTrue(session != null, "expected a K2AnalysisSession from K2 analysis")

        val profile = session!!.file.declarations.firstOrNull { it.name == "Profile" } as? org.jetbrains.kotlin.psi.KtClass
        assertTrue(profile != null, "expected Profile declaration")
        assertEquals("spike.Profile", profile?.fqName?.asString())
        
        val emailParam = profile!!.primaryConstructorParameters.firstOrNull { it.name == "email" }
        assertTrue(emailParam != null, "expected email parameter")
        assertTrue(emailParam!!.typeReference?.text?.endsWith("?") == true, "expected email type to be nullable")
    }

    @Test
    fun `concurrent analyzeSession calls do not corrupt each other`() {
        val snippets = listOf(
            "package a\nfun alpha(): String = \"a\"",
            "package b\ndata class Beta(val x: Int)",
            "package c\nclass Gamma { fun y(): Boolean = true }",
            "package d\nval delta: Double = 1.5"
        )

        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, String?>>())
        val threads = snippets.indices.map { i ->
            Thread {
                val session = K2SnippetFrontend.analyzeSession(snippets[i])
                val resolved = session?.let { s ->
                    s.file.declarations.firstOrNull { it.name != null }?.name
                }
                results.add(i to resolved)
            }.also { it.start() }
        }
        threads.forEach { it.join(30_000) }

        // Each thread must resolve its OWN snippet's declaration, not a cross-thread one.
        snippets.indices.forEach { i ->
            val pair = results.first { it.first == i }
            assertTrue(pair.second != null, "snippet $i must resolve a declaration")
        }
        // Ensure no cross-contamination: names must be distinct per thread (alpha/Beta/Gamma/delta).
        val names = results.map { it.second }.filterNotNull()
        assertEquals(snippets.size, names.toSet().size, "each concurrent session must resolve its own declaration, got: $names")
    }

    @Test
    fun `WorkspaceSemanticIndexer uses BindingContext to resolve symbol occurrences`() {
        val tempDir = java.nio.file.Files.createTempDirectory("kmcp-semantic-index").toFile()
        try {
            val fileA = java.io.File(tempDir, "User.kt").apply {
                writeText("""
                    package com.example.models
                    class User(val name: String)
                """.trimIndent())
            }
            val fileB = java.io.File(tempDir, "Service.kt").apply {
                writeText("""
                    package com.example.services
                    import com.example.models.User
                    class UserService {
                        fun process(user: User): String = user.name
                    }
                """.trimIndent())
            }

            val indexer = WorkspaceSemanticIndexer()
            val index = indexer.index(listOf(fileA, fileB), tempDir.absolutePath)

            val references = index.occurrences.filter { it.kind == OccurrenceKind.REFERENCE }
            val userRef = references.firstOrNull { it.name == "User" && it.file == "Service.kt" }
            assertNotNull(userRef, "expected User reference in Service.kt")
            assertEquals("com.example.models.User", userRef?.fqn, "expected resolved FQN for User reference")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

