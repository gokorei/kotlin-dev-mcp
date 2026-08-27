package com.gokorei.kotlinmcp.doc

import com.gokorei.kotlinmcp.models.FrameworkFeature

/**
 * Static catalog holding Kotlin language feature and framework concept documentation entries.
 */
object FrameworkFeatureCatalog {

    val featureAppliesTo: Map<FrameworkFeature, List<String>> = mapOf(
        FrameworkFeature.ARROW to listOf("arrow"),
        FrameworkFeature.DATETIME to listOf("kotlinx-datetime"),
        FrameworkFeature.KTOR to listOf("ktor"),
        FrameworkFeature.TURBINE to listOf("turbine"),
        FrameworkFeature.MOCKK to listOf("mockk"),
        FrameworkFeature.ANDROID to listOf("android", "androidx"),
        FrameworkFeature.COMPOSE to listOf("compose"),
        FrameworkFeature.SPRING to listOf("spring", "springframework"),
        FrameworkFeature.EXPOSED to listOf("exposed"),
        FrameworkFeature.ROOM to listOf("room")
    )

    val featureDocs: Map<FrameworkFeature, String> = mapOf(
        FrameworkFeature.ANDROID to """
            # Modern Android Development with Kotlin Guide
            Guidelines for building robust, leak-free Android apps using Jetpack Compose, Lifecycle, and Hilt.

            ## Jetpack Compose Best Practices
            - **Lifecycle-aware State Collection**: Use `flow.collectAsStateWithLifecycle()` from `androidx.lifecycle.compose` instead of `collectAsState()` to automatically pause flow collection when the UI is in the background or stopped.
            - **Type-Safe Navigation (Navigation Compose 2.8+)**: Define destinations using Kotlin `@Serializable` objects/classes instead of string route concatenation. Pass and extract type-safe route arguments directly.
            - **UI State Preservation**: Use `rememberSaveable` for ephemeral UI state (e.g. text field edits, scroll offsets) and inject `SavedStateHandle` into `ViewModel` to survive process death and configuration changes.
            - **Stable Collections**: Avoid standard `List<T>`, `Set<T>`, and `Map<K, V>` in Composable parameters as they are treated as unstable by the Compose compiler. Prefer `kotlinx.collections.immutable.ImmutableList` or wrap in `@Immutable` / `@Stable` data classes.
            - **Modifier Parameter Conventions**: Every custom UI `@Composable` should accept `modifier: Modifier = Modifier` as the first optional parameter and chain it to the root layout element.
            - **Edge-to-Edge Compliance (Android 15+)**: Call `enableEdgeToEdge()` in `Activity.onCreate()` and handle window insets via Compose modifiers (`Modifier.safeDrawingPadding()`, `Modifier.imePadding()`).

            ## Architecture & Concurrency
            - **ViewModel Coroutine Scopes**: Always launch coroutines via `viewModelScope.launch { ... }` so work cancels automatically when the ViewModel clears.
            - **No Leaked UI References**: Never hold direct references to `Activity`, `View`, or UI `Context` inside `ViewModel` or `@Singleton` classes. Use `@ApplicationContext` or state callbacks instead.
            - **Hilt Dependency Injection**: Annotate injected ViewModels with `@HiltViewModel` and Activity/Fragment injection targets with `@AndroidEntryPoint`.

            ## Permissions & Media Selection
            - **PhotoPicker**: For selecting images/videos on Android 13+, prefer `ActivityResultContracts.PickVisualMedia` over requesting full `READ_MEDIA_IMAGES` / storage permissions.
            - **Push Notifications (Android 13+)**: Verify `android.permission.POST_NOTIFICATIONS` runtime permission with rationale dialogs before posting notifications.
        """.trimIndent(),
        FrameworkFeature.COMPOSE to """
            # Jetpack Compose Guide
            Declarative modern UI toolkit for Kotlin applications.
            
            ## State & Recomposition
            - Use `remember { mutableStateOf(...) }` to preserve UI state across recompositions.
            - Provide `modifier: Modifier = Modifier` on reusable custom composables.
        """.trimIndent(),
        FrameworkFeature.SPRING to """
            # Spring Boot with Kotlin Guide
            Building reactive and web services using Spring Framework and Kotlin.
            
            ## Best Practices
            - Leverage constructor injection and Kotlin data classes for DTOs.
            - Use `org.springframework.web.reactive.function.server.coRouter` for coroutine routing.
        """.trimIndent(),
        FrameworkFeature.EXPOSED to """
            # JetBrains Exposed SQL ORM Guide
            Type-safe SQL library for Kotlin.
            
            ## Patterns
            - Declare schema with `object Users : IntIdTable("users")`.
            - Execute queries inside `transaction { ... }` or `suspendTransaction { ... }`.
        """.trimIndent(),
        FrameworkFeature.ROOM to """
            # Android Jetpack Room Guide
            SQLite object mapping library for Android.
            
            ## Coroutines & Flow
            - Declare DAO suspend functions for one-shot reads/writes.
            - Return `Flow<T>` from DAO methods for observable queries.
        """.trimIndent(),
        FrameworkFeature.COROUTINES to """
            # Kotlin Coroutines Guide
            Coroutines provide lightweight cooperative multitasking built directly into Kotlin.
            
            ## Core Concepts
            - `suspend fun`: Functions that can suspend execution without blocking threads.
            - `CoroutineScope`: Defines lifetime boundaries for launched coroutines.
            - `Dispatchers`: Controls thread allocation (`Dispatchers.IO`, `Dispatchers.Default`, `Dispatchers.Main`).
            - `Flow<T>`: Asynchronous cold stream emitting multiple sequential values.
            
            ## Best Practices
            - Avoid `GlobalScope`; inject or inherit `CoroutineScope`.
            - Use `runTest` for unit testing coroutines with virtual time skipping.
        """.trimIndent(),
        FrameworkFeature.SERIALIZATION to """
            # `kotlinx.serialization` Guide
            Multiplatform, compiler-plugin-driven JSON/Protobuf/CBOR serialization for Kotlin data classes.
            
            ## Syntax
            ```kotlin
            @Serializable
            data class User(val id: Int, val name: String)
            ```
        """.trimIndent(),
        FrameworkFeature.ARROW to """
            # Arrow Functional Programming Library
            Arrow brings typed functional programming abstractions to modern Kotlin.
            
            ## Key Modules
            - `arrow.core.Either`: Typed success/failure result monad.
            - `arrow.core.raise.Raise`: DSL for declarative error handling without exceptions.
            - `arrow.fx.coroutines`: Resource management (`Resource`), race conditions, and concurrency primitives.
        """.trimIndent(),
        FrameworkFeature.DATETIME to """
            # `kotlinx-datetime` Guide
            Official Kotlin Multiplatform date/time library.
            
            ## Key Types
            - `Instant`: Timestamp UTC moment.
            - `LocalDate`: Civil date without time-zone.
            - `Clock.System.now()`: Current system time.
        """.trimIndent(),
        FrameworkFeature.KTOR to """
            # Ktor Framework Guide
            Asynchronous multiplatform framework for building HTTP servers and clients.
            
            ## Client Usage
            ```kotlin
            val client = HttpClient(CIO) { install(ContentNegotiation) { json() } }
            ```
        """.trimIndent(),
        FrameworkFeature.TURBINE to """
            # AppCash Turbine Guide
            Testing library for `kotlinx.coroutines.Flow`.
            
            ## Usage
            ```kotlin
            flow.test {
                assertEquals("first", awaitItem())
                awaitComplete()
            }
            ```
        """.trimIndent(),
        FrameworkFeature.MOCKK to """
            # MockK Guide
            Idiomatic Kotlin mocking library.
            
            ## Usage
            ```kotlin
            val repo = mockk<Repository>()
            every { repo.findUser(1) } returns User(1, "Alice")
            ```
        """.trimIndent()
    )
}
