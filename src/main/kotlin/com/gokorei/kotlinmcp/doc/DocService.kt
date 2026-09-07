package com.gokorei.kotlinmcp.doc

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.shared.CommandService
import com.gokorei.kotlinmcp.shared.ToonUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

enum class DocAction {
    SEARCH,
    LOOKUP_SYMBOL,
    EXPLAIN_FEATURE
}

/**
 * Service interface for querying Kotlin documentation, stdlib references, and language features.
 */
interface DocService : CommandService<DocAction> {
    fun execute(action: DocAction, query: String, preset: String? = null, classpath: List<String> = emptyList()): KotlinMcpResult
    override fun execute(action: DocAction, code: String): KotlinMcpResult = execute(action, query = code, preset = null, classpath = emptyList())

    fun searchDocs(query: String, classpath: List<String> = emptyList()): KotlinMcpResult
    fun lookupSymbol(query: String, preset: String? = null, classpath: List<String> = emptyList()): KotlinMcpResult
    fun explainFeature(query: String): KotlinMcpResult
    fun listCategories(): KotlinMcpResult = searchDocs("")
    fun formatToonDocs(activeFrameworks: Any? = null): KotlinMcpResult = searchDocs("")
    fun seedResources(targetDirectory: File): KotlinMcpResult = KotlinMcpResult.Success("Seeded")

    val symbolDocs: Map<String, String>
    val featureDocs: Map<String, String>
    fun docFor(kind: String, name: String): String?
    fun registerDynamicSymbol(symbol: String, content: String)
    fun registerDynamicFeature(feature: String, content: String)
    fun registerDynamicNamespace(namespace: String, content: String)
    val namespaces: Map<String, String>
    val symbolAppliesTo: Map<String, List<String>>
    val featureAppliesTo: Map<String, List<String>>
}

class DefaultDocService(private val persistencePath: String? = null) : DocService {

    private val logger = KotlinLogging.logger {}

    private val symbolAppliesToMap: ConcurrentHashMap<String, List<String>> =
        ConcurrentHashMap(mapOf(
            "kotlinx.datetime.Instant" to listOf("kotlinx-datetime"),
            "kotlinx.datetime.Clock" to listOf("kotlinx-datetime"),
            "kotlinx.datetime.LocalDate" to listOf("kotlinx-datetime"),
            "runTest" to listOf("kotlinx-coroutines-test"),
            "MainDispatcherRule" to listOf("kotlinx-coroutines-test", "junit"),
            "Turbine.test" to listOf("turbine"),
            "mockk" to listOf("mockk"),
            "every" to listOf("mockk"),
            "verify" to listOf("mockk"),
            "Ktor/Routing" to listOf("ktor"),
            "Ktor/ContentNegotiation" to listOf("ktor"),
            "Either" to listOf("arrow"),
            "Raise" to listOf("arrow"),
            "valid" to listOf("arrow"),
            "validNel" to listOf("arrow")
        ))

    private val featureAppliesToMap: ConcurrentHashMap<String, List<String>> =
        ConcurrentHashMap(mapOf(
            "arrow" to listOf("arrow"),
            "kotlinx datetime" to listOf("kotlinx-datetime"),
            "ktor" to listOf("ktor"),
            "turbine" to listOf("turbine"),
            "mockk" to listOf("mockk")
        ))

    override val symbolAppliesTo: Map<String, List<String>>
        get() = symbolAppliesToMap

    override val featureAppliesTo: Map<String, List<String>>
        get() = featureAppliesToMap

    private val symbolDatabase: ConcurrentHashMap<String, String> = ConcurrentHashMap(StdlibSymbolCatalog.symbolDocs)

    private val featureDatabase: ConcurrentHashMap<String, String> = ConcurrentHashMap(mapOf(
        "contracts" to """
            # Kotlin Contracts (`kotlin.contracts`)
            Contracts allow a function to explicitly describe its behavior to the compiler (e.g. smart casts, callsInPlace).
            
            ## Example
            ```kotlin
            @OptIn(ExperimentalContracts::class)
            fun requireNotNull(value: Any?) {
                contract {
                    returns() implies (value != null)
                }
                if (value == null) throw IllegalArgumentException()
            }
            ```
        """.trimIndent(),
        "coroutines" to """
            # Kotlin Coroutines
            Coroutines provide lightweight, non-blocking asynchronous programming using `suspend` functions and structured concurrency (`CoroutineScope`).
        """.trimIndent(),
        "context_parameters" to """
            # Context Parameters (Kotlin 2.2+)
            Context parameters replace experimental context receivers to pass contextual dependencies down function call graphs cleanly.
            
            ## Example
            ```kotlin
            context(Locale)
            fun formatNumber(n: Int): String = ...
            ```
        """.trimIndent(),
        "sealed interface" to """
            # Sealed Interfaces (`sealed interface`)
            A sealed interface restricts which types may implement it — all implementations must be in the same package/module.
            Enables exhaustive `when` expressions without an `else` branch.
            
            ## Example
            ```kotlin
            sealed interface Result<out T> {
                data class Success<T>(val value: T) : Result<T>
                data class Failure(val error: Throwable) : Result<Nothing>
            }
            ```
        """.trimIndent(),
        "sealed class" to """
            # Sealed Classes (`sealed class`)
            Restricts class hierarchies: all subclasses must be declared in the same package and file (before Kotlin 1.5) or same package/module (after).
            Used with exhaustive `when` expressions.
        """.trimIndent(),
        "data class" to """
            # Data Classes (`data class`)
            A class whose primary purpose is to hold data. The compiler generates equals/hashCode/toString/copy/componentN for all primary-constructor properties.
            
            ## Example
            ```kotlin
            data class User(val id: Long, val email: String)
            ```
        """.trimIndent(),
        "value class" to """
            # Value Classes (`value class`)
            A wrapper around a single value with no allocation overhead at runtime. Introduced (stable) in Kotlin 1.5.
            
            ## Example
            ```kotlin
            @JvmInline
            value class UserId(val value: Long)
            ```
        """.trimIndent(),
        "smart cast" to """
            # Smart Casts
            Kotlin automatically casts a value after a type or null check when it is provably safe (immutable local or val without custom getter).
            
            ## Example
            ```kotlin
            fun len(s: String?): Int {
                if (s == null) return 0
                return s.length  // smart cast to String
            }
            ```
        """.trimIndent(),
        "extension functions" to """
            # Extension Functions
            Add new functions to existing types without inheritance or modification of the type.
            
            ## Example
            ```kotlin
            fun String.isPalindrome(): Boolean = this == this.reversed()
            ```
        """.trimIndent(),
        "scope functions" to """
            # Scope Functions: `let`, `run`, `with`, `apply`, `also`
            - `let`: transform nullable values; `T.let { it }` returns last expression.
            - `run`: compute a value from receiver context.
            - `with`: call functions on a receiver non-extension.
            - `apply`: configure the receiver and return it.
            - `also`: perform side effects and return the receiver.
        """.trimIndent(),
        "string templates" to """
            # String Templates
            Embed expressions in strings with `${'$'}name` and `${'$'}{expression}`.
            
            ## Example
            ```kotlin
            val n = 5
            "n is ${'$'}n, doubled is ${'$'}{n * 2}"
            ```
        """.trimIndent(),
        "null safety" to """
            # Null Safety
            Kotlin's type system distinguishes nullable (`T?`) from non-null (`T`) types.
            - Safe call: `a?.b`
            - Elvis: `a ?: default`
            - Safe cast: `a as? T`
            - Non-null assertion (avoid): `a!!`
        """.trimIndent(),
        "when expression" to """
            # `when` Expression
            A versatile conditional that can be an expression or statement, and supports exhaustive matching over sealed hierarchies and enums.
            
            ## Example
            ```kotlin
            fun describe(n: Int) = when (n) {
                0 -> "zero"
                in 1..10 -> "small"
                else -> "large"
            }
            ```
        """.trimIndent(),
        "serialization" to """
            # Kotlin Serialization (kotlinx.serialization)
            Compiler-plugin + runtime for type-safe (de)serialization. Annotate types with `@Serializable`,
            then use a format such as `Json`.
            
            ## Example
            ```kotlin
            @Serializable
            data class User(val id: Long, val name: String)
            
            val text = Json.encodeToString(User(1, "Ada"))
            val user = Json.decodeFromString<User>(text)
            ```
        """.trimIndent(),
        "file io" to """
            # File & IO
            Kotlin extends java.io.File and java.nio.file.Path with ergonomic functions.
            
            ## Examples
            ```kotlin
            val text = File("config.json").readText()
            File("out.txt").writeText("hello")
            Files.readString(Paths.get("data.csv"))
            ```
        """.trimIndent(),
        "jvm interop" to """
            # JVM Interop (kotlin.jvm)
            Annotations that control how Kotlin compiles to JVM bytecode for Java interop:
            - `@JvmStatic` — real static method in companions/objects
            - `@JvmField` — expose property as a field
            - `@JvmOverloads` — generate overloaded methods for default args
            - `@JvmName` — rename the generated JVM symbol
        """.trimIndent(),
        "testing" to """
            # Testing (kotlin.test)
            Framework-agnostic assertions that map to JUnit/TestNG on JVM.
            
            ## Example
            ```kotlin
            import kotlin.test.Test
            import kotlin.test.assertEquals
            
            class CalcTest {
                @Test fun adds() = assertEquals(4, 2 + 2)
            }
            ```
        """.trimIndent(),
        "gradle kotlin dsl" to """
            # Gradle Kotlin DSL (`build.gradle.kts`)
            Type-safe Gradle build scripts in Kotlin.
            
            ## Example
            ```kotlin
            plugins { kotlin("jvm") version "2.3.20" }
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") }
            tasks.test { useJUnitPlatform() }
            ```
        """.trimIndent(),
        "arrow" to """
            # Arrow (arrow-core 2.x)
            Functional programming library for Kotlin: `Either`, `Raise`, `Validated`,
            typeclasses, and optics. Import `arrow.core.*` and `arrow.core.raise.*`.
            
            ## Example
            ```kotlin
            import arrow.core.Either
            import arrow.core.raise.either
            fun div(a: Int, b: Int): Either<String, Int> = either {
                if (b == 0) raise("division by zero") else a / b
            }
            ```
        """.trimIndent(),
        "kotlinx datetime" to """
            # kotlinx-datetime
            Multiplatform date/time types: `Instant`, `Clock`, `LocalDate`, `LocalDateTime`,
            `TimeZone`. Prefer over `java.util.Date`/`Calendar` and `java.time` for multiplatform code.
            
            ## Example
            ```kotlin
            import kotlinx.datetime.*
            val now: Instant = Clock.System.now()
            val today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
            ```
        """.trimIndent(),
        "ktor" to """
            # Ktor (io.ktor 3.x)
            Asynchronous server and client framework. Server plugins (ContentNegotiation,
            Routing, StatusPages) install on the server; the client configures its own
            `ContentNegotiation`. Prefer `runTest` over `runBlocking` in Ktor tests.
        """.trimIndent(),
        "turbine" to """
            # Turbine (app.cash.turbine)
            Small testing library for `Flow`: `flowOf(1, 2).test { assertEquals(1, awaitItem()) }`.
            Each emission must be consumed with `awaitItem()`/`awaitError()`/`awaitComplete()`.
        """.trimIndent(),
        "mockk" to """
            # MockK (io.mockk)
            Mocking library for Kotlin. `mockk<T>()` + `every { }` to stub, `verify { }` to
            assert, and `unmockkAll()` in `@AfterEach` to prevent `mockkObject`/`mockkStatic` leaks.
        """.trimIndent(),
        "design patterns" to """
            # GoF Patterns → Idiomatic Kotlin
            Most classic GoF patterns resolve to Kotlin built-ins; prefer the idiom and reach for
            the GoF form only when the idiom lacks needed behavior (documented state in Strategy,
            user-pluggable handler chains in Chain of Responsibility, extreme Builder flexibility).

            ## Creational
            - Singleton → `object` (lazy, thread-safe, `init` block)
            - Static Factory Method → `companion object` factories (`of`, `from`, `valueOf`) + optional `private constructor`
            - Builder → default + named arguments (avoid the Java-style builder chain)
            - Prototype → `data class` `copy()`
            - Factory/Abstract Factory → `when` over a sealed interface family (smart cast on `is`)

            ## Structural
            - Decorator → interface delegation `class Logging(r: Repo) : Repo by r` (override only the decorated member)
            - Adapter → extension functions (`fun USPlug.toEUPlug()`), not adapter classes
            - Facade → extension function that orchestrates the class family
            - Proxy → `by lazy` (default synchronized; `LazyThreadSafetyMode.PUBLICATION`/`.NONE` for cheaper variants)
            - Bridge → flatten with constructor-composed strategy fields
            - Composite → same-interface nesting + `vararg` secondary constructor

            ## Behavioral
            - Strategy → function reference in a `var` (swap `= Weapons::peashooter` at runtime); interface only if the strategy carries state
            - Command → `typealias Command = () -> Unit` + function-generator closures; undo via `Pair<Command, Command>`
            - Chain of Responsibility → `typealias Handler = (Request) -> Response` + function composition `auth(validation(finalResponse()))`
            - State → sealed classes + exhaustive `when` with `is`; state-holds-logic or context-holds-logic styles
            - Template Method → higher-order function with lambda params; optional hook = nullable default `bossHook: (() -> Unit)?` + `bossHook?.let { it() }`
            - Observer → `mutableMapOf<() -> Unit, () -> Unit>` keyed on the subscriber function itself; publish immutable `data class Message`
            - Visitor → sealed class + `when`/`is` replaces accept() double-dispatch
            - Iterator → `operator fun iterator(): Iterator<T>` makes any class for-eachable

            ## State & Data (Ch9)
            - Prefer sealed classes over enums when the state carries associated data (e.g. `PizzaOrderStatus(orderId)`); enums cannot hold per-instance data
        """.trimIndent(),
        "dsl builders" to """
            # Kotlin DSL Builders
            Build type-safe, readable builders via receiver lambdas and trailing-lambda syntax.

            ## Recipe
            ```kotlin
            class Trip {
                var hotel: String? = null
                fun day(label: String, plan: Day.() -> Unit = {}) = Day(label).apply(plan).also { days += it }
            }
            val trip = Trip().apply {
                hotel = "Ritz"
                day("Mon") { visit("Louvre") }
            }
            ```
            - Receiver lambda `T.() -> Unit` grants `this` = receiver inside the block (used with `apply`/`also`/`build`). To also expose the outer receiver use `this@Outer`.
            - Trailing lambda allows `build { }` call-suffix without parentheses: `Trip().apply { ... }`.
            - `lateinit` (non-null, non-primitive) for values set later; reading before assignment throws `UninitializedPropertyAccessException`.
            - Scope function pick: `apply` = configure + return receiver; `also` = side-effect + return receiver; `run` = compute value from receiver; `let` = transform nullable.
        """.trimIndent(),
        "cooperative cancellation" to """
            # Cooperative Cancellation
            Kotlin cancellation is COOPERATIVE: `cancel()` only takes effect at a suspension point.
            - `delay()`, `yield()`, and suspending I/O all check cancellation; a tight CPU loop that never suspends does NOT stop on cancel.
            - `Thread.sleep()` inside a coroutine blocks and is NOT cancellable — use `delay()` instead (also detects cancellation).
            - Catching `CancellationException` for cleanup is legal, but NEVER swallow it: the coroutine is still cancelled afterwards. Re-throw `throw e` after cleanup, or use a `finally` block for the cleanup without catching.
            - `yield()` is an explicit checkpoint to allow cancellation/progress on a single context.
        """.trimIndent(),
        "structured concurrency" to """
            # Structured Concurrency & Exception Propagation
            Default `coroutineScope { }` semantics:
            - A parent coroutine waits for ALL its children to finish before completing.
            - When ONE child throws, the exception cancels the parent AND every sibling, then propagates unless every child already finished (in that case siblings' exceptions still propagate if any).
            - `launch` failures must not go unhandled; they are delivered via the (uncaught) exception handler of the root Job.
            - `supervisorScope { }` ISOLATES failures: a failing child cancels only itself; siblings and the scope survive. Use it for independent sub-tasks (one failure should not abort the batch).
            - Cancellation of the parent still cascades into a `supervisorScope` — supervision only changes child-failure propagation, not parent cancellation.
        """.trimIndent(),
        "flow backpressure" to """
            # Flow: Cold Semantics & Backpressure Operators
            - `Flow` is COLD: no work happens until collected; each collector triggers a fresh producer run (emissions repeat per subscriber). A `Channel` is a QUEUE: point-to-point delivery where each element is consumed once — multiple consumers compete because `receive()` removes elements. Use `SharedFlow` to broadcast to all active collectors.
            - Default backpressure: the producer suspends (`collect` is suspending) whenever the collector is slower, i.e. emissions are sequential and backpressured naturally.
            - `buffer(capacity)` — decouples producer and consumer into a channel with the given capacity; producer runs AHEAD, queueing up to `capacity` (default 64) items. Unbounded buffering can exhaust memory.
            - `conflate()` — only the LATEST value matters; slow consumer skips intermediate emissions, producer never blocks (capacity 1 with drop-oldest). Use when a slow consumer and outdated intermediate values are acceptable (e.g. UI state tickers).
            - Default errors: exceptions thrown by the producer are delivered to the collector and abort the flow; use `catch`/`retry`/`onEach` for operator-level handling.
        """.trimIndent(),
        "async barrier" to """
            # Barrier / Start-All-Then-Await Pattern
            Launch all N independent coroutines FIRST, then await all results — otherwise they run sequentially.
            ```kotlin
            // (Snippets run inside `coroutineScope { }` — `async` needs a CoroutineScope receiver)
            // WRONG: b only starts AFTER a finishes → serialized (a + b durations)
            val a = async { fetch("a") }.await()
            val b = async { fetch("b") }.await()
            // RIGHT: both start immediately, then we await (max(a, b) total)
            val d1 = async { fetch("a") }; val d2 = async { fetch("b") }
            val a = d1.await(); val b = d2.await()
            // Homogeneous: just use awaitAll()
            val results = (1..3).map { async { repo.fetch(it) } }.awaitAll()
            ```
            Rule: gather all `async` handles before any `await`. For a homogeneous batch that returns the same type, prefer `awaitAll()`.
        """.trimIndent(),
        "select expression" to """
            # `select` is Biased — use `selectUnbiased` for Fairness
            `select { }` suspends until ONE of several clauses is ready. When MULTIPLE clauses are
            ready at the same time, `select` deterministically picks the EARLIEST-listed clause
            (syntax order wins). This is the "bias": channel order decides ties, NOT randomness.
            `selectUnbiased { }` picks uniformly at random among simultaneously-ready clauses.
            Use `selectUnbiased` when you need fair load distribution across ready channels, and
            plain `select` when you want deterministic tie-breaking (e.g. prefer the first ready
            source). Clauses may have different result types; the block returns the value of the
            chosen clause (use `onAwait`, `onReceive`, `onSend` to register them).
        """.trimIndent(),
        "algebraic data types" to """
            # Algebraic Data Types with Sealed + `Nothing`
            Model sum types as a `sealed interface` plus `data class`/`object` cases. The empty
            or base case uses the `Nothing` covariant sentinel trick:
            ```kotlin
            sealed interface Tree<out T> {
                object Empty : Tree<Nothing>           // assignable to Tree<Int> via covariance
                data class Node<T>(
                    val value: T,
                    val left: Tree<T> = Empty,
                    val right: Tree<T> = Empty,
                ) : Tree<T>
            }
            fun <T> Tree<T>.depth(): Int = when (this) {
                Tree.Empty -> 0
                is Tree.Node -> 1 + maxOf(left.depth(), right.depth())
            }
            ```
            - `Nothing` is a subtype of EVERY type, so `Empty : Tree<Nothing>` works as `Tree<Int>` because of `out T`.
            - `Any` is the TOP type and is the WRONG choice here.
            - Sealed → exhaustive `when`, no `else` branch required (compiler proves coverage).
            - This is the idiomatic Kotlin equivalent of Haskell/OCaml ADTs (Maybe/Option, Tree, List).
        """.trimIndent(),
        "input validation" to """
            # Input Validation: `require` vs `check`
            The two stdlib validation functions differ by the exception they throw, chosen by WHERE the mistake lives.
            - `require(value, { msg })` → `IllegalArgumentException` — BAD INPUT / precondition: caller passed an invalid argument.
            - `check(value, { msg })` → `IllegalStateException` — BAD STATE / postcondition: the object is in an unusable internal state.
            - `requireNotNull(value)` / `checkNotNull(value)` are the nullable variants (same split).
            - Both evaluate the message lambda LAZILY (only on failure): `{ "arg must be >= 0, was ${'$'}x" }`.
            - Prefer these over hand-rolled `if (x < 0) throw IllegalArgumentException(...)` — they read as intent and never forget the message.
            Convention: validate function inputs with `require` at the top ("fail fast" on bad parameters); use `check` inside functions for internal invariants after state transitions.
        """.trimIndent(),
        "serializable data classes" to """
            # `@Serializable` DTOs (kotlinx.serialization)
            Data classes passed through a serializer (Ktor `call.respond(dto)`, `call.receive<Dto>()`,
            `Json.encodeToString`, request/response bodies) need ALL THREE, or you hit the cryptic
            runtime error "Serializer for class 'X' is not found":
            1. `@Serializable` annotation on the DTO.
            2. The Gradle compiler plugin `kotlin("plugin.serialization")` (same version as the kotlin JVM plugin) — it generates the serializer.
            3. The runtime artifact dependency (e.g. `kotlinx-serialization-json`).
            Missing just the plugin surfaces as a RUNTIME `SerializationException` ("Serializer for
            class not found") instead of a compile error, so it is easy to misdiagnose.
        """.trimIndent(),
        "junit kotlin lifecycle" to """
            # JUnit 5 Lifecycle in Kotlin: @BeforeAll / @AfterAll
            `@BeforeAll`/`@AfterAll` require static methods in Java, but Kotlin has no `static`.
            A naive `@BeforeAll fun setup()` in a test class throws a JUnit Jupiter configuration
            error at runtime ("must be static unless the class uses PER_CLASS lifecycle") — it is
            NOT silently ignored, and the failure is easy to misdiagnose.
            Two working forms (pick ONE):
            ```kotlin
            // A) PER_CLASS lifecycle → instance methods run once each way
            @TestInstance(TestInstance.Lifecycle.PER_CLASS)
            class ServerTest {
                @BeforeAll fun setup() { /* once */ }
                @AfterAll fun cleanup() { /* once */ }
            }
            // B) companion object + @JvmStatic → static methods exposed to JUnit
            class ServerTest {
                companion object {
                    @JvmStatic @BeforeAll fun setup() { }
                    @JvmStatic @AfterAll fun cleanup() { }
                }
            }
            ```
            - `@BeforeEach`/`@AfterEach` work fine as instance methods under both lifecycles.
            - `@TestInstance(PER_CLASS)` + `@Nested` inner classes share one instance; keep per-test
              state in `@BeforeEach`/`@AfterEach` nested setters to avoid cross-test coupling.
        """.trimIndent()
    ))

    private val namespaceDatabase: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    private val docCache: ConcurrentHashMap<String, String?> = ConcurrentHashMap()

    override fun registerDynamicSymbol(symbol: String, content: String) {
        val key = symbol.trim()
        if (key.isBlank()) return
        symbolDatabase[key] = content
        docCache.clear()
        persist()
    }

    override fun registerDynamicFeature(feature: String, content: String) {
        val key = feature.trim()
        if (key.isBlank()) return
        featureDatabase[key] = content
        docCache.clear()
        persist()
    }

    override fun registerDynamicNamespace(namespace: String, content: String) {
        val key = namespace.trim()
        if (key.isBlank()) return
        namespaceDatabase[key] = content
        persist()
    }

    override val namespaces: Map<String, String>
        get() = namespaceDatabase

    private val persistenceFile: File? = runCatching {
        val dir = if (persistencePath != null) {
            File(persistencePath).parentFile
        } else {
            File(System.getProperty("user.home"), ".kotlin-mcp")
        }
        dir?.mkdirs()
        if (persistencePath != null) File(persistencePath) else File(dir, "registered-docs.json")
    }.getOrNull()

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    init {
        seedFromSyncedIndex()
        loadPersisted()
    }

    private fun seedFromSyncedIndex() {
        val stream = runCatching {
            DefaultDocService::class.java.classLoader.getResourceAsStream("stdlib-index.json")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("stdlib-index.json")
        }.getOrNull() ?: return

        stream.use { s ->
            runCatching {
                val element = json.parseToJsonElement(s.readBytes().toString(Charsets.UTF_8))
                val array = element as? kotlinx.serialization.json.JsonArray ?: return
                array.forEach { item ->
                    val obj = item as? kotlinx.serialization.json.JsonObject ?: return@forEach
                    val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim() ?: ""
                    val summary = (obj["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim() ?: ""
                    if (name.isNotBlank() && !symbolDatabase.containsKey(name)) {
                        symbolDatabase[name] = "# `$name`\n$summary"
                    }
                }
            }
        }
        docCache.clear()
    }

    private fun persist() {
        val file = persistenceFile ?: return
        runCatching {
            val jsonObject = kotlinx.serialization.json.buildJsonObject {
                put("version", kotlinx.serialization.json.JsonPrimitive(1))
                put("symbols", kotlinx.serialization.json.buildJsonObject {
                    symbolDatabase.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                })
                put("features", kotlinx.serialization.json.buildJsonObject {
                    featureDatabase.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                })
                put("namespaces", kotlinx.serialization.json.buildJsonObject {
                    namespaceDatabase.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                })
            }
            file.parentFile?.mkdirs()
            val tempFile = File.createTempFile("registered-docs", ".tmp", file.parentFile ?: File("."))
            tempFile.writeText(jsonObject.toString())
            java.nio.file.Files.move(
                tempFile.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        }
    }

    private fun loadPersisted() {
        val file = persistenceFile ?: return
        if (!file.exists()) return
        runCatching {
            val element = json.parseToJsonElement(file.readText())
            val root = element as? kotlinx.serialization.json.JsonObject ?: return
            root["symbols"]?.let { symObj ->
                (symObj as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) ->
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { symbolDatabase[k] = it }
                }
            }
            root["features"]?.let { featObj ->
                (featObj as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) ->
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { featureDatabase[k] = it }
                }
            }
            root["namespaces"]?.let { nsObj ->
                (nsObj as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) ->
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { namespaceDatabase[k] = it }
                }
            }
            docCache.clear()
        }
    }

    override val symbolDocs: Map<String, String>
        get() = symbolDatabase

    override val featureDocs: Map<String, String>
        get() = featureDatabase

    override fun docFor(kind: String, name: String): String? {
        val cacheKey = "$kind:$name"
        return docCache.computeIfAbsent(cacheKey) {
            val decoded = try {
                URLDecoder.decode(name, Charsets.UTF_8.name())
            } catch (e: Exception) {
                name
            }
            val db = when (kind.lowercase()) {
                "symbol" -> symbolDatabase
                "feature" -> featureDatabase
                else -> return@computeIfAbsent null
            }
            db[decoded]
                ?: db[decoded.replace("_", " ")]
                ?: db.entries.firstOrNull { it.key.equals(decoded, ignoreCase = true) }?.value
        }
    }

    override fun execute(action: DocAction, query: String, preset: String?, classpath: List<String>): KotlinMcpResult {
        return when (action) {
            DocAction.SEARCH -> searchDocs(query, classpath)
            DocAction.LOOKUP_SYMBOL -> lookupSymbol(query, preset, classpath)
            DocAction.EXPLAIN_FEATURE -> explainFeature(query)
        }
    }

    private fun applies(tags: List<String>, classpath: List<String>): Boolean {
        if (tags.isEmpty()) return true
        val cp = classpath
        if (cp.isEmpty()) return true
        return tags.any { tag -> libraryPresent(tag, cp) }
    }

    private fun libraryPresent(tag: String, cp: List<String>): Boolean {
        val base = tag.substringBefore(":").lowercase()
        val cpLower = cp.map { it.lowercase() }
        return cpLower.any { entry ->
            val name = entry.substringAfterLast('/').substringAfterLast('\\')
            name.contains(base) || entry.contains(base)
        }
    }

    override fun searchDocs(query: String, classpath: List<String>): KotlinMcpResult {
        val q = query.lowercase().trim()
        val symbolMatches = symbolDatabase.keys
            .filter { it.lowercase().contains(q) }
            .filter { applies(symbolAppliesToMap[it].orEmpty(), classpath) }
            .map { "symbol" to it }
        val featureMatches = featureDatabase.keys
            .filter { it.lowercase().contains(q) }
            .filter { applies(featureAppliesToMap[it].orEmpty(), classpath) }
            .map { "feature" to it }
        val allMatches = symbolMatches + featureMatches
        val content = if (allMatches.isNotEmpty()) {
            val toon = ToonUtils.encodeToonTable(
                headerName = "search_matches",
                columns = listOf("kind", "name", "uri"),
                items = allMatches
            ) { (kind, name) ->
                val encoded = java.net.URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")
                listOf(kind, name, "kotlin://docs/$kind/$encoded")
            }
            "Found ${allMatches.size} documentation match(es) for '$query':\n$toon"
        } else {
            "No documentation entries matched query '$query'. Available topics include: ${featureDatabase.keys.joinToString()}"
        }
        return KotlinMcpResult.Success(content = content, metadata = mapOf("query" to query, "matchCount" to allMatches.size.toString()))
    }

    override fun lookupSymbol(query: String, preset: String?, classpath: List<String>): KotlinMcpResult {
        val q = query.trim()
        val direct = symbolDatabase[q]?.let { q to it }
        val caseInsensitive = symbolDatabase.entries.firstOrNull { it.key.equals(q, ignoreCase = true) }?.let { it.key to it.value }
        val shortName = symbolDatabase.entries.firstOrNull { it.key.substringAfterLast('.').equals(q, ignoreCase = true) }?.let { it.key to it.value }
        val namespaceHit = namespaceMatch(q)
        val candidate = direct ?: caseInsensitive ?: shortName
        val entry = if (candidate != null && applies(symbolAppliesToMap[candidate.first].orEmpty(), classpath)) {
            candidate.second
        } else if (candidate == null && namespaceHit != null) {
            namespaceHit
        } else {
            null
        }
        return if (entry != null) {
            val content = if (preset == "compact") {
                entry.lines().firstOrNull { it.isNotBlank() } ?: entry
            } else {
                entry
            }
            KotlinMcpResult.Success(content = content, metadata = mapOf("symbol" to q, "preset" to (preset ?: "full")))
        } else {
            val filteredOut = (direct ?: caseInsensitive ?: shortName)?.first
            KotlinMcpResult.Error(
                message = "Symbol '$query' not found in documentation index." +
                    (if (filteredOut != null) " (Entry exists but is filtered out because the library is not on the caller's classpath.)" else ""),
                code = "SYMBOL_NOT_FOUND",
                details = mapOf("query" to q)
            )
        }
    }

    private fun namespaceMatch(q: String): String? {
        val registered = namespaceDatabase.entries
            .filter { it.key.isNotBlank() && (q.startsWith(it.key) || it.key.startsWith(q) || q.contains(it.key)) }
        if (registered.isEmpty()) return null
        val (prefix, content) = registered.maxBy { it.key.length }
        val remainder = q.removePrefix(prefix).trim().trim('.')
        return if (remainder.isBlank()) {
            content
        } else {
            "$content\n\n(Symbol '$q' falls under registered namespace '$prefix'; remainder: '$remainder'.)"
        }
    }

    override fun explainFeature(query: String): KotlinMcpResult {
        val key = query.lowercase().trim()
        val entry = featureDatabase[key]
            ?: featureDatabase.entries.firstOrNull { it.key.equals(key) }?.value
            ?: featureDatabase.entries.firstOrNull { it.key.contains(key) || key.contains(it.key) }?.value
        return if (entry != null) {
            KotlinMcpResult.Success(content = entry, metadata = mapOf("feature" to query))
        } else {
            KotlinMcpResult.Error(
                message = "Language feature '$query' not found. Available features: ${featureDatabase.keys.joinToString()}",
                code = "FEATURE_NOT_FOUND",
                details = mapOf("availableFeatures" to featureDatabase.keys.joinToString())
            )
        }
    }
}
