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
        "runTest" to """
            # `fun runTest(context: CoroutineContext = EmptyCoroutineContext, timeout: Duration = 60.seconds, testBody: suspend TestScope.() -> Unit): TestResult`
            Executes a coroutine test block with virtualized time skipping. Standard entry point for coroutines testing.
        """.trimIndent(),
        "MainDispatcherRule" to """
            # `class MainDispatcherRule(val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher()`
            JUnit4 / JUnit5 rule that overrides `Dispatchers.Main` with a test dispatcher during test execution.
        """.trimIndent(),
        "Turbine.test" to """
            # `suspend fun <T> Flow<T>.test(timeout: Duration? = null, validate: suspend ReceiveTurbine<T>.() -> Unit)`
            AppCash Turbine extension function on `Flow<T>` for testing emissions sequentially: `awaitItem()`, `awaitComplete()`, `awaitError()`.
        """.trimIndent(),
        "mockk" to """
            # `inline fun <reified T : Any> mockk(name: String? = null, relaxed: Boolean = false, vararg relaxUnitFun: Boolean, block: T.() -> Unit = {}): T`
            Creates a mock object for type T using MockK.
        """.trimIndent(),
        "every" to """
            # `fun <T> every(stubBlock: MockKMatcherScope.() -> T): MockKStubScope<T, T>`
            Stubs a method call behavior in MockK. Pair with `returns` or `answers`.
        """.trimIndent(),
        "verify" to """
            # `fun verify(ordering: Ordering = Ordering.UNORDERED, exactly: Int = -1, atLeast: Int = -1, atMost: Int = -1, verifyBlock: MockKVerificationScope.() -> Unit)`
            Verifies that stubbed calls occurred on a mock object.
        """.trimIndent(),
        "Ktor/Routing" to """
            # `fun Application.routing(configuration: Routing.() -> Unit): Routing`
            Defines the HTTP routing tree for a Ktor Application using verb builders (`get`, `post`, `put`, `delete`).
        """.trimIndent(),
        "Ktor/ContentNegotiation" to """
            # `fun Application.install(plugin: ContentNegotiation, configure: ContentNegotiation.Config.() -> Unit)`
            Ktor plugin for automatic JSON request/response serialization/deserialization.
        """.trimIndent(),
        "Either" to """
            # `sealed class Either<out A, out B>`
            Arrow functional data type representing a value of one of two possible types: `Either.Left(A)` for failure or `Either.Right(B)` for success.
        """.trimIndent(),
        "Raise" to """
            # `interface Raise<in E>`
            Arrow 2.x DSL for short-circuiting error computation without exception throwing using `raise(e)`.
        """.trimIndent(),
        "valid" to """
            # `fun <A> A.valid(): Validated<Nothing, A>`
            Creates a Validated.Valid instance wrapping value A.
        """.trimIndent(),
        "validNel" to """
            # `fun <E, A> A.validNel(): ValidatedNel<E, A>`
            Creates a Validated instance wrapping A as Valid with an empty NonEmptyList of errors.
        """.trimIndent(),
        "kotlinx.datetime.Instant" to """
            # `class Instant`
            Represents a moment on the UTC time line in `kotlinx-datetime`.
        """.trimIndent(),
        "kotlinx.datetime.Clock" to """
            # `interface Clock`
            Provider for current instant: `Clock.System.now()`.
        """.trimIndent(),
        "kotlinx.datetime.LocalDate" to """
            # `class LocalDate`
            Civil date (year, month, day) without time or time-zone in `kotlinx-datetime`.
        """.trimIndent(),
        "kotlinx.serialization.json.Json" to """
            # `sealed class Json`
            Main entry point for Kotlin serialization JSON operations: `Json.encodeToString(...)` and `Json.decodeFromString(...)`.
        """.trimIndent(),
        "readText" to """
            # `fun File.readText(charset: Charset = Charsets.UTF_8): String`
            Reads the entire content of a file as a String.
        """.trimIndent()
    )
}
