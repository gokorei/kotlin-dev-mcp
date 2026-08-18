package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.doc.DefaultDocService

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LspServiceTest {

    private lateinit var service: LspService

    @BeforeEach
    fun setUp() {
        service = DefaultLspService()
    }

    @Test
    fun `findDefinition locates symbol line in code snippet`() {
        val snippet = """
            package com.example
            
            data class Movie(val id: Int, val title: String)
            
            fun processMovie(movie: Movie) {
                println(movie.title)
            }
        """.trimIndent()

        val result = service.execute(LspAction.FIND_DEFINITION, snippet, symbol = "Movie")
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Line 3"))
        assertTrue(success.content.contains("data class Movie"))
    }

    @Test
    fun `findDefinition locates typealias and sealed interface declarations`() {
        val snippet = """
            package com.example
            
            typealias UserId = String
            sealed interface Outcome
        """.trimIndent()

        val resultAlias = service.execute(LspAction.FIND_DEFINITION, snippet, symbol = "UserId")
        assertTrue((resultAlias as KotlinMcpResult.Success).content.contains("Line 3"))

        val resultSealed = service.execute(LspAction.FIND_DEFINITION, snippet, symbol = "Outcome")
        assertTrue((resultSealed as KotlinMcpResult.Success).content.contains("Line 4"))
    }

    @Test
    fun `findDefinition resolves stdlib type info when not declared in snippet`() {
        val snippet = "val items: List<String> = emptyList()"

        val result = service.execute(LspAction.FIND_DEFINITION, snippet, symbol = "List")
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("List"), "expected List in content: ${success.content}")
    }

    @Test
    fun `findDefinition resolves a symbol declared in another workspace file to that file and line`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-def")
        try {
            workspace.resolve("model/User.kt").toFile().apply {
                parentFile.mkdirs()
                writeText("package com.example.model\nclass User(val name: String)\n")
            }

            val snippet = """
                package com.example.app
                import com.example.model.User
                fun main() { val u = User("x"); println(u.name) }
            """.trimIndent()

            val result = service.execute(
                LspAction.FIND_DEFINITION, snippet,
                symbol = "User", workspacePath = workspace.toString()
            )
            assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("model/User.kt"), "expected workspace file in: ${success.content}")
            assertTrue(success.content.contains(":2"), "expected line 2 in: ${success.content}")
            assertTrue(success.content.contains("class User"), "expected declaration signature in: ${success.content}")
            assertEquals("true", success.metadata["found"])
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `findDefinition resolves snippet-local declarations through the semantic engine`() {
        val snippet = """
            package spike
            class Engine
            fun start() { Engine() }
        """.trimIndent()

        val result = service.execute(LspAction.FIND_DEFINITION, snippet, symbol = "Engine")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Line 2"), "expected snippet line in: ${success.content}")
        assertTrue(success.content.contains("class Engine"), "expected declaration in: ${success.content}")
        assertEquals("true", success.metadata["found"])
    }

    @Test
    fun `findDefinition falls back to stdlib docs for stdlib symbols resolved as external`() {
        val snippet = "fun main() { val items: List<Int> = emptyList() }"

        val result = service.execute(LspAction.FIND_DEFINITION, snippet, symbol = "List")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("List"), "expected stdlib fallback in: ${success.content}")
        assertTrue(success.content.contains("Standard Library"), "expected stdlib marker in: ${success.content}")
    }

    @Test
    fun `findReferences counts occurrences in code snippet`() {
        val snippet = """
            fun calculate(value: Int): Int {
                val valueCopy = value
                return value + valueCopy
            }
        """.trimIndent()

        val result = service.execute(LspAction.FIND_REFERENCES, snippet, symbol = "value")
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Snippet: Line 1"))
        assertTrue(success.content.contains("Snippet: Line 3"))
    }

    @Test
    fun `getCompletions suggests matching symbols`() {
        val snippet = """
            fun processOrder() {}
            fun processPayment() {}
            val itemPrice = 100
        """.trimIndent()

        val result = service.execute(LspAction.GET_COMPLETIONS, snippet, symbol = "process")
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("processOrder"))
        assertTrue(success.content.contains("processPayment"))
    }

    @Test
    fun `renameSymbol updates occurrences in code snippet`() {
        val snippet = """
            fun calculateTotal(price: Int): Int {
                val total = price * 2
                return total
            }
        """.trimIndent()

        val result = service.execute(LspAction.RENAME_SYMBOL, snippet, symbol = "total", newName = "grandTotal")
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("grandTotal"))
        assertFalse(success.content.contains("val total ="))
    }

    @Test
    fun `findDefinition returns error on blank symbol`() {
        val result = service.execute(LspAction.FIND_DEFINITION, "code", symbol = "")
        assertTrue(result is KotlinMcpResult.Error)
    }

    @Test
    fun `findDefinition consults DocService for stdlib symbols beyond the old 7-entry table`() {
        val serviceWithDocs = DefaultLspService(DefaultDocService())
        val result = serviceWithDocs.execute(LspAction.FIND_DEFINITION, "val x = listOf(1)", symbol = "mapNotNull")
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("mapNotNull"), "expected DocService-backed lookup in: ${success.content}")
    }

    @Test
    fun `renameSymbol writes changes to workspace files under workspacePath`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-rename")
        val fileA = workspace.resolve("A.kt").toFile()
        val fileB = workspace.resolve("B.kt").toFile()
        fileA.writeText("class Widget { fun render() = 1 }\n")
        fileB.writeText("val w = Widget()\n")
        val ignored = workspace.resolve("notes.txt").toFile()
        ignored.writeText("Widget is a concept\n")

        val result = service.execute(
            LspAction.RENAME_SYMBOL,
            code = "fun useWidget(w: Widget) = w.render()",
            symbol = "Widget",
            newName = "Gadget",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Gadget"), "expected new name in: ${success.content}")

        assertTrue(fileA.readText().contains("class Gadget"), "workspace file A must be rewritten: ${fileA.readText()}")
        assertTrue(fileB.readText().contains("val w = Gadget()"), "workspace file B must be rewritten: ${fileB.readText()}")
        assertTrue(ignored.readText().contains("Widget"), "non-kotlin files must be left untouched")

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `renameSymbol does not mutate comments or string literals in snippets`() {
        val snippet = """
            package com.example

            // The total calculated for a user
            fun calculateTotal(price: Int): Int {
                val total = price * 2
                val label = "Your total amount is ${'$'}total"
                return total
            }
        """.trimIndent()

        val result = service.execute(LspAction.RENAME_SYMBOL, snippet, symbol = "total", newName = "grandTotal")
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        val content = success.content

        // Comment should remain intact with 'total'
        assertTrue(content.contains("// The total calculated for a user"), "Comment text should not be modified")
        // String literal text 'total' should remain intact, while interpolation '$total' becomes '$grandTotal'
        assertTrue(content.contains("\"Your total amount is \$grandTotal\""), "String interpolation should be updated but literal text preserved: $content")
        // AST variable declaration & return statement should be updated
        assertTrue(content.contains("val grandTotal = price * 2"))
        assertTrue(content.contains("return grandTotal"))
    }

    @Test
    fun `renameSymbol does not mutate comments or string literals in workspace files`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-rename-ast")
        val fileA = workspace.resolve("A.kt").toFile()
        fileA.writeText("""
            // Widget representation
            class Widget {
                val name = "Widget"
                fun printWidget() = println("Widget: ${'$'}name")
            }
        """.trimIndent())

        val result = service.execute(
            LspAction.RENAME_SYMBOL,
            code = "fun useWidget(w: Widget) = w.printWidget()",
            symbol = "Widget",
            newName = "Gadget",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")

        val updatedA = fileA.readText()
        // Comment must be untouched
        assertTrue(updatedA.contains("// Widget representation"), "Comment in workspace file must be preserved: $updatedA")
        // Plain string literal text "Widget" must be untouched
        assertTrue(updatedA.contains("val name = \"Widget\""), "String literal in workspace file must be preserved: $updatedA")
        // Class declaration must be renamed
        assertTrue(updatedA.contains("class Gadget"), "Class declaration must be updated: $updatedA")

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `getCompletions includes DocService stdlib symbols matching the prefix`() {
        val serviceWithDocs = DefaultLspService(DefaultDocService())
        val result = serviceWithDocs.execute(LspAction.GET_COMPLETIONS, "val x = 1", symbol = "mapNotNull")
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("mapNotNull"), "expected DocService completion in: ${success.content}")
    }

    @Test
    fun `getCompletions suggests String members for a String receiver`() {
        val snippet = """
            fun main() {
                val name: String = "alice"
                println(name.uppercase())
            }
        """.trimIndent()

        val result = service.execute(LspAction.GET_COMPLETIONS, snippet, symbol = "name.")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("length"), "expected String member 'length' in: ${success.content}")
        assertTrue(success.content.contains("uppercase"), "expected String extension 'uppercase' in: ${success.content}")
        assertTrue(success.content.contains("lowercase"), "expected String extension 'lowercase' in: ${success.content}")
    }

    @Test
    fun `getCompletions includes in-scope locals and parameters`() {
        val snippet = """
            fun main() {
                val total = 5
                val totalCopy = total
                println(totalCopy)
            }
        """.trimIndent()

        val result = service.execute(LspAction.GET_COMPLETIONS, snippet, symbol = "total")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("total"), "expected local 'total' in: ${success.content}")
        assertTrue(success.content.contains("totalCopy"), "expected local 'totalCopy' in: ${success.content}")
    }

    @Test
    fun `getCompletions lists members of a project class receiver`() {
        val snippet = """
            class Counter {
                fun increment(): Int = 1
                fun reset() {}
            }
            fun main() { val c = Counter(); println(c.increment()) }
        """.trimIndent()

        val result = service.execute(LspAction.GET_COMPLETIONS, snippet, symbol = "c.")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("increment"), "expected receiver member 'increment' in: ${success.content}")
        assertTrue(success.content.contains("reset"), "expected receiver member 'reset' in: ${success.content}")
    }

    @Test
    fun `getCompletions keeps curated idioms in a clearly separated section`() {
        val result = service.execute(LspAction.GET_COMPLETIONS, "", symbol = "")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Idiom suggestions (curated)"), "expected curated section in: ${success.content}")
        assertTrue(success.content.contains("map { it }"), "expected idiom 'map { it }' in: ${success.content}")
    }

    @Test
    fun `typeHierarchy traces supertypes and subclasses`() {
        val snippet = """
            interface Repository
            class SqlRepository : Repository
            class MemRepository : Repository
        """.trimIndent()

        val result = service.execute(LspAction.TYPE_HIERARCHY, snippet, symbol = "Repository")
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Type Hierarchy for `Repository`"))
        assertTrue(success.content.contains("SqlRepository"))
        assertTrue(success.content.contains("MemRepository"))
    }

    @Test
    fun `typeHierarchy handles multi-file workspace with package directives without syntax errors`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-typeh")
        val fileA = workspace.resolve("A.kt").toFile()
        fileA.writeText("""
            package com.example.domain
            interface BaseService
        """.trimIndent())

        val fileB = workspace.resolve("B.kt").toFile()
        fileB.writeText("""
            package com.example.service
            import com.example.domain.BaseService
            class CustomService : BaseService
        """.trimIndent())

        val result = service.execute(
            LspAction.TYPE_HIERARCHY,
            code = "package com.example.app\nclass AppService : BaseService",
            symbol = "BaseService",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("CustomService"), "subclasses across files should be found: ${success.content}")
        assertTrue(success.content.contains("AppService"), "subclasses in snippet should be found: ${success.content}")

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `callHierarchy handles multi-file workspace with package directives without syntax errors`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-callh")
        val fileA = workspace.resolve("A.kt").toFile()
        fileA.writeText("""
            package com.example.utils
            fun executeAction() {}
        """.trimIndent())

        val fileB = workspace.resolve("B.kt").toFile()
        fileB.writeText("""
            package com.example.runner
            import com.example.utils.executeAction
            fun runAll() {
                executeAction()
            }
        """.trimIndent())

        val result = service.execute(
            LspAction.CALL_HIERARCHY,
            code = "package com.example.main\nfun start() { executeAction() }",
            symbol = "executeAction",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("runAll"), "callers in workspace should be identified: ${success.content}")
        assertTrue(success.content.contains("start"), "callers in snippet should be identified: ${success.content}")

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `renameSymbol does not corrupt unrelated classes with the same property name in different packages`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-scope-rename")
        val fileA = workspace.resolve("com/example/model/User.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("package com.example.model\nclass User(val id: String)\n")
        }
        val fileB = workspace.resolve("com/example/other/Product.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("package com.example.other\nclass Product(val id: Long)\n")
        }

        val result = service.execute(
            LspAction.RENAME_SYMBOL,
            code = "package com.example.model\nfun getUserId(u: User) = u.id",
            symbol = "id",
            newName = "userId",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success)

        val updatedProduct = fileB.readText()
        assertTrue(updatedProduct.contains("val id: Long"), "Product in another package must retain its own 'id' property: $updatedProduct")

        workspace.toFile().deleteRecursively()
    }
}

