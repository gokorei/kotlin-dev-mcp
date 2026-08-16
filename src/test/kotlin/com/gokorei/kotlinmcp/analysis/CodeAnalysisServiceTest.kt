package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CodeAnalysisServiceTest {

    private lateinit var codeAnalysisService: CodeAnalysisService

    @BeforeEach
    fun setUp() {
        codeAnalysisService = DefaultCodeAnalysisService()
    }

    @Test
    fun `analyze_nullability detects unsafe force non-null assertions`() {
        val snippet = """
            fun process(name: String?) {
                val length = name!!.length
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.ANALYZE_NULLABILITY,
            code = snippet
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Unsafe non-null assertion `!!` detected"))
    }

    @Test
    fun `analyze_nullability does not flag requireNotNull or checkNotNull guarded use`() {
        val snippet = """
            fun process(input: String?) {
                requireNotNull(input)
                println(input.length)
            }
            fun other(value: List<Int>?) {
                checkNotNull(value)
                println(value.size)
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.ANALYZE_NULLABILITY,
            code = snippet
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("Unsafe dereference"), "guarded use must not be flagged: ${success.content}")
    }

    @Test
    fun `analyze_nullability still flags genuinely unsafe dereference`() {
        val snippet = """
            fun process(input: String?) {
                println(input.length)
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.ANALYZE_NULLABILITY,
            code = snippet
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Unsafe dereference"), "unguarded deref must be flagged: ${success.content}")
    }

    @Test
    fun `explain_coroutines flags blocking Thread sleep inside coroutine scope`() {
        val snippet = """
            suspend fun fetchData() = coroutineScope {
                Thread.sleep(1000)
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.EXPLAIN_COROUTINES,
            code = snippet
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Blocking call `Thread.sleep` detected"))
    }

    @Test
    fun `analyze_nullability flags unsafe dereference of nullable value`() {
        val snippet = """
            fun process(name: String?) {
                val length = name.length
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.ANALYZE_NULLABILITY,
            code = snippet
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Unsafe dereference of nullable `name`"))
    }

    @Test
    fun `analyze_nullability does not flag safe call or elvis`() {
        val snippet = """
            fun process(name: String?) {
                val a = name?.length
                val b = name ?: "x"
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.ANALYZE_NULLABILITY,
            code = snippet
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("Unsafe dereference"))
    }

    @Test
    fun `explain_coroutines flags unbounded while true loop without suspend point`() {
        val snippet = """
            fun worker() = CoroutineScope(Dispatchers.Default).launch {
                while (true) {
                    doWork()
                }
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.EXPLAIN_COROUTINES,
            code = snippet
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Unbounded `while(true)` loop"))
    }

    @Test
    fun `inspect_symbol extracts class declaration and members`() {
        val snippet = """
            class User(val id: String, var email: String?) {
                fun isActive(): Boolean = email != null
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.INSPECT_SYMBOL,
            code = snippet
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Class: User"), "expected Class: User in: ${success.content}")
        assertTrue(success.content.contains("Properties: id, email"), "expected Properties: id, email in: ${success.content}")
    }

    @Test
    fun `inspect_symbol reports multiple classes not just the first`() {
        val snippet = """
            class First(val a: Int)
            class Second(val b: String)
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.INSPECT_SYMBOL, snippet)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Class: First"), "expected First in: ${success.content}")
        assertTrue(success.content.contains("Class: Second"), "expected Second in: ${success.content}")
    }

    @Test
    fun `inspect_symbol lists enums objects and nested classes`() {
        val snippet = """
            enum class Color { RED, GREEN, BLUE }
            object Logger
            class Outer(val x: Int) {
                class Inner(val y: Int)
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.INSPECT_SYMBOL, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Enum: Color"), "expected enum in: ${success.content}")
        assertTrue(success.content.contains("Object: Logger"), "expected object in: ${success.content}")
        assertTrue(success.content.contains("Nested classes: Inner"), "expected nested class in: ${success.content}")
    }

    @Test
    fun `inspect_symbol captures secondary constructors and top-level properties`() {
        val snippet = """
            val topLevelThreshold: Int = 42

            class Point(val x: Int, val y: Int) {
                constructor() : this(0, 0) {
                    println("origin")
                }
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.INSPECT_SYMBOL, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Secondary constructors: 1"), "expected secondary ctor in: ${success.content}")
        assertTrue(success.content.contains("Top-level properties: topLevelThreshold"), "expected top-level prop in: ${success.content}")
    }

    @Test
    fun `analyze_compose flags unstable param type and un-remembered state`() {
        val snippet = """
            data class Profile(val id: String, val name: String)

            @Composable
            fun ProfileCard(profile: Profile) {
                val expanded by remember { mutableStateOf(false) }
                Text(profile.name)
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.ANALYZE_COMPOSE,
            code = snippet
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("not a known stable type"), "expected unstable-param note in: ${success.content}")
    }

    @Test
    fun `analyze_compose accepts stable composable with keyed remember`() {
        val snippet = """
            @Composable
            fun Counter(count: Int, onIncrement: () -> Unit) {
                val doubled by remember(count) { mutableStateOf(count * 2) }
                Text("${'$'}doubled")
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.ANALYZE_COMPOSE,
            code = snippet
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("unstable"), "expected no unstable flags in: ${success.content}")
    }

    @Test
    fun `explain_coroutines flags hardcoded dispatchers for injection`() {
        val snippet = """
            fun load() {
                launch(Dispatchers.IO) { doWork() }
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(
            action = CodeAnalysisAction.EXPLAIN_COROUTINES,
            code = snippet
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Dispatchers.IO"), "expected hardcoded dispatcher flag in: ${success.content}")
        assertTrue(success.content.contains("Inject"), "expected injection guidance in: ${success.content}")
    }

    @Test
    fun `inspect_symbol ignores braces inside string literals and comments`() {
        val snippet = """
            class Sample {
                val greeting = "Hello { World } \"escaped\""
                // Comment { with braces }
                /* Block comment { with braces } */
                fun test() = greeting
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.INSPECT_SYMBOL, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Class: Sample"))
        assertTrue(success.content.contains("Properties: greeting"))
        assertTrue(success.content.contains("Functions: test"))
    }

    @Test
    fun `file_context summarizes imports and cross-file dependencies`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-file-ctx")
        val pkg = dir.resolve("src/main/kotlin/com/example")
        java.nio.file.Files.createDirectories(pkg)

        val fileA = pkg.resolve("A.kt").toFile()
        fileA.writeText("""
            package com.example
            import java.util.Date
            
            class A {
                fun process() = println("A")
            }
        """.trimIndent())

        val fileB = pkg.resolve("B.kt").toFile()
        fileB.writeText("""
            package com.example
            
            fun runB() {
                val a = A()
                a.process()
            }
        """.trimIndent())

        try {
            val result = codeAnalysisService.execute(
                action = CodeAnalysisAction.FILE_CONTEXT,
                code = fileA.absolutePath,
                workspacePath = dir.toString()
            )

            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("File Context"), "expected file context header")
            assertTrue(success.content.contains("java.util.Date"), "expected import in context")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `analyze_nullability does not flag exclamation inside comments or strings`() {
        val snippet = """
            fun process(name: String?) {
                // name!!.length should be ignored
                val hint = "never name!!.length here"
                println(name?.length)
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_NULLABILITY, snippet)
        assertTrue(result.isSuccess)
        val content = (result as KotlinMcpResult.Success).content
        assertFalse(content.contains("Unsafe non-null assertion"), "comments/strings must not trigger !! findings: $content")
    }

    @Test
    fun `analyze_nullability multi-line smart cast guard is honored`() {
        val snippet = """
            fun process(name: String?) {
                if (name != null) {
                    println(name.length)
                }
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_NULLABILITY, snippet)
        assertTrue(result.isSuccess)
        val content = (result as KotlinMcpResult.Success).content
        assertFalse(content.contains("Unsafe dereference"), "block-scoped guard must suppress deref: $content")
    }

    @Test
    fun `explain_coroutines does not flag while true inside a properly suspended launch`() {
        val snippet = """
            fun worker() = CoroutineScope(Dispatchers.Default).launch {
                while (true) {
                    delay(100)
                }
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.EXPLAIN_COROUTINES, snippet)
        assertTrue(result.isSuccess)
        val content = (result as KotlinMcpResult.Success).content
        assertFalse(content.contains("Unbounded `while(true)`"), "delay() is a suspension point: $content")
    }

    @Test
    fun `explain_coroutines does not flag GlobalScope or Thread sleep inside comments`() {
        val snippet = """
            // GlobalScope.launch { } is commented out
            suspend fun go() {
                val msg = "Thread.sleep is a string"
                delay(1)
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.EXPLAIN_COROUTINES, snippet)
        assertTrue(result.isSuccess)
        val content = (result as KotlinMcpResult.Success).content
        assertFalse(content.contains("GlobalScope"), "comment must not trigger GlobalScope finding: $content")
        assertFalse(content.contains("Thread.sleep"), "string must not trigger Thread.sleep finding: $content")
    }

    @Test
    fun `inspect_symbol handles backtick identifiers and multi-line class headers`() {
        val snippet = "class `my-class`(\n    val id: String,\n    val value: Int\n) {\n    fun `do work`() = id\n}"
        val result = codeAnalysisService.execute(CodeAnalysisAction.INSPECT_SYMBOL, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("my-class"), "backtick class name must be reported: $content")
        assertTrue(content.contains("Properties: id, value"), "multi-line constructor props must be reported: $content")
        assertTrue(content.contains("do work"), "backtick function must be reported: $content")
    }

    @Test
    fun `analyze_compose handles nested braces inside remember block without truncation`() {
        val snippet = """
            @Composable
            fun ItemList(items: List<String>) {
                val state = remember {
                    val map = items.map { it.uppercase() }
                    mutableStateOf(map)
                }
                Text("Count: " + state.value.size)
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_COMPOSE, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("has no key arguments yet its body declares mutable state"), "nested brace must not truncate remember block body: $content")
    }

    @Test
    fun `analyze_compose does not flag standard Kotlin collection operations or scope functions`() {
        val snippet = """
            @Composable
            fun UserList(users: List<String>) {
                users.forEach { name ->
                    Text(name)
                }
                val upper = users.map { it.uppercase() }
                val item = users.firstOrNull()?.let { it.trim() }
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_COMPOSE, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertFalse(content.contains("inline lambda passed to a composable call"), "collection and scope lambdas must not be flagged: $content")
    }

    @Test
    fun `file_context rejects target file located outside workspace path`() {
        val workspaceDir = java.nio.file.Files.createTempDirectory("kmcp-ws")
        val outsideDir = java.nio.file.Files.createTempDirectory("kmcp-outside")
        val outsideFile = outsideDir.resolve("Outside.kt").toFile()
        outsideFile.writeText("package outside\nclass Outside")

        try {
            val result = codeAnalysisService.execute(
                action = CodeAnalysisAction.FILE_CONTEXT,
                code = outsideFile.absolutePath,
                workspacePath = workspaceDir.toString()
            )

            assertTrue(result.isError)
            val error = result as KotlinMcpResult.Error
            assertTrue(error.message.contains("inside the workspace root directory"))
        } finally {
            workspaceDir.toFile().deleteRecursively()
            outsideDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `explain_coroutines flags Future get and CompletableFuture join blocking calls`() {
        val snippet = """
            suspend fun loadAsync(future: java.util.concurrent.CompletableFuture<String>) {
                val value = future.join()
                println(value)
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.EXPLAIN_COROUTINES, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("Blocking call `join`") || content.contains("join"), "expected join blocking call advisory in: $content")
    }

    @Test
    fun `explain_coroutines flags un-suspended while true loop in top-level suspend function`() {
        val snippet = """
            suspend fun busyLoop() {
                while (true) {
                    val x = 1 + 1
                }
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.EXPLAIN_COROUTINES, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("Unbounded `while(true)` loop"), "expected while(true) loop advisory in top-level suspend fn: $content")
    }

    @Test
    fun `analyze_nullability detects inter-function nullable parameter passing`() {
        val snippet = """
            fun findUser(): String? = null
            fun processUser(name: String) = name.length

            fun main() {
                processUser(findUser())
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_NULLABILITY, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("Unsafe dereference") || content.contains("nullable") || content.contains("findUser"), "expected inter-function nullability advisory in: $content")
    }

    @Test
    fun `analyze_nullability does not flag nullable receiver inside safe scope function let or also`() {
        val snippet = """
            fun process(name: String?) {
                name?.let {
                    println(it.length)
                }
                name?.also {
                    println(it.length)
                }
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_NULLABILITY, snippet)
        assertTrue(result.isSuccess)
        val content = (result as KotlinMcpResult.Success).content
        assertFalse(content.contains("Unsafe dereference"), "safe scope functions must not trigger deref finding: $content")
    }

    @Test
    fun `analyze_nullability respects check assertion and is type guards`() {
        val snippet = """
            fun process(input: Any?) {
                check(input != null)
                println(input.hashCode())
            }
            fun inspect(name: String?) {
                if (name is String) {
                    println(name.length)
                }
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_NULLABILITY, snippet)
        assertTrue(result.isSuccess)
        val content = (result as KotlinMcpResult.Success).content
        assertFalse(content.contains("Unsafe dereference"), "check and is guards must suppress deref finding: $content")
    }

    @Test
    fun `explain_coroutines flags InputStream read and CountDownLatch await blocking calls`() {
        val snippet = """
            suspend fun readStream(stream: java.io.InputStream, latch: java.util.concurrent.CountDownLatch) {
                latch.await()
                val b = stream.read()
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.EXPLAIN_COROUTINES, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("await") || content.contains("read"), "expected blocking call advisory for stream read and latch await: $content")
    }

    @Test
    fun `analyze_compose recognizes JvmInline value class as stable parameter`() {
        val snippet = """
            @JvmInline
            value class UserId(val id: Long)

            @Composable
            fun UserHeader(userId: UserId) {
                Text(userId.id.toString())
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_COMPOSE, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertFalse(content.contains("not a known stable type"), "JvmInline value class must be recognized as stable: $content")
    }
}


