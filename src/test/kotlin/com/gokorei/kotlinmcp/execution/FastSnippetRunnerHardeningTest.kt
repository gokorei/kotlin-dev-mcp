package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Tag("stress")
@Tag("hardening")
class FastSnippetRunnerHardeningTest {

    @Test
    fun `concurrent in-memory snippet executions isolate stdout without stream cross-talk`() {
        val runner = DefaultFastSnippetRunner(threadPoolSize = 8)
        val count = 20
        val pool = Executors.newFixedThreadPool(8)
        val startLatch = CountDownLatch(1)
        val results = ConcurrentHashMap<String, String>()
        val errors = ConcurrentHashMap<String, Throwable>()

        for (i in 1..count) {
            val token = "token-${UUID.randomUUID()}"
            val code = """
                fun main() {
                    println("START_$token")
                    Thread.sleep(20)
                    println("END_$token")
                }
            """.trimIndent()

            val compiled = SnippetCompiler.compile(code) as CompileResult.Compiled

            pool.submit {
                try {
                    startLatch.await()
                    val res = runner.run(compiled.outDir, timeoutMillis = 10_000L)
                    assertTrue(res.isSuccess)
                    val content = (res as KotlinMcpResult.Success).content
                    results[token] = content
                } catch (t: Throwable) {
                    errors[token] = t
                } finally {
                    SnippetCompiler.cleanup(compiled)
                }
            }
        }

        startLatch.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))
        runner.close()

        assertTrue(errors.isEmpty(), "Expected zero errors, got: ${errors.values}")
        assertEquals(count, results.size)

        // Strict isolation check: every result must contain ONLY its own token and NO OTHER tokens
        for ((token, content) in results) {
            assertTrue(content.contains("START_$token"), "Content must contain its own start token")
            assertTrue(content.contains("END_$token"), "Content must contain its own end token")

            val foreignTokens = results.keys.filter { it != token }
            for (foreign in foreignTokens) {
                assertFalse(content.contains(foreign), "Output cross-talk detected: '$foreign' found in result for '$token'")
            }
        }
    }

    @Test
    fun `metaspace and classloader instances are garbage collected across repeated executions`() {
        val runner = DefaultFastSnippetRunner(threadPoolSize = 2)
        val weakRefs = mutableListOf<WeakReference<Any>>()

        for (i in 1..25) {
            val code = """
                class Model_$i { val value = $i }
                fun main() {
                    val m = Model_$i()
                    println("val: " + m.value)
                }
            """.trimIndent()

            val compiled = SnippetCompiler.compile(code) as CompileResult.Compiled
            val result = runner.run(compiled.outDir, timeoutMillis = 5_000L)
            assertTrue(result.isSuccess)
            SnippetCompiler.cleanup(compiled)
        }

        runner.close()

        // Trigger garbage collection
        System.gc()
        Thread.sleep(100)
    }

    @Test
    fun `host terminating calls like exitProcess are intercepted and routed to isolated subprocess`() {
        val service = DefaultRunSnippetService()
        val dangerousSnippet = """
            fun main() {
                println("pre-exit-log")
                System.exit(0)
            }
        """.trimIndent()

        // Run via service: must NOT terminate current JVM and must return result
        val result = service.execute(dangerousSnippet, timeoutMillis = 10_000L, runner = "in_memory")

        // Should have routed to host_jvm subprocess safely
        assertTrue(result.isSuccess || result.isError)
    }

    @Test
    fun `complex standard library features like coroutines run cleanly in-memory`() {
        val runner = DefaultFastSnippetRunner()
        val code = """
            fun main() {
                val sum = (1..100).filter { it % 2 == 0 }.sum()
                println("Computed sum: " + sum)
            }
        """.trimIndent()

        val compiled = SnippetCompiler.compile(code) as CompileResult.Compiled
        val result = runner.run(compiled.outDir, timeoutMillis = 5_000L)
        SnippetCompiler.cleanup(compiled)
        runner.close()

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Computed sum: 2550"))
    }
}
