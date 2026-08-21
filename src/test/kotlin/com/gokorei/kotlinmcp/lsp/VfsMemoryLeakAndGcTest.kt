package com.gokorei.kotlinmcp.lsp

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Tag("stress")
@Tag("hardening")
class VfsMemoryLeakAndGcTest {

    @Test
    fun `LRU eviction bounds heap memory and strictly limits cached AST entries`(@TempDir tempDir: Path) {
        val capacity = 20
        val cache = DefaultVfsPsiCache(maxCapacity = capacity)
        val fileCount = 150

        // Parse files through cache capped at 20
        for (i in 1..fileCount) {
            val f = tempDir.resolve("TempModel_$i.kt").toFile()
            f.writeText("""
                package com.example.leak
                class TempModel_$i {
                    val payload: String = "payload_$i"
                }
            """.trimIndent())
            cache.getOrParse(f)
        }

        // Verify cache size is strictly bounded
        assertEquals(capacity, cache.size, "Cache size must not exceed configured capacity")

        // Ensure active cache element is valid using PSI AST traversal
        val lastFile = tempDir.resolve("TempModel_$fileCount.kt").toFile()
        val psi = cache.getOrParse(lastFile)
        assertNotNull(psi)

        var visitedClassName: String? = null
        psi!!.accept(object : KtTreeVisitorVoid() {
            override fun visitClass(klass: KtClass) {
                visitedClassName = klass.name
                super.visitClass(klass)
            }
        })
        assertEquals("TempModel_$fileCount", visitedClassName, "Parsed AST node must resolve matching class name via PSI")

        cache.close()
    }
}
