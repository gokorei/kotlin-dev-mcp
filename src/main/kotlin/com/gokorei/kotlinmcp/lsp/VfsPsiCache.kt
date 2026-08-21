package com.gokorei.kotlinmcp.lsp

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory Virtual File System (VFS) cache for parsed K2 [KtFile] ASTs.
 * Prevents repetitive disk I/O and PSI parsing across LSP and analysis queries,
 * with WatchService file-modification invalidation support.
 */
interface VfsPsiCache : AutoCloseable {
    fun getOrParse(file: File): KtFile?
    fun getOrParse(filePath: String, content: String): KtFile?
    fun invalidate(filePath: String)
    fun invalidate(path: Path)
    fun clear()
    val size: Int
    fun startWatching(rootPath: String)
}

class DefaultVfsPsiCache(
    private val maxCapacity: Int = 500
) : VfsPsiCache {

    private val logger = KotlinLogging.logger {}

    private data class CachedEntry(
        val file: KtFile,
        val lastModified: Long,
        val contentHash: Int
    )

    // Bounded LRU cache map
    private val cache: MutableMap<String, CachedEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedEntry>(maxCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEntry>?): Boolean {
                return size > maxCapacity
            }
        }
    )

    @Volatile
    private var watchService: WatchService? = null
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()
    @Volatile
    private var watchThread: Thread? = null
    @Volatile
    private var closed = false

    override val size: Int
        get() = cache.size

    override fun getOrParse(file: File): KtFile? {
        val normalizedPath = file.absoluteFile.normalize().invariantSeparatorsPath
        val lastMod = file.lastModified()

        val existing = cache[normalizedPath]
        if (existing != null && existing.lastModified == lastMod) {
            return existing.file
        }

        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val hash = text.hashCode()

        if (existing != null && existing.contentHash == hash) {
            // Content didn't change even if timestamp bumped
            val updated = existing.copy(lastModified = lastMod)
            cache[normalizedPath] = updated
            return updated.file
        }

        val parsed = K2SnippetFrontend.parsePsi(text) ?: return null
        cache[normalizedPath] = CachedEntry(parsed, lastMod, hash)
        return parsed
    }

    override fun getOrParse(filePath: String, content: String): KtFile? {
        val normalizedPath = File(filePath).absoluteFile.normalize().invariantSeparatorsPath
        val hash = content.hashCode()

        val existing = cache[normalizedPath]
        if (existing != null && existing.contentHash == hash) {
            return existing.file
        }

        val parsed = K2SnippetFrontend.parsePsi(content) ?: return null
        cache[normalizedPath] = CachedEntry(parsed, System.currentTimeMillis(), hash)
        return parsed
    }

    override fun invalidate(filePath: String) {
        val normalized = File(filePath).absoluteFile.normalize().invariantSeparatorsPath
        cache.remove(normalized)
    }

    override fun invalidate(path: Path) {
        invalidate(path.toFile().absolutePath)
    }

    override fun clear() {
        cache.clear()
    }

    override fun startWatching(rootPath: String) {
        val rootDir = File(rootPath)
        if (!rootDir.isDirectory || closed) return

        try {
            val ws = FileSystems.getDefault().newWatchService()
            this.watchService = ws

            // Register root and subdirectories
            rootDir.walkTopDown().onEnter { dir ->
                !K2ResolutionUtils.isExcludedWorkspaceDir(dir)
            }.filter { it.isDirectory }.forEach { dir ->
                runCatching {
                    val path = dir.toPath()
                    val key = path.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                    watchKeys[key] = path
                }
            }

            val thread = Thread({
                while (!closed) {
                    val key: WatchKey = try {
                        ws.take()
                    } catch (_: InterruptedException) {
                        break
                    }

                    val dirPath = watchKeys[key]
                    if (dirPath != null) {
                        for (event in key.pollEvents()) {
                            val context = event.context() as? Path
                            if (context != null) {
                                val fullPath = dirPath.resolve(context)
                                val ext = fullPath.toFile().extension
                                if (ext == "kt" || ext == "kts" || ext == "java") {
                                    invalidate(fullPath)
                                }
                            }
                        }
                    }

                    val valid = key.reset()
                    if (!valid) {
                        watchKeys.remove(key)
                    }
                }
            }, "VfsPsiCache-Watcher").apply {
                isDaemon = true
                start()
            }
            this.watchThread = thread
        } catch (e: Exception) {
            logger.warn(e) { "Could not initialize VfsPsiCache WatchService for root '$rootPath': ${e.message}" }
        }
    }

    override fun close() {
        closed = true
        watchThread?.interrupt()
        runCatching { watchService?.close() }
        watchKeys.clear()
        cache.clear()
    }
}
