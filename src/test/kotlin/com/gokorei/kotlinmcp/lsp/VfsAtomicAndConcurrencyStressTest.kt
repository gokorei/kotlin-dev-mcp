package com.gokorei.kotlinmcp.lsp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Tag("stress")
@Tag("hardening")
class VfsAtomicAndConcurrencyStressTest {

    @Test
    fun `high-concurrency readers with simultaneous background invalidations execute safely without deadlocks`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 100)
        val fileCount = 20
        val files = (1..fileCount).map { i ->
            val f = tempDir.resolve("Model$i.kt").toFile()
            f.writeText("data class Model$i(val id: Int, val payload: String = \"item-$i\")")
            f
        }

        val readerCount = 16
        val invalidatorCount = 4
        val pool = Executors.newFixedThreadPool(readerCount + invalidatorCount)
        val startLatch = CountDownLatch(1)
        val running = AtomicBoolean(true)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val readOperations = java.util.concurrent.atomic.AtomicLong(0)

        // Readers
        repeat(readerCount) { rIndex ->
            pool.submit {
                startLatch.await()
                while (running.get()) {
                    try {
                        val target = files[rIndex % fileCount]
                        val psi = cache.getOrParse(target)
                        if (psi != null) {
                            val text = psi.text
                            assertTrue(text.contains("data class Model"), "AST must be structurally valid")
                            readOperations.incrementAndGet()
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
        }

        // Background Invalidators / Mutators
        repeat(invalidatorCount) { invIndex ->
            pool.submit {
                startLatch.await()
                var counter = 0
                while (running.get()) {
                    try {
                        val target = files[invIndex % fileCount]
                        counter++
                        val updatedCode = "data class Model${invIndex + 1}(val id: Int, val version: Int = $counter)"
                        val tmp = File(target.parentFile, "${target.name}.tmp")
                        tmp.writeText(updatedCode)
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        cache.invalidate(target.absolutePath)
                        Thread.sleep(10)
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
        }

        startLatch.countDown()
        Thread.sleep(2000) // Stress for 2 seconds
        running.set(false)
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Thread pool must terminate cleanly")

        cache.close()

        assertTrue(errors.isEmpty(), "Expected zero concurrency exceptions, but got: ${errors.map { it.message }}")
        assertTrue(readOperations.get() > 500, "Expected >500 successful concurrent reads, got: ${readOperations.get()}")
    }

    @Test
    fun `atomic file replacements (tmp file renamed over target) cleanly invalidate and reload AST`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 50)
        cache.startWatching(tempDir.toString())

        val targetFile = tempDir.resolve("Repository.kt").toFile()
        targetFile.writeText("class Repository { fun fetch(): Int = 1 }")

        val psi1 = cache.getOrParse(targetFile)
        assertNotNull(psi1)
        assertEquals("class Repository { fun fetch(): Int = 1 }", psi1?.text)

        // Simulate IDE atomic replace: write to tmp file, atomic move over target
        val tmpFile = tempDir.resolve("Repository.kt.tmp").toFile()
        tmpFile.writeText("class Repository { fun fetch(): Int = 2 }")
        Files.move(tmpFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        // Invalidate explicitly or wait for WatchService event
        cache.invalidate(targetFile.toPath())

        val psi2 = cache.getOrParse(targetFile)
        assertNotNull(psi2)
        assertEquals("class Repository { fun fetch(): Int = 2 }", psi2?.text)

        cache.close()
    }

    @Test
    fun `directory recursive deletion evicts all nested child entries`(@TempDir tempDir: Path) {
        val cache = DefaultVfsPsiCache(maxCapacity = 50)
        val nestedDir = tempDir.resolve("nested/subpackage").toFile()
        nestedDir.mkdirs()

        val f1 = File(nestedDir, "A.kt").apply { writeText("class A") }
        val f2 = File(nestedDir, "B.kt").apply { writeText("class B") }

        assertNotNull(cache.getOrParse(f1))
        assertNotNull(cache.getOrParse(f2))
        assertEquals(2, cache.size)

        // Invalidate directory path
        nestedDir.deleteRecursively()
        cache.invalidate(f1.absolutePath)
        cache.invalidate(f2.absolutePath)

        assertEquals(0, cache.size)
        cache.close()
    }
}
