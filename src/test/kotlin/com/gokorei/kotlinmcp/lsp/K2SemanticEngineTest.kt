package com.gokorei.kotlinmcp.lsp

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class K2SemanticEngineTest {

    private val engine = DefaultK2SemanticEngine()

    private fun tempWorkspace(prefix: String): java.io.File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    @Test
    fun `session builds over temp workspace plus snippet without exceptions`() {
        val ws = tempWorkspace("kmcp-engine-session")
        try {
            ws.resolve("Model.kt").writeText("package com.example.model\nclass User(val name: String)\n")
            val session = engine.session(ws.absolutePath, "package com.example.app\nfun main() { val u: User? = null }")
            assertNotNull(session, "expected a K2AnalysisSession over workspace + snippet")
            assertNotNull(session!!.bindingContext)
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `cross-file reference in snippet resolves to workspace declaration file and line`() {
        val ws = tempWorkspace("kmcp-engine-crossfile")
        try {
            ws.resolve("User.kt").writeText(
                "package com.example.model\nclass User(val name: String)\n".trimIndent()
            )
            ws.resolve("Service.kt").writeText(
                "package com.example.service\nimport com.example.model.User\nclass Service { fun greet(u: User) = u.name }\n".trimIndent()
            )

            val snippet = """
                package com.example.app
                import com.example.model.User
                fun main() { val u = User("x"); println(u.name) }
            """.trimIndent()

            val session = engine.session(ws.absolutePath, snippet)!!
            val ref = findReference(session, "User")
            val resolved = engine.resolveReference(session, ref, ws.absolutePath)

            assertNotNull(resolved, "reference must resolve across files")
            assertEquals("User", resolved!!.symbol)
            assertEquals("User.kt", resolved.file)
            assertEquals("com.example.model.User", resolved.fqn)
            assertEquals(ResolvedSource.WORKSPACE, resolved.source)
            assertTrue(resolved.line > 0, "expected a real line, got ${resolved.line}")
            assertNotNull(resolved.signature)
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `snippet-local declaration resolves to snippet source`() {
        val snippet = """
            package spike
            class User(val name: String)
            fun main() { val u = User("x") }
        """.trimIndent()

        val session = engine.session(null, snippet)!!
        val ref = findReference(session, "User")
        val resolved = engine.resolveReference(session, ref, null)

        assertNotNull(resolved, "snippet-local reference must resolve")
        assertEquals("Snippet.kt", resolved!!.file)
        assertEquals("spike.User", resolved.fqn)
        assertEquals(ResolvedSource.SNIPPET, resolved.source)
    }

    @Test
    fun `external stdlib symbol resolves to external source`() {
        val snippet = "fun main() { println(\"hi\") }"
        val session = engine.session(null, snippet)!!
        val ref = findReference(session, "println")
        val resolved = engine.resolveReference(session, ref, null)

        assertNotNull(resolved, "println should resolve to the stdlib")
        assertEquals(ResolvedSource.EXTERNAL, resolved!!.source)
        assertTrue(resolved.fqn.isNullOrBlank().not(), "expected an FQN for println, got ${resolved.fqn}")
    }

    @Test
    fun `fqNameOfDeclaration resolves FQN from the binding context`() {
        val snippet = """
            package spike
            data class Profile(val id: Long)
        """.trimIndent()

        val session = engine.session(null, snippet)!!
        val profile = session.file.declarations.firstOrNull { it.name == "Profile" } as KtClass
        assertEquals("spike.Profile", engine.fqNameOfDeclaration(session, profile))
    }

    @Test
    fun `typeOfExpression returns the resolved type`() {
        val snippet = "fun main() { val name: String = \"x\"; println(name) }"
        val session = engine.session(null, snippet)!!
        val ref = findReference(session, "name")
        assertEquals("kotlin.String", engine.typeOfExpression(session, ref))
    }

    @Test
    fun `referencesForSymbol lists cross-file bound references and excludes shadowed same-name symbols`() {
        val ws = tempWorkspace("kmcp-engine-references")
        try {
            ws.resolve("Model.kt").writeText(
                "package com.example.model\nfun greet(name: String) = \"hi ${'$'}name\"\n".trimIndent()
            )
            ws.resolve("App.kt").writeText(
                """
                package com.example.app
                import com.example.model.greet
                fun main() { val out = greet("x") }
                fun local() { val greet = 1; println(greet) }
                """.trimIndent()
            )

            val snippet = """
                package com.example.app
                import com.example.model.greet
                fun start() { val out = greet("y") }
            """.trimIndent()

            val session = engine.session(ws.absolutePath, snippet)!!
            val refs = engine.referencesForSymbol(session, "greet", ws.absolutePath)

            assertTrue(refs.any { it.kind == "decl" && it.file == "Model.kt" }, "declaration in Model.kt expected: $refs")
            assertTrue(refs.any { it.kind == "ref" && it.file == "App.kt" }, "call site in App.kt expected: $refs")
            assertTrue(refs.any { it.kind == "ref" && it.file == "Snippet.kt" }, "call site in snippet expected: $refs")
            assertFalse(refs.any { it.snippet.contains("val greet = 1") }, "shadowed local must not appear: $refs")
            assertFalse(refs.any { it.snippet.contains("println(greet)") }, "reference to the shadowed local must not appear: $refs")
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `workspace is parsed once and reused across calls, rebuilt only on file change`() {
        val ws = tempWorkspace("kmcp-engine-cache")
        try {
            val modelFile = ws.resolve("Model.kt")
            modelFile.writeText("package com.example.model\nclass User(val name: String)\n")

            engine.session(ws.absolutePath, "fun a() {}")
            val afterFirst = engine.workspaceRebuilds
            assertTrue(afterFirst >= 1, "workspace must be parsed at least once")

            engine.session(ws.absolutePath, "fun b() {}")
            assertEquals(afterFirst, engine.workspaceRebuilds, "repeated calls must reuse the cached workspace parse")

            modelFile.appendText("// change\n")
            modelFile.setLastModified(System.currentTimeMillis() + 2000)
            engine.session(ws.absolutePath, "fun c() {}")
            assertEquals(afterFirst + 1, engine.workspaceRebuilds, "a file modification must rebuild the workspace")
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `close invalidates the engine`() {
        val engineToClose = DefaultK2SemanticEngine()
        engineToClose.close()
        assertNull(engineToClose.session(null, "fun x() = 1"))
        assertTrue(engineToClose.projectClasspath(null).isEmpty())
    }

    @Test
    fun `workspaceStats reuses cached snapshot and renameEdits returns precise identifier ranges`() {
        val ws = tempWorkspace("kmcp-engine-stats")
        try {
            ws.resolve("Model.kt").writeText("package com.example.model\nclass TargetItem(val value: Int)\n")
            val session = engine.session(ws.absolutePath, "package com.example.app\nimport com.example.model.TargetItem\nfun use(t: TargetItem) = t.value")!!
            val stats = engine.workspaceStats(ws.absolutePath)
            assertEquals(1, stats.totalKtFiles)
            assertEquals(1, stats.analyzedFiles)
            assertFalse(stats.truncated)

            val edits = engine.renameEditsForSymbol(session, "TargetItem", ws.absolutePath)
            assertTrue(edits.isNotEmpty(), "expected rename edits for TargetItem")
            edits.forEach { edit ->
                assertEquals(10, edit.length, "edit length must match 'TargetItem' identifier length")
            }
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `workspaceStats reflects file additions and removals after snapshot exists and updates truncation when fileCap crossed`() {
        val ws = tempWorkspace("kmcp-engine-stats-updates")
        val cappedEngine = DefaultK2SemanticEngine(fileCap = 2)
        try {
            ws.resolve("A.kt").writeText("package p\nclass A\n")
            ws.resolve("B.kt").writeText("package p\nclass B\n")

            // Create initial session / snapshot
            cappedEngine.session(ws.absolutePath, "fun main() {}")
            val initialStats = cappedEngine.workspaceStats(ws.absolutePath)
            assertEquals(2, initialStats.totalKtFiles)
            assertEquals(2, initialStats.analyzedFiles)
            assertFalse(initialStats.truncated)

            // Add a 3rd file crossing the fileCap of 2
            ws.resolve("C.kt").writeText("package p\nclass C\n")
            val statsAfterAdd = cappedEngine.workspaceStats(ws.absolutePath)
            assertEquals(3, statsAfterAdd.totalKtFiles, "totalKtFiles must reflect new file C.kt")
            assertEquals(2, statsAfterAdd.analyzedFiles, "analyzedFiles must be capped at 2")
            assertTrue(statsAfterAdd.truncated, "stats must be marked truncated when totalKtFiles > fileCap")

            // Remove file B.kt returning under the cap
            ws.resolve("B.kt").delete()
            val statsAfterRemove = cappedEngine.workspaceStats(ws.absolutePath)
            assertEquals(2, statsAfterRemove.totalKtFiles, "totalKtFiles must reflect removal of B.kt")
            assertEquals(2, statsAfterRemove.analyzedFiles)
            assertFalse(statsAfterRemove.truncated, "truncation flag must clear when within cap")
        } finally {
            ws.deleteRecursively()
        }
    }

    private fun findReference(session: K2AnalysisSession, symbol: String): KtReferenceExpression {
        var found: KtReferenceExpression? = null
        session.file.accept(object : KtTreeVisitorVoid() {
            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                if (found == null && expression.getReferencedName() == symbol) {
                    found = expression
                }
                super.visitSimpleNameExpression(expression)
            }
        })
        return found ?: error("no reference named `$symbol` found in snippet")
    }
}