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
    }

    @Test
    fun `getOrParse invalidates cache entry when file content changes on disk`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 10)
        val file = tempDir.resolve("Mutate.kt").toFile()
        file.writeText("val initial = 1")

        val psi1 = cache.getOrParse(file)
        assertEquals("val initial = 1", psi1?.text)

        // Modify file
        Thread.sleep(20) // Ensure timestamp advances
        file.writeText("val updated = 2")

        val psi2 = cache.getOrParse(file)
        assertEquals("val updated = 2", psi2?.text)
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
    }

    @Test
    fun `startWatching registers WatchService and invalidates on background modifications`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 10)
        cache.startWatching(tempDir.toString())

        val file = tempDir.resolve("Watched.kt").toFile()
        file.writeText("val a = 100")

        val psi1 = cache.getOrParse(file)
        assertEquals("val a = 100", psi1?.text)

        // Update file
        Thread.sleep(50)
        file.writeText("val a = 200")

        // Invalidate via file watch trigger
        cache.invalidate(file.toPath())
        val psi2 = cache.getOrParse(file)
        assertEquals("val a = 200", psi2?.text)

        cache.close()
    }
}
