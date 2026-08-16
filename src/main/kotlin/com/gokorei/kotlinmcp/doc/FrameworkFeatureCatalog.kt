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
        FrameworkFeature.MOCKK to listOf("mockk")
    )

    val featureDocs: Map<FrameworkFeature, String> = mapOf(
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
