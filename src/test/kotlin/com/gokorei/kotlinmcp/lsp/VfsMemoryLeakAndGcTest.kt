package com.gokorei.kotlinmcp.lsp

import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.ref.WeakReference
import java.nio.file.Path

@Tag("stress")
@Tag("hardening")
class VfsMemoryLeakAndGcTest {

    @Test
    fun `LRU eviction bounds heap memory and allows garbage collection of evicted AST nodes`(@TempDir tempDir: Path) {
        val capacity = 20
        val cache = DefaultVfsPsiCache(maxCapacity = capacity)
        val fileCount = 150
        val earlyWeakRefs = mutableListOf<WeakReference<KtFile>>()

        // Parse files through cache capped at 20
        for (i in 1..fileCount) {
            val f = tempDir.resolve("TempModel_$i.kt").toFile()
            f.writeText("""
                package com.example.leak
                class TempModel_$i {
                    val payload: String = "payload_$i"
                }
            """.trimIndent())
            val psi = cache.getOrParse(f)
            if (i <= 20 && psi != null) {
                earlyWeakRefs.add(WeakReference(psi))
            }
        }

        // Verify cache size is strictly bounded
        assertEquals(capacity, cache.size, "Cache size must not exceed configured capacity")

        // Trigger garbage collection
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }

        // Ensure active cache elements are still functional
        val lastFile = tempDir.resolve("TempModel_$fileCount.kt").toFile()
        val psi = cache.getOrParse(lastFile)
        assertNotNull(psi)
        assertTrue(psi!!.text.contains("TempModel_$fileCount"))

        val cleared = earlyWeakRefs.count { it.get() == null }
        assertTrue(cleared > 0, "Expected at least some evicted AST instances to be collected by GC")

        cache.close()
    }
}
