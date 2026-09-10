package com.gokorei.kotlinmcp.doc

import com.gokorei.kotlinmcp.models.FrameworkFeature

/**
 * Static catalog holding stdlib and framework symbol documentation entries.
 */
object StdlibSymbolCatalog {

    val symbolAppliesTo: Map<String, FrameworkFeature> = mapOf(
        "kotlinx.datetime.Instant" to FrameworkFeature.DATETIME,
        "kotlinx.datetime.Clock" to FrameworkFeature.DATETIME,
        "kotlinx.datetime.LocalDate" to FrameworkFeature.DATETIME,
        "runTest" to FrameworkFeature.COROUTINES,
        "MainDispatcherRule" to FrameworkFeature.COROUTINES,
        "Turbine.test" to FrameworkFeature.TURBINE,
        "mockk" to FrameworkFeature.MOCKK,
        "every" to FrameworkFeature.MOCKK,
        "verify" to FrameworkFeature.MOCKK,
        "Ktor/Routing" to FrameworkFeature.KTOR,
        "Ktor/ContentNegotiation" to FrameworkFeature.KTOR,
        "Either" to FrameworkFeature.ARROW,
        "Raise" to FrameworkFeature.ARROW,
        "valid" to FrameworkFeature.ARROW,
        "validNel" to FrameworkFeature.ARROW
    )

    val symbolDocs: Map<String, String> = mapOf(
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
    )
}
