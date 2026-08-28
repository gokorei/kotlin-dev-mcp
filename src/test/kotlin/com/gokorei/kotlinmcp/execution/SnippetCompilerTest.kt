package com.gokorei.kotlinmcp.execution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SnippetCompilerTest {

    @Test
    fun `compile resolves project types when their classpath is supplied`() {
        val libSource = """
            package demo
            class Greeter { fun greet(): String = "hello" }
        """.trimIndent()

        val libResult = SnippetCompiler.compile(libSource)
        assertTrue(libResult is CompileResult.Compiled, "expected lib to compile, got: $libResult")
        val libOut = (libResult as CompileResult.Compiled).outDir.toString()

        val consumer = """
            import demo.Greeter
            fun main() { println(Greeter().greet()) }
        """.trimIndent()

        val without = SnippetCompiler.compile(consumer)
        val withoutErrors = (without as? CompileResult.Compiled)
            ?.diagnostics?.filter { it.severity == "error" }.orEmpty()
        assertTrue(withoutErrors.isNotEmpty(), "expected unresolved reference without the classpath")

        val with = SnippetCompiler.compile(consumer, listOf(libOut))
        val withErrors = (with as? CompileResult.Compiled)
            ?.diagnostics?.filter { it.severity == "error" }.orEmpty()
        assertTrue(withErrors.isEmpty(), "expected no errors with classpath, got: $withErrors")

        SnippetCompiler.cleanup(libResult)
        SnippetCompiler.cleanup(without)
        SnippetCompiler.cleanup(with)
    }

    @Test
    fun `cleanup removes the temp directory backing a compiled result`() {
        val result = SnippetCompiler.compile("fun f(): Int = 1")
        assertTrue(result is CompileResult.Compiled)
        val compiled = result as CompileResult.Compiled
        val tempRoot = compiled.tempRoot.toFile()
        assertTrue(tempRoot.exists(), "temp root should exist before cleanup")

        SnippetCompiler.cleanup(result)
        assertFalse(tempRoot.exists(), "temp root should be deleted after cleanup")
    }

    @Test
    fun `detectProjectClasspath finds build classes directory`() {
        val tempDir = java.nio.file.Files.createTempDirectory("kmcp-project-test")
        val classesDir = tempDir.resolve("build/classes/kotlin/main")
        java.nio.file.Files.createDirectories(classesDir)

        val detected = SnippetCompiler.detectProjectClasspath(tempDir.toString())
        assertTrue(detected.contains(classesDir.toString()))

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `compile resolves workspace project types when projectPath is supplied`() {
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-workspace-cp")
        try {
            val lib = SnippetCompiler.compile("""
                package demo
                class Greeter { fun greet(): String = "hello" }
            """.trimIndent())
            val libOut = (lib as CompileResult.Compiled).outDir

            val classesDir = workspace.resolve("build/classes/kotlin/main")
            java.nio.file.Files.createDirectories(classesDir)
            libOut.toFile().walkTopDown().forEach { f ->
                if (f.isFile) {
                    val dest = classesDir.resolve(libOut.relativize(f.toPath()).toString())
                    java.nio.file.Files.createDirectories(dest.parent)
                    java.nio.file.Files.copy(f.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }

            val consumer = """
                import demo.Greeter
                fun main() { println(Greeter().greet()) }
            """.trimIndent()

            val without = SnippetCompiler.compile(consumer)
            val withoutErrors = (without as? CompileResult.Compiled)
                ?.diagnostics?.filter { it.severity == "error" }.orEmpty()
            assertTrue(withoutErrors.isNotEmpty(), "expected unresolved reference without projectPath")

            val with = SnippetCompiler.compile(consumer, projectPath = workspace.toString())
            val withErrors = (with as? CompileResult.Compiled)
                ?.diagnostics?.filter { it.severity == "error" }.orEmpty()
            assertTrue(withErrors.isEmpty(), "expected no errors with projectPath, got: $withErrors")

            SnippetCompiler.cleanup(lib)
            SnippetCompiler.cleanup(without)
            SnippetCompiler.cleanup(with)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detectProjectClasspath finds KSP and KAPT generated code directories`() {
        val tempDir = java.nio.file.Files.createTempDirectory("kmcp-ksp-test")
        val kspDir = tempDir.resolve("build/generated/ksp/main/kotlin")
        val kaptDir = tempDir.resolve("build/generated/source/kapt/main")
        java.nio.file.Files.createDirectories(kspDir)
        java.nio.file.Files.createDirectories(kaptDir)

        val detected = SnippetCompiler.detectProjectClasspath(tempDir.toString())
        assertTrue(detected.contains(kspDir.toString()), "expected ksp dir in detected classpath: $detected")
        assertTrue(detected.contains(kaptDir.toString()), "expected kapt dir in detected classpath: $detected")

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `coroutines flow resolves without explicit classpath`() {
        val snippet = """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flowOf
            fun numbers(): Flow<Int> = flowOf(1, 2, 3)
        """.trimIndent()

        val result = SnippetCompiler.compile(snippet)
        val errors = (result as? CompileResult.Compiled)
            ?.diagnostics?.filter { it.severity == "error" }.orEmpty()
        assertTrue(errors.isEmpty(), "expected flow to resolve without classpath, got: $errors")
        SnippetCompiler.cleanup(result)
    }

    @Test
    fun `fat-jar launch falls back to bundled snippet library classpath`() {
        // TK-94DK6TD1 regression: under `java -jar <all.jar>` the JVM's
        // java.class.path is the single flat fat jar, so the name-filtered
        // system classpath is empty and SnippetCompiler must fall back to the
        // library jars bundled as resources by `dumpSnippetClasspath`.
        val fatJarOnly = "/path/to/kotlin-mcp-1.0.0-all.jar"
        val resolved = SnippetCompiler.resolveDefaultImports(fatJarOnly)

        assertTrue(resolved.isNotEmpty(), "expected non-empty classpath fallback under fat-jar launch, got: $resolved")
        resolved.forEach { p ->
            assertTrue(java.io.File(p).isFile, "materialized classpath entry must be a real file on disk: $p")
        }
        val names = resolved.map { it.substringAfterLast('/').lowercase() }
        assertTrue(
            names.any { it.contains("kotlin-stdlib") },
            "expected kotlin-stdlib in bundled classpath, got: $names"
        )
        assertTrue(
            names.any { it.contains("kotlinx-coroutines") },
            "expected kotlinx-coroutines in bundled classpath, got: $names"
        )

        // The bundled jars alone must let a coroutines snippet resolve (no
        // unresolved-reference errors).
        val snippet = """
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.async
            suspend fun lo(): List<Int> = coroutineScope { (1..3).map { async { it } }.map { it.await() } }
        """.trimIndent()
        val result = SnippetCompiler.compile(snippet, resolved)
        assertTrue(
            result is CompileResult.Compiled,
            "expected compile to succeed against bundled classpath, got: $result"
        )
        val errors = (result as CompileResult.Compiled).diagnostics.filter { it.severity == "error" }
        assertTrue(errors.isEmpty(), "expected coroutines snippet to compile against bundled classpath, got: $errors")
        SnippetCompiler.cleanup(result)
    }

    @Test
    fun `materializeBundledSnippetClasspath rejects invalid non-zip files and handles corrupted resources safely`() {
        SnippetCompiler.resetBundledSnippetClasspathCache()
        val corruptLoader = object : ClassLoader(SnippetCompiler::class.java.classLoader) {
            override fun getResourceAsStream(name: String): java.io.InputStream? {
                return when (name) {
                    "snippet-classpath/snippet.classpath.txt" -> "corrupted.jar\n".byteInputStream()
                    "snippet-classpath/corrupted.jar" -> "NOT_A_VALID_ZIP_HEADER".byteInputStream()
                    else -> super.getResourceAsStream(name)
                }
            }
        }

        val result = SnippetCompiler.materializeBundledSnippetClasspath(corruptLoader)
        assertTrue(result.isEmpty(), "Expected empty list when all bundled jars are corrupt, got: $result")
        SnippetCompiler.resetBundledSnippetClasspathCache()
    }

    @Test
    fun `external library without classpath is a hard unresolved error`() {
        val snippet = """
            import org.example.missinglib.Widget
            fun make(): Widget = Widget()
        """.trimIndent()

        val result = SnippetCompiler.compile(snippet)
        val errors = (result as? CompileResult.Compiled)
            ?.diagnostics?.filter { it.severity == "error" }.orEmpty()
        assertTrue(errors.isNotEmpty(), "expected unresolved reference for missing library without classpath")
        assertTrue(
            errors.any { it.message.contains("Unresolved reference") || it.message.contains("unresolved") },
            "expected unresolved-reference error, got: ${errors.map { it.message }}"
        )
        SnippetCompiler.cleanup(result)
    }

    @Test
    fun `external library with classpath resolves`() {
        val libSource = """
            package org.example.missinglib
            class Widget(val label: String = "w")
        """.trimIndent()
        val libResult = SnippetCompiler.compile(libSource)
        assertTrue(libResult is CompileResult.Compiled, "expected lib to compile, got: $libResult")
        val libOut = (libResult as CompileResult.Compiled).outDir.toString()

        val consumer = """
            import org.example.missinglib.Widget
            fun make(): Widget = Widget()
        """.trimIndent()

        val result = SnippetCompiler.compile(consumer, listOf(libOut))
        val errors = (result as? CompileResult.Compiled)
            ?.diagnostics?.filter { it.severity == "error" }.orEmpty()
        assertTrue(errors.isEmpty(), "expected missing lib to resolve with classpath, got: $errors")

        SnippetCompiler.cleanup(libResult)
        SnippetCompiler.cleanup(result)
    }

    @Test
    fun `structured collector reports a line and column for each diagnostic`() {
        val result = SnippetCompiler.compile("fun f(): Int = \"boom\"\nfun g(): Int = \"boom2\"")
        assertTrue(result is CompileResult.Compiled, "expected compiled result, got: $result")
        val errors = (result as CompileResult.Compiled).diagnostics.filter { it.severity == "error" }
        assertTrue(errors.isNotEmpty(), "expected type-mismatch errors")
        // Each error must carry a real line and column (from MessageCollector
        // source locations, not a text scrape).
        errors.forEach { d ->
            assertTrue(d.line != null && d.column != null, "expected line+column on $d")
            assertTrue(d.message.isNotBlank(), "expected message text on $d")
        }
        SnippetCompiler.cleanup(result)
    }

    @Test
    fun `detectProjectClasspath finds deeply nested multi-module project build directories beyond depth 6`() {
        val tempDir = java.nio.file.Files.createTempDirectory("kmcp-deep-project")
        val deepClasses = tempDir.resolve("module1/module2/module3/module4/module5/module6/build/classes/kotlin/main")
        java.nio.file.Files.createDirectories(deepClasses)

        val detected = SnippetCompiler.detectProjectClasspath(tempDir.toString())
        assertTrue(detected.contains(deepClasses.toString()), "expected deeply nested classes dir to be detected beyond depth 6: $detected")

        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `detectProjectClasspath finds AGP android intermediates classes`() {
        val tempDir = java.nio.file.Files.createTempDirectory("kmcp-agp-project")
        val agpDir = tempDir.resolve("app/build/intermediates/javac/debug/classes")
        java.nio.file.Files.createDirectories(agpDir)

        val detected = SnippetCompiler.detectProjectClasspath(tempDir.toString())
        assertTrue(detected.contains(agpDir.toString()), "expected AGP javac dir to be detected: $detected")

        tempDir.toFile().deleteRecursively()
    }
}

