package com.gokorei.kotlinmcp.lsp

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory Virtual File System (VFS) and AST cache.
 * Provides LRU-bounded caching of parsed KtFile ASTs, background file invalidation via
 * WatchService, and sub-millisecond AST re-use across MCP tool invocations.
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
        val contentHash: Int,
        val content: String? = null
    )

    // Bounded LRU cache for parsed KtFiles
    private val cache: MutableMap<String, CachedEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedEntry>(maxCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEntry>?): Boolean {
                return size > maxCapacity
            }
        }
    )

    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()
    @Volatile
    private var watchService: WatchService? = null
    @Volatile
    private var watchThread: Thread? = null
    @Volatile
    private var closed = false

    override val size: Int
        get() = cache.size

    override fun getOrParse(file: File): KtFile? {
        if (!file.exists() || !file.isFile) return null
        val normalizedPath = file.absoluteFile.normalize().invariantSeparatorsPath
        val lastMod = file.lastModified()

        val existing = cache[normalizedPath]
        if (existing != null && existing.lastModified == lastMod) {
            return existing.file
        }

        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val hash = text.hashCode()

        if (existing != null && existing.contentHash == hash && (existing.content == null || existing.content == text || existing.file.text == text)) {
            // Content didn't change even if timestamp bumped
            val updated = existing.copy(lastModified = lastMod, content = text)
            cache[normalizedPath] = updated
            return updated.file
        }

        val parsed = K2SnippetFrontend.parsePsi(text) ?: return null
        cache[normalizedPath] = CachedEntry(parsed, lastMod, hash, text)
        return parsed
    }

    override fun getOrParse(filePath: String, content: String): KtFile? {
        val normalizedPath = File(filePath).absoluteFile.normalize().invariantSeparatorsPath
        val hash = content.hashCode()

        val existing = cache[normalizedPath]
        if (existing != null && existing.contentHash == hash && (existing.content == null || existing.content == content || existing.file.text == content)) {
            return existing.file
        }

        val parsed = K2SnippetFrontend.parsePsi(content) ?: return null
        cache[normalizedPath] = CachedEntry(parsed, System.currentTimeMillis(), hash, content)
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

    @Synchronized
    override fun startWatching(rootPath: String) {
        val rootDir = File(rootPath)
        if (!rootDir.isDirectory || closed || watchService != null) return

        try {
            val ws = FileSystems.getDefault().newWatchService()
            this.watchService = ws

            val sensitivityModifier = runCatching {
                val clazz = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier")
                clazz.getField("HIGH").get(null) as? WatchEvent.Modifier
            }.getOrNull()

            val modifiers = if (sensitivityModifier != null) arrayOf(sensitivityModifier) else emptyArray()

            // Register root and subdirectories
            rootDir.walkTopDown().onEnter { dir ->
                !K2ResolutionUtils.isExcludedWorkspaceDir(dir)
            }.filter { it.isDirectory }.forEach { dir ->
                runCatching {
                    val path = dir.toPath()
                    val key = if (modifiers.isNotEmpty()) {
                        path.register(ws, arrayOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), *modifiers)
                    } else {
                        path.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                    }
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
                    val events = key.pollEvents()
                    if (dirPath != null) {
                        for (event in events) {
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
            runCatching { this.watchService?.close() }
            this.watchService = null
            this.watchKeys.clear()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        watchThread?.interrupt()
        runCatching { watchService?.close() }
        watchService = null
        watchKeys.clear()
        cache.clear()
    }
}
