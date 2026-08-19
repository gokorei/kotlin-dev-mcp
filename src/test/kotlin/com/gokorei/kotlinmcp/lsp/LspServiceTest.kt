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
    fun `renameSymbol does not rename an unrelated same-name top-level symbol`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-rename-local")
        val otherFile = workspace.resolve("Other.kt").toFile()
        otherFile.writeText("val total = 3\n")

        val snippet = """
            fun main() {
                val total = 5
                val double = total * 2
                println(double)
            }
        """.trimIndent()

        val result = service.execute(
            LspAction.RENAME_SYMBOL,
            snippet,
            symbol = "total",
            newName = "grandTotal",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("val grandTotal = 5"), "snippet local must be renamed: ${success.content}")
        assertTrue(success.content.contains("val double = grandTotal * 2"), "snippet usage must be renamed: ${success.content}")
        assertTrue(otherFile.readText().contains("val total = 3"), "unrelated same-name top-level must be untouched: ${otherFile.readText()}")

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `renameSymbol updates all bound usages across workspace files`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-rename-bound")
        val modelFile = workspace.resolve("model/Math.kt").toFile()
        modelFile.parentFile.mkdirs()
        modelFile.writeText("""
            package app.model

            fun double(price: Int): Int = price * 2
        """.trimIndent())
        val consumerFile = workspace.resolve("Consume.kt").toFile()
        consumerFile.writeText("""
            package app

            import app.model.double

            fun go() = double(50)
        """.trimIndent())

        val snippet = """
            package app

            import app.model.double

            fun demo() { val r = double(21) }
        """.trimIndent()

        val result = service.execute(
            LspAction.RENAME_SYMBOL,
            snippet,
            symbol = "double",
            newName = "twice",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("twice(21)"), "snippet usage must be renamed: ${success.content}")
        assertTrue(modelFile.readText().contains("fun twice(price: Int)"), "declaration must be renamed: ${modelFile.readText()}")
        assertTrue(consumerFile.readText().contains("fun go() = twice(50)"), "bound workspace usage must be renamed: ${consumerFile.readText()}")

        workspace.toFile().deleteRecursively()
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
            code = "package com.example.app\nimport com.example.domain.BaseService\nclass AppService : BaseService",
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
            code = "package com.example.main\nimport com.example.utils.executeAction\nfun start() { executeAction() }",
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
    fun `callHierarchy derives callers from resolved call sites only`() {
        // An unbound same-name member call must not be reported as a caller.
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-callh-bound")
        workspace.resolve("Service.kt").toFile().writeText("""
            package app

            fun refresh() {}
            class Widget { fun refresh() {} }
        """.trimIndent())
        workspace.resolve("Use.kt").toFile().writeText("""
            package app

            fun useWidget() { Widget().refresh() }
        """.trimIndent())

        val result = service.execute(
            LspAction.CALL_HIERARCHY,
            code = "package app\nfun go() { refresh() }",
            symbol = "refresh",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("go"), "snippet caller should be identified: ${success.content}")
        assertFalse(success.content.contains("useWidget"), "unbound member call must not be a caller: ${success.content}")

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `typeHierarchy falls back to the structural index with an explicit marker over the cap`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-typeh-fallback")
        for (i in 1..201) {
            workspace.resolve("f$i.kt").toFile().writeText("fun f$i() {}\n")
        }

        val result = service.execute(
            LspAction.TYPE_HIERARCHY,
            code = "interface Repository\nclass SqlRepository : Repository",
            symbol = "Repository",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Structural-index fallback"), "expected explicit fallback marker: ${success.content}")
        assertTrue(success.content.contains("SqlRepository"), "fallback result should still find subtypes: ${success.content}")

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `hover on a function symbol returns its resolved signature and KDoc`() {
        val snippet = """
            /**
             * Computes the squared value of an integer.
             */
            fun square(x: Int): Int = x * x

            fun main() { square(4) }
        """.trimIndent()

        val result = service.execute(LspAction.HOVER, snippet, symbol = "square")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("fun square"), "expected rendered signature: ${success.content}")
        assertTrue(success.content.contains("Type: `kotlin.Int`"), "expected call return type: ${success.content}")
        assertTrue(success.content.contains("Documentation"), "expected docs section: ${success.content}")
        assertTrue(success.content.contains("squared value"), "expected KDoc body: ${success.content}")
    }

    @Test
    fun `hover on a property returns its resolved type`() {
        val snippet = """
            val pageSize: Int = 20
        """.trimIndent()

        val result = service.execute(LspAction.HOVER, snippet, symbol = "pageSize")
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Type: `kotlin.Int`"), "expected resolved property type: ${success.content}")
        assertTrue(success.content.contains("val pageSize"), "expected rendered declaration: ${success.content}")
    }

    @Test
    fun `hover on unknown symbol returns a clear unresolved response`() {
        val result = service.execute(LspAction.HOVER, "fun main() { println(1) }", symbol = "nonExistentSymbol")
        assertTrue(result is KotlinMcpResult.Success, "expected success (unresolved is a value, not an error): ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("unresolved", ignoreCase = true), "expected explicit unresolved message: ${success.content}")
        assertEquals("false", success.metadata["found"])
    }

    @Test
    fun `hover on a workspace function resolves its signature and file`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-hover-ws")
        workspace.resolve("Greeter.kt").toFile().writeText("""
            package app
            /** Says a friendly greeting. */
            fun greet(user: String): String = "hi, ${'$'}user"
        """.trimIndent())

        val result = service.execute(
            LspAction.HOVER,
            code = "package app\nimport app.greet\nfun demo() = greet(\"ada\")",
            symbol = "greet",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("fun greet"), "expected rendered signature: ${success.content}")
        assertTrue(success.content.contains("kotlin.String"), "expected resolved types in signature: ${success.content}")
        assertTrue(success.content.contains("Greeter.kt"), "expected workspace location: ${success.content}")
        assertTrue(success.content.contains("friendly greeting"), "expected KDoc from workspace declaration: ${success.content}")

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `semantic workspace parse is cached and invalidated only when files change`() {
        val engine = DefaultK2SemanticEngine()
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-cache")
        workspace.resolve("a.kt").toFile().writeText("package app\nfun alpha() {}\n")
        workspace.resolve("b.kt").toFile().writeText("package app\nfun beta() = alpha()\n")

        engine.session(workspace.toString(), "package app\nfun demo() = alpha()")
        val rebuildsAfterFirst = engine.workspaceRebuilds
        assertTrue(rebuildsAfterFirst > 0, "first analysis must parse the workspace")

        repeat(5) {
            engine.session(workspace.toString(), "package app\nfun demo() = alpha()")
        }
        assertEquals(rebuildsAfterFirst, engine.workspaceRebuilds, "unchanged workspace must reuse the cached parse")

        val changed = workspace.resolve("b.kt").toFile()
        changed.writeText("package app\nfun beta() = alpha()\n// touched")
        changed.setLastModified(System.currentTimeMillis() + 2000)
        engine.session(workspace.toString(), "package app\nfun demo() = alpha()")

        assertTrue(engine.workspaceRebuilds > rebuildsAfterFirst, "file change must invalidate and re-parse")

        val stats = engine.workspaceStats(workspace.toString())
        assertEquals(2, stats.totalKtFiles)
        assertEquals(2, stats.analyzedFiles)
        assertFalse(stats.truncated)

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `closed semantic engine tears down safely and stops serving`() {
        val engine = DefaultK2SemanticEngine()
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-teardown")
        workspace.resolve("a.kt").toFile().writeText("package app\nfun alpha() {}\n")

        assertNotNull(engine.session(workspace.toString(), "fun demo() = alpha()"), "engine serves before close")
        engine.close()
        assertNull(engine.session(workspace.toString(), "fun demo() = alpha()"), "engine must stop serving after close")
        val stats = engine.workspaceStats(workspace.toString())
        assertEquals(0, stats.totalKtFiles, "work after teardown must degrade to empty stats, not throw")

        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `workspace over the semantic file cap is marked incomplete instead of failing`() {
        val engine = DefaultK2SemanticEngine(fileCap = 2)
        val capped = DefaultLspService(semanticEngine = engine)
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-cap")
        workspace.resolve("a.kt").toFile().writeText("package app\nval shared = 42\n")
        workspace.resolve("b.kt").toFile().writeText("package app\nfun useShared() = shared.toString()\n")
        workspace.resolve("c.kt").toFile().writeText("package app\nfun extra() {}\n")

        val result = capped.execute(
            LspAction.FIND_REFERENCES, code = "", symbol = "shared", workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertFalse(result.isError, "over-cap must not fail")
        assertTrue(success.content.contains("truncated", ignoreCase = true), "expected incomplete marker: ${success.content}")
        assertTrue(success.content.contains("2 of 3"), "expected analyzed/total counts: ${success.content}")

        workspace.toFile().deleteRecursively()
        engine.close()
    }

    @Test
    fun `unanalyzable or non-source workspaces degrade gracefully without error`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-degrade")
        workspace.resolve("junk.kt").toFile().writeBytes(ByteArray(4096) { 0x00 })
        workspace.resolve("app.kt").toFile().writeText("package app\nfun make() = 42\n")

        val result = service.execute(
            LspAction.FIND_DEFINITION, code = "package app\nfun caller() = make()", symbol = "make",
            workspacePath = workspace.toString()
        )
        assertTrue(result !is KotlinMcpResult.Error, "binary junk must not surface as an error: ${result.toFormattedText()}")
        assertTrue(result is KotlinMcpResult.Success, "expected success fallback, got: ${result.toFormattedText()}")

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

    @Test
    fun `renameSymbol reports conflict when file on disk has unexpected content at offset`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-lsp-rename-conflict")
        val fileA = workspace.resolve("com/example/model/User.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("package com.example.model\nclass User(val name: String)\n")
        }

        val customEngine = object : K2SemanticEngine by DefaultK2SemanticEngine() {
            override fun renameEditsForSymbol(session: K2AnalysisSession, symbol: String, workspacePath: String?): List<ResolvedRenameEdit> {
                // Return an edit with an offset pointing to something other than 'name'
                return listOf(ResolvedRenameEdit("com/example/model/User.kt", offset = 0, length = 7))
            }
        }
        val customService = DefaultLspService(semanticEngine = customEngine)

        val result = customService.execute(
            LspAction.RENAME_SYMBOL,
            code = "package com.example.model\nfun get(u: User) = u.name",
            symbol = "name",
            newName = "fullName",
            workspacePath = workspace.toString()
        )
        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("CONFLICT"), "conflict should be reported: ${success.content}")
        assertTrue(fileA.readText().startsWith("package"), "file content should not be corrupted on conflict")

        workspace.toFile().deleteRecursively()
    }
}

