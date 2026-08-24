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

    private val symbolDatabase: ConcurrentHashMap<String, String> = ConcurrentHashMap(mapOf(
        "kotlin.collections.List" to """
            # `interface List<out E> : Collection<E>`
            A generic ordered collection of elements. Methods in this interface support only read-only access to the list.
            
            ## Key Functions
            - `get(index: Int): E`
            - `indexOf(element: E): Int`
            - `subList(fromIndex: Int, toIndex: Int): List<E>`
        """.trimIndent(),
        "kotlin.collections.MutableList" to """
            # `interface MutableList<E> : List<E>, MutableCollection<E>`
            A generic ordered collection that supports element addition and removal.
            
            ## Key Functions
            - `add(element: E): Boolean`
            - `remove(element: E): Boolean`
            - `set(index: Int, element: E): E`
        """.trimIndent(),
        "kotlin.collections.Map" to """
            # `interface Map<K, out V>`
            A collection that holds pairs of objects (keys and values) and supports efficiently retrieving the value corresponding to each key.
            
            ## Key Functions
            - `get(key: K): V?`
            - `getOrDefault(key: K, defaultValue: V): V`
            - `keys`, `values`, `entries`
        """.trimIndent(),
        "kotlin.Result" to """
            # `value class Result<out T>`
            A discriminated union that encapsulates a successful outcome with a value of type T or a failure with an arbitrary Throwable exception.
            
            ## Usage
            Prefer `Result` for explicit error handling in functional Kotlin code instead of throwing exceptions.
        """.trimIndent(),
        "kotlinx.coroutines.Flow" to """
            # `interface Flow<out T>`
            A cold asynchronous data stream that sequentially emits values and completes normally or with an exception.
            
            ## Cold semantics
            A Flow starts producing values only when collected; it is reusable and cancelable.
        """.trimIndent(),
        "mapNotNull" to """
            # `inline fun <T, R : Any> Iterable<T>.mapNotNull(transform: (T) -> R?): List<R>`
            Returns a list containing the results of applying the given transform to each element, omitting null results.
            
            ## Example
            ```kotlin
            val strings = listOf("1", "x", "3")
            strings.mapNotNull { it.toIntOrNull() } // [1, 3]
            ```
        """.trimIndent(),
        "map" to """
            # `inline fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R>`
            Returns a list containing the results of applying the transform function to each element.
        """.trimIndent(),
        "filter" to """
            # `inline fun <T> Iterable<T>.filter(predicate: (T) -> Boolean): List<T>`
            Returns a list containing only elements matching the given predicate.
        """.trimIndent(),
        "flatMap" to """
            # `inline fun <T, R> Iterable<T>.flatMap(transform: (T) -> Iterable<R>): List<R>`
            Returns a single list of all elements yielded from results of the transform applied to each element.
        """.trimIndent(),
        "fold" to """
            # `inline fun <T, R> Iterable<T>.fold(initial: R, operation: (acc: R, T) -> R): R`
            Accumulates value starting with initial and applying operation to each element.
        """.trimIndent(),
        "sum" to """
            # `fun Iterable<Int>.sum(): Int`
            Returns the sum of all elements in the collection.
        """.trimIndent(),
        "sumOf" to """
            # `inline fun <T> Iterable<T>.sumOf(selector: (T) -> Int): Int`
            Returns the sum of values produced by the selector function applied to each element.
        """.trimIndent(),
        "takeIf" to """
            # `inline fun <T> T.takeIf(predicate: (T) -> Boolean): T?`
            Returns this value if it satisfies the predicate, or null otherwise.
        """.trimIndent(),
        "runCatching" to """
            # `inline fun <R> runCatching(block: () -> R): Result<R>`
            Calls the specified function and returns its result wrapped in Result, or captures any thrown exception as a failure.
        """.trimIndent(),
        "buildList" to """
            # `inline fun <E> buildList(builderAction: MutableList<E>.() -> Unit): List<E>`
            Builds a list by populating a MutableList via the receiver lambda, returning an immutable read-only list.
        """.trimIndent(),
        "CoroutineScope" to """
            # `interface CoroutineScope`
            Defines a scope for new coroutines. Every coroutine builder (launch, async) is an extension on CoroutineScope.
            
            ## Key Members
            - `val coroutineContext: CoroutineContext`
            - Extension: `launch { }`, `async { }`
            
            Prefer structured concurrency: pass an explicit scope rather than using GlobalScope.
        """.trimIndent(),
        "runBlocking" to """
            # `fun <T> runBlocking(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> T): T`
            Runs a new coroutine and blocks the current thread until it completes. Intended for main functions and tests, NOT for production suspension chains.
        """.trimIndent(),
        "launch" to """
            # `fun CoroutineScope.launch(context, start, block: suspend CoroutineScope.() -> Unit): Job`
            Launches a new coroutine without blocking the current thread. Returns a Job that completes when the coroutine completes.
        """.trimIndent(),
        "async" to """
            # `fun CoroutineScope.async(context, start, block: suspend CoroutineScope.() -> T): Deferred<T>`
            Creates a coroutine that returns a Deferred — a future result that can be awaited with `await()`.
        """.trimIndent(),
        "delay" to """
            # `suspend fun delay(timeMillis: Long)`
            Suspends the current coroutine for the given time without blocking a thread.
        """.trimIndent(),
        "withContext" to """
            # `suspend fun <T> withContext(context: CoroutineContext, block: suspend CoroutineScope.() -> T): T`
            Calls the block with a new coroutine context, suspending until it completes. Commonly used to switch dispatchers (e.g. Dispatchers.IO).
        """.trimIndent(),
        "withTimeout" to """
            # `suspend fun <T> withTimeout(timeMillis: Long, block: suspend CoroutineScope.() -> T): T`
            Runs the block, throwing TimeoutCancellationException if it does not complete within the given time.
        """.trimIndent(),
        "collect" to """
            # `suspend fun <T> Flow<T>.collect(action: suspend (T) -> Unit)`
            Terminal operator that collects values from the flow, executing action for each emitted value.
        """.trimIndent(),
        "kotlinx.serialization.json.Json" to """
            # `object Json : StringFormat`
            The entry point for JSON (de)serialization with kotlinx.serialization.
            
            ## Usage
            ```kotlin
            val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
            val text = json.encodeToString(user)
            val user = json.decodeFromString<User>(text)
            ```
        """.trimIndent(),
        "encodeToString" to """
            # `inline fun <reified T> StringFormat.encodeToString(value: T): String`
            Serializes a value to a string (JSON etc.). Requires the type to be `@Serializable`.
        """.trimIndent(),
        "decodeFromString" to """
            # `inline fun <reified T> StringFormat.decodeFromString(string: String): T`
            Deserializes a string into a value of type T. Throws SerializationException on malformed input.
        """.trimIndent(),
        "Json.encodeToList" to """
            # `fun <T> Json.encodeToList(value: T, serializer: KSerializer<T>): List<JsonElement>`
            Serializes a value into a JSON array of elements (extension in kotlinx.serialization.json).
            Useful for streaming large collections without materializing the whole string.
        """.trimIndent(),
        "Either" to """
            # `sealed interface Either<out L, out R>` (arrow.core, Arrow 2.x)
            A right-biased discriminated union representing success (`Right`) or failure (`Left`).
            
            ## Usage
            ```kotlin
            import arrow.core.Either
            import arrow.core.right
            fun parse(s: String): Either<Throwable, Int> = Either.catch { s.toInt() }
            val r: Either<Throwable, Int> = parse("42")
            r.fold({ err -> println("failed: ${'$'}err") }, { v -> println(v) })
            ```
        """.trimIndent(),
        "Raise" to """
            # `interface Raise<E>` (arrow.core.raise, Arrow 2.x)
            The Arrow Raise context: a function `context(Raise<E>)` can short-circuit with `raise(e)`.
            Build Either/Result via `either { ... }` or `result { ... }`.
            
            ## Usage
            ```kotlin
            import arrow.core.raise.either
            import arrow.core.raise.ensure
            val x: Either<String, Int> = either {
                val n = 3
                ensure(n > 0) { "must be positive" }
                n * 2
            }
            ```
        """.trimIndent(),
        "valid" to """
            # `fun <A> A.valid(): Validated<Nothing, A>` (arrow.core, Arrow 2.x)
            Wraps a value as a `Validated` success. Combine with `zip` for all-errors accumulation.
        """.trimIndent(),
        "validNel" to """
            # `fun <A> A.validNel(): ValidatedNel<Nothing, A>` (arrow.core, Arrow 2.x)
            Wraps a value as a `Validated<NonEmptyList<E>, A>` success; pairs with `invalidNel` to
            accumulate multiple errors into a `NonEmptyList`.
        """.trimIndent(),
        "kotlinx.datetime.Instant" to """
            # `class Instant` (kotlinx.datetime)
            A moment on the UTC time line, independent of time zone. Nanosecond precision.
            
            ## Usage
            ```kotlin
            import kotlinx.datetime.Instant
            import kotlinx.datetime.Clock
            val now: Instant = Clock.System.now()
            ```
        """.trimIndent(),
        "kotlinx.datetime.Clock" to """
            # `interface Clock` (kotlinx.datetime)
            Provides the current `Instant`. `Clock.System.now()` is the platform clock.
            Prefer over `java.util.Date`/`System.currentTimeMillis()` for explicit, type-safe time.
        """.trimIndent(),
        "kotlinx.datetime.LocalDate" to """
            # `class LocalDate(year, month, day)` (kotlinx.datetime)
            A date without a time zone. Obtain today's date with
            `Clock.System.todayIn(TimeZone.currentSystemDefault())`.
        """.trimIndent(),
        "runTest" to """
            # `fun runTest(context: CoroutineContext = ..., block: suspend TestScope.() -> Unit)` (kotlinx-coroutines-test)
            Runs a test coroutine with virtual time; delays advance instantly and
            `StandardTestDispatcher` is used by default. Prefer over `runBlocking` in tests.
        """.trimIndent(),
        "MainDispatcherRule" to """
            # `class MainDispatcherRule` (kotlinx-coroutines-test test helper)
            A JUnit rule that swaps `Dispatchers.Main` for a `StandardTestDispatcher` via
            `Dispatchers.setMain` / `resetMain`, making Main deterministic in unit tests.
            
            ## Usage
            ```kotlin
            class MyTest {
                @get:Rule
                val mainDispatcherRule = MainDispatcherRule()
            }
            ```
        """.trimIndent(),
        "Turbine.test" to """
            # `suspend fun <T> Flow<T>.test(block: suspend Turbine<T>.() -> Unit)` (app.cash.turbine)
            Collects a Flow in a test scope and exposes `awaitItem()`, `awaitError()`,
            `awaitComplete()` to assert each emission. Unconsumed emissions are discarded.
        """.trimIndent(),
        "mockk" to """
            # `mockk<T>()` (io.mockk)
            Creates a MockK mock of type T. Stub behavior with `every { }`, assert interactions
            with `verify { }`, and reset global mocks with `unmockkAll()` in `@AfterEach`.
        """.trimIndent(),
        "every" to """
            # `every { ... } returns value` (io.mockk)
            Stubs behavior for a mock: `every { repo.fetch() } returns 42`. Use `verify { }`
            to assert the call actually happened.
        """.trimIndent(),
        "verify" to """
            # `verify { ... }` / `verify(exactly = n) { ... }` (io.mockk)
            Asserts a stubbed mock interaction occurred. Pair `every` stubs with `verify` and
            call `confirmVerified(mock)` to ensure no unexpected calls remain.
        """.trimIndent(),
        "Ktor/Routing" to """
            # `fun Application.module() { routing { ... } }` (io.ktor.server.routing)
            The Ktor routing DSL declares HTTP routes. Install plugins on the server with
            `install(ContentNegotiation) { json() }` before routing so DTO serialization works.
        """.trimIndent(),
        "Ktor/ContentNegotiation" to """
            # `install(ContentNegotiation) { json() }` (io.ktor.server.contentnegotiation)
            Registers the JSON (de)serializer for @Serializable request/response bodies.
            Missing this plugin is a common cause of `SerializationException` or raw-string bodies.
        """.trimIndent(),
        "File" to """
            # `class File(path: String)` (java.io)
            Represents a file/directory path. Kotlin adds ergonomic extensions.
            
            ## Key Extensions
            - `readText(): String`, `writeText(text: String)`
            - `readLines(): List<String>`, `forEachLine { }`
            - `exists()`, `isFile`, `isDirectory`, `listFiles()`
        """.trimIndent(),
        "Path" to """
            # `interface Path` (java.nio.file)
            Modern NIO file path. Prefer over java.io.File for new code.
            
            ## Key APIs
            - `Files.readString(path)`, `Files.writeString(path, content)`
            - `path.resolve("child")`, `Paths.get("a", "b")`
            - `file.use { }` via `File.inputStream()` for auto-closing
        """.trimIndent(),
        "readText" to """
            # `fun File.readText(charset: Charset = Charsets.UTF_8): String`
            Reads the entire contents of a file into a string.
        """.trimIndent(),
        "Regex" to """
            # `class Regex(pattern: String)`
            Regular-expression support.
            
            ## Usage
            ```kotlin
            val re = Regex("\\d+")
            re.find("abc123")?.value        // "123"
            re.findAll("a1b22").map { it.value } // ["1","22"]
            "a1b2".replace(Regex("\\d"), "#")    // "a#b#"
            ```
        """.trimIndent(),
        "buildString" to """
            # `inline fun buildString(builderAction: StringBuilder.() -> Unit): String`
            Builds a string via a StringBuilder receiver lambda.
            
            ## Example
            ```kotlin
            val s = buildString {
                appendLine("header")
                items.forEach { appendLine("- ${'$'}it") }
            }
            ```
        """.trimIndent(),
        "@JvmStatic" to """
            # `@JvmStatic` (kotlin.jvm)
            Marks a member of a companion object/object to be compiled as a real static method for Java interop.
        """.trimIndent(),
        "@JvmField" to """
            # `@JvmField` (kotlin.jvm)
            Exposes a Kotlin property as a plain Java field (no getter/setter) for interop.
        """.trimIndent(),
        "assertEquals" to """
            # `fun <T> assertEquals(expected: T, actual: T, message: String? = null)` (kotlin.test)
            Asserts two values are equal; fails the test otherwise. Use `import kotlin.test.assertEquals`.
        """.trimIndent(),
        "@Test" to """
            # `@Test` (kotlin.test / org.junit)
            Marks a function as a test case. On JVM this maps to JUnit's `@Test`.
        """.trimIndent(),
        "kotlin.Nothing" to """
            # `class Nothing` (kotlin)
            The bottom type in Kotlin. `Nothing` is a subtype of EVERY type, so a
            value of type `Nothing` can be used anywhere. Combined with a covariant
            (`out T`) generic, an `object Empty : Tree<Nothing>` becomes assignable to
            `Tree<Int>`. Use it (never `Any?`) for the empty/base case of algebraic data types.
        """.trimIndent(),
        "tailrec" to """
            # `tailrec` (modifier)
            Asks the compiler to replace a self-recursive call with a loop, preventing stack
            overflow. Constraint: every self-recursive call must be the FINAL operation on its
            execution path (tail position). Multiple branches (e.g. if/else) may each recurse,
            as long as each call is the last thing done on its path — there is no "one call only"
            limit. If NO call is in tail position the compiler warns "a function is marked as
            tail-recursive but no tail calls are found" and the keyword is silently ignored.
            Tail-recursive functions cannot be `open`/`override` on JVM.
        """.trimIndent(),
        "require" to """
            # `inline fun require(value: Boolean, lazyMessage: () -> Any = {...})`
            Validates a PRECONDITION / input argument; throws `IllegalArgumentException` when
            `value` is false. Prefer over hand-rolled `if (x < 0) throw IllegalArgumentException()`.
            The message lambda is only evaluated on failure.
        """.trimIndent(),
        "check" to """
            # `inline fun check(value: Boolean, lazyMessage: () -> Any = {...})`
            Validates a POSTCONDITION / internal invariant or object state; throws
            `IllegalStateException` when `value` is false. Use for "this object is in a bad state",
            NOT for bad caller input (that is `require`). Message lambda evaluated only on failure.
        """.trimIndent(),
        "requireNotNull" to """
            # `inline fun <T : Any> requireNotNull(value: T?, lazyMessage: () -> Any = {...}): T`
            Returns `value` after asserting it is non-null; throws `IllegalArgumentException`
            otherwise. Same input-contract role as `require`, for nullables.
        """.trimIndent(),
        "checkNotNull" to """
            # `inline fun <T : Any> checkNotNull(value: T?, lazyMessage: () -> Any = {...}): T`
            Returns `value` after asserting it is non-null; throws `IllegalStateException`
            otherwise. Same state-contract role as `check`, for nullables.
        """.trimIndent(),
        "supervisorScope" to """
            # `suspend fun <R> supervisorScope(block: suspend CoroutineScope.() -> R): R`
            Creates a scope whose failure of one child does NOT cancel siblings or the scope.
            Default structured concurrency cancels siblings on any child failure; wrap work that
            must be failure-isolated in `supervisorScope`. Does NOT change cancellation of the
            scope itself by its parent.
        """.trimIndent(),
        "select" to """
            # `select { }` (kotlinx.coroutines.selects)
            Suspends until one of several clauses completes, then resumes once. BIASED: when
            several clauses are ready simultaneously, the EARLIEST-listed clause wins — the choice
            is NOT random. `selectUnbiased { }` randomizes the winner among ready clauses.
        """.trimIndent(),
        "selectUnbiased" to """
            # `selectUnbiased { }` (kotlinx.coroutines.selects)
            Like `select { }` but chooses uniformly at random among simultaneously-ready clauses
            instead of always preferring the earliest listed one. Use when fairness is required.
        """.trimIndent(),
        "@BeforeAll" to """
            # `@BeforeAll` / `@AfterAll` (org.junit.jupiter)
            Run once before/after ALL tests in a class. These require STATIC methods in Java;
            Kotlin has no `static`, so a plain instance `@BeforeAll fun setup()` throws a JUnit
            Jupiter configuration error at runtime ("must be static unless ... PER_CLASS") — it is
            NOT silently skipped. Fix: annotate the test class `@TestInstance(TestInstance
            .Lifecycle.PER_CLASS)` (instance methods then run), or put the functions in a
            `companion object` with `@JvmStatic`. `@BeforeEach`/`@AfterEach` are unaffected.
        """.trimIndent(),
        "awaitAll" to """
            # `suspend fun <T> Iterable<Deferred<T>>.awaitAll(): List<T>`
            Awaits every Deferred, collecting results in order. Prefer `listOf(a, b, c).awaitAll()`
            over sequential `val a = x.async().await(); val b = y.async().await()` — the latter
            SERIALIZES the two coroutines (launch all first, then await all).
        """.trimIndent()
    ))

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
