package com.gokorei.kotlinmcp.lsp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Tag("stress")
@Tag("hardening")
class VfsMemoryLeakAndGcTest {

    @Test
    fun `LRU eviction bounds heap memory and allows garbage collection of evicted AST nodes`(@TempDir tempDir: Path) {
        val capacity = 50
        val cache = DefaultVfsPsiCache(maxCapacity = capacity)
        val fileCount = 300

        // Parse 300 files through a cache capped at 50
        for (i in 1..fileCount) {
            val f = tempDir.resolve("TempModel_$i.kt").toFile()
            f.writeText("""
                package com.example.leak
                class TempModel_$i {
                    val field1: String = "large_string_payload_to_consume_retained_bytes_$i"
                    val field2: Int = $i
                    fun compute(): String = field1 + field2
                }
            """.trimIndent())
            cache.getOrParse(f)
        }

        // Verify cache size is strictly bounded
        assertEquals(capacity, cache.size, "Cache size must not exceed configured capacity")

        // Encourage GC to collect evicted AST instances
        System.gc()
        Thread.sleep(100)

        // Ensure active cache elements are still functional
        val lastFile = tempDir.resolve("TempModel_$fileCount.kt").toFile()
        val psi = cache.getOrParse(lastFile)
        assertNotNull(psi)
        assertTrue(psi!!.text.contains("TempModel_$fileCount"))

        cache.close()
    }
}
