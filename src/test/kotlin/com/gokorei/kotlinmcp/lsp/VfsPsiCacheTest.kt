package com.gokorei.kotlinmcp.lsp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class VfsPsiCacheTest {

    @Test
    fun `getOrParse parses and caches KtFile AST in memory`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 10)
        val file = tempDir.resolve("Example.kt").toFile()
        file.writeText("fun hello(): String = \"world\"")

        val psi1 = cache.getOrParse(file)
        assertNotNull(psi1)
        assertEquals("fun hello(): String = \"world\"", psi1?.text)

        // Cache hit
        val psi2 = cache.getOrParse(file)
        assertNotNull(psi2)
        assertEquals(psi1?.text, psi2?.text)
        assertEquals(1, cache.size)
        cache.close()
    }

    @Test
    fun `getOrParse invalidates cache entry when file content changes on disk`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 10)
        val file = tempDir.resolve("Mutate.kt").toFile()
        file.writeText("val initial = 1")

        val psi1 = cache.getOrParse(file)
        assertEquals("val initial = 1", psi1?.text)

        // Modify file & advance timestamp deterministically
        file.writeText("val updated = 2")
        file.setLastModified(file.lastModified() + 2_000)

        val psi2 = cache.getOrParse(file)
        assertEquals("val updated = 2", psi2?.text)
        cache.close()
    }

    @Test
    fun `invalidate removes path from cache explicitly`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 10)
        val file = tempDir.resolve("DeleteMe.kt").toFile()
        file.writeText("class Target")

        val psi = cache.getOrParse(file)
        assertNotNull(psi)
        assertEquals(1, cache.size)

        cache.invalidate(file.absolutePath)
        assertEquals(0, cache.size)
        cache.close()
    }

    @Test
    fun `LRU capacity bounds max entries and evicts oldest`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 3)
        val files = (1..5).map { i ->
            val f = tempDir.resolve("File$i.kt").toFile()
            f.writeText("val x$i = $i")
            cache.getOrParse(f)
            f
        }

        assertEquals(3, cache.size, "Cache size must be bounded by maxCapacity")

        // Mutate files 1 and 2 without updating timestamp so cached entry would return old text
        files[0].writeText("val x1 = 999")
        files[1].writeText("val x2 = 999")

        // Because File1 and File2 were evicted from the LRU cache, getOrParse will re-parse and see 999
        val reparse1 = cache.getOrParse(files[0])
        assertEquals("val x1 = 999", reparse1?.text, "Evicted File1 must be re-parsed from disk")

        cache.close()
    }

    @Test
    fun `startWatching registers WatchService and invalidates on background modifications`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 10)
        cache.startWatching(tempDir.toString())

        val file = tempDir.resolve("Watched.kt").toFile()
        file.writeText("val a = 100")

        val psi1 = cache.getOrParse(file)
        assertEquals("val a = 100", psi1?.text)

        val originalMtime = file.lastModified()

        // Update file and restore original timestamp to strictly test WatchService invalidation
        file.writeText("val a = 200")
        assertTrue(file.setLastModified(originalMtime), "setLastModified must succeed")
        // Poll with bounded timeout for WatchService event delivery
        var updatedText: String? = null
        for (i in 1..50) {
            val current = cache.getOrParse(file)
            if (current?.text == "val a = 200") {
                updatedText = current.text
                break
            }
            Thread.sleep(60)
        }

        assertEquals("val a = 200", updatedText, "WatchService must invalidate stale cache on disk modification")
        cache.close()
    }
}
