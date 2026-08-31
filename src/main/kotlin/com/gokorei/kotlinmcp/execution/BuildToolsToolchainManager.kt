@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
package com.gokorei.kotlinmcp.execution

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.buildtools.api.CompilationService
import java.io.Closeable

/**
 * Scoped build session for interacting with the Kotlin Build Tools API.
 *
 * @property compilationService The active [CompilationService] instance for this session.
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
     *
     * @return The cached or newly initialized [CompilationService].
     */
    fun getCompilationService(): CompilationService

    /**
     * Executes an operation within a scoped [BuildToolsSession].
     *
     * @param block The operation to execute using the active session.
     * @return The result of executing [block].
     */
    fun <T> withSession(block: (BuildToolsSession) -> T): T
}

/**
 * Default thread-safe implementation of [BuildToolsToolchainManager] caching the
 * [CompilationService] instance and managing the underlying [ClassLoader] across compilation requests.
 *
 * @param classLoaderProvider Factory function supplying the [ClassLoader] used to load the BTA implementation.
 */
class DefaultBuildToolsToolchainManager(
    private val classLoaderProvider: () -> ClassLoader = {
        BuildToolsToolchainManager::class.java.classLoader
    }
) : BuildToolsToolchainManager {

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var cachedService: CompilationService? = null

    @Volatile
    private var cachedClassLoader: ClassLoader? = null

    /**
     * Retrieves the cached [CompilationService] instance or loads it using the configured [classLoaderProvider].
     *
     * @return The active [CompilationService].
     */
    @Synchronized
    override fun getCompilationService(): CompilationService {
        cachedService?.let { return it }
        val loader = classLoaderProvider()
        val service = try {
            CompilationService.loadImplementation(loader)
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to load CompilationService via provided classloader, falling back to classLoader of BuildToolsToolchainManager" }
            CompilationService.loadImplementation(BuildToolsToolchainManager::class.java.classLoader)
        }
        cachedClassLoader = loader
        cachedService = service
        return service
    }

    /**
     * Executes a given lambda within a scoped [BuildToolsSession].
     *
     * @param block The code block receiving the scoped [BuildToolsSession].
     * @return The return value of [block].
     */
    override fun <T> withSession(block: (BuildToolsSession) -> T): T {
        val service = getCompilationService()
        val session = BuildToolsSession(compilationService = service)
        return block(session)
    }

    /**
     * Closes the manager, invalidating cached compilation services and releasing closeable classloader resources.
     */
    @Synchronized
    override fun close() {
        (cachedClassLoader as? AutoCloseable)?.close()
        cachedClassLoader = null
        cachedService = null
    }

    companion object {
        /**
         * Singleton instance of the default toolchain manager.
         */
        val instance: BuildToolsToolchainManager by lazy {
            DefaultBuildToolsToolchainManager()
        }
    }
}
