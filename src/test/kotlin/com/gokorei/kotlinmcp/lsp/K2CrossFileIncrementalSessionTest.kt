package com.gokorei.kotlinmcp.lsp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

@Tag("stress")
@Tag("hardening")
class K2CrossFileIncrementalSessionTest {

    @Test
    fun `cross-file type resolution updates dynamically when dependent file changes signature`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("ServiceA.kt").toFile()
        val fileB = tempDir.resolve("ConsumerB.kt").toFile()

        // Version 1: ServiceA returns Int
        fileA.writeText("""
            package com.example.stress
            class ServiceA {
                fun computeValue(): Int = 42
            }
        """.trimIndent())

        // ConsumerB calls computeValue()
        fileB.writeText("""
            package com.example.stress
            class ConsumerB(private val service: ServiceA) {
                fun process() = service.computeValue()
            }
        """.trimIndent())

        val psiA1 = K2SnippetFrontend.parsePsi(fileA.readText())
        val psiB1 = K2SnippetFrontend.parsePsi(fileB.readText())
        assertNotNull(psiA1)
        assertNotNull(psiB1)

        val session1 = K2SnippetFrontend.analyzeSession(psiB1!!.text, listOf(psiA1!!))
        assertNotNull(session1)
        assertNotNull(session1?.bindingContext)

        val consumerClass1 = session1!!.file.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtClass>().first()
        val processFn1 = consumerClass1.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>().first()
        val bodyExpr1 = processFn1.bodyExpression
        assertNotNull(bodyExpr1)

        val type1 = session1.bindingContext.getType(bodyExpr1!!)
        assertEquals("Int", type1?.constructor?.declarationDescriptor?.name?.asString())

        // Version 2: Mutate ServiceA to return String
        fileA.writeText("""
            package com.example.stress
            class ServiceA {
                fun computeValue(): String = "uuid-computed-value"
            }
        """.trimIndent())

        val psiA2 = K2SnippetFrontend.parsePsi(fileA.readText())
        assertNotNull(psiA2)

        val session2 = K2SnippetFrontend.analyzeSession(psiB1.text, listOf(psiA2!!))
        assertNotNull(session2)

        val consumerClass2 = session2!!.file.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtClass>().first()
        val processFn2 = consumerClass2.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>().first()
        val bodyExpr2 = processFn2.bodyExpression
        assertNotNull(bodyExpr2)

        val type2 = session2.bindingContext.getType(bodyExpr2!!)
        assertEquals("String", type2?.constructor?.declarationDescriptor?.name?.asString(),
            "Incremental session must resolve updated return type String across files")
    }

    @Test
    fun `shadowed local declarations resolve correctly without descriptor pollution across consecutive sessions`() {
        val snippet1 = """
            package com.example.shadow
            val count = 100
            fun test() {
                val count = "local-shadow"
                val length = count.length
            }
        """.trimIndent()

        val session1 = K2SnippetFrontend.analyzeSession(snippet1)
        assertNotNull(session1)

        val testFn = session1!!.file.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>().first()
        val body = testFn.bodyBlockExpression
        val lengthProp = body?.statements?.filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>()?.first { it.name == "length" }
        assertNotNull(lengthProp?.initializer)

        val lengthType = session1.bindingContext.getType(lengthProp!!.initializer!!)
        assertEquals("Int", lengthType?.constructor?.declarationDescriptor?.name?.asString())
    }
}
