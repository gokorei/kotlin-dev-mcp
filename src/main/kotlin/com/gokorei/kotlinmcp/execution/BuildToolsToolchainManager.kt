@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
package com.gokorei.kotlinmcp.execution

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.buildtools.api.CompilationService
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import java.io.Closeable

/**
 * Scoped build session for interacting with the Kotlin Build Tools API.
 */
data class BuildToolsSession(
    val compilationService: CompilationService
)

/**
 * Explicit interface for managing the lifecycle of the Kotlin Build Tools API (BTA)
 * toolchain and compilation sessions.
 */
interface BuildToolsToolchainManager : Closeable {
    /**
     * Gets or loads the cached [CompilationService] implementation.
     */
    fun getCompilationService(): CompilationService

    /**
     * Executes an operation within a scoped [BuildToolsSession].
     */
    fun <T> withSession(block: (BuildToolsSession) -> T): T
}

/**
 * Default thread-safe implementation of [BuildToolsToolchainManager] caching the
 * [CompilationService] instance across snippet compilation requests.
 */
class DefaultBuildToolsToolchainManager(
    private val classLoaderProvider: () -> ClassLoader = {
        SharedApiClassesClassLoader()
    }
) : BuildToolsToolchainManager {

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var cachedService: CompilationService? = null

    @Synchronized
    override fun getCompilationService(): CompilationService {
        cachedService?.let { return it }
        val service = try {
            val loader = classLoaderProvider()
            CompilationService.loadImplementation(loader)
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to load CompilationService via custom classloader, falling back to context classloader" }
            CompilationService.loadImplementation(BuildToolsToolchainManager::class.java.classLoader)
        }
        cachedService = service
        return service
    }

    override fun <T> withSession(block: (BuildToolsSession) -> T): T {
        val service = getCompilationService()
        val session = BuildToolsSession(compilationService = service)
        return block(session)
    }

    @Synchronized
    override fun close() {
        cachedService = null
    }

    companion object {
        val instance: BuildToolsToolchainManager by lazy {
            DefaultBuildToolsToolchainManager()
        }
    }
}
