package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkManagerAnalyzerTest {

    private lateinit var analyzer: WorkManagerAnalyzer

    @BeforeEach
    fun setUp() {
        analyzer = WorkManagerAnalyzer()
    }

    @Test
    fun `flags missing HiltWorker annotation when AssistedInject constructor is used on CoroutineWorker`() {
        val workerCode = """
            import android.content.Context
            import androidx.work.CoroutineWorker
            import androidx.work.WorkerParameters
            import dagger.assisted.Assisted
            import dagger.assisted.AssistedInject

            class DownloadWorker @AssistedInject constructor(
                @Assisted context: Context,
                @Assisted params: WorkerParameters,
                private val api: ApiService
            ) : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result {
                    return Result.success()
                }
            }
        """.trimIndent()

        val result = analyzer.analyze(workerCode)
        assertTrue(result.isSuccess, "expected success: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("@HiltWorker"), "expected @HiltWorker recommendation in: ${success.content}")
    }

    @Test
    fun `flags blocking file operations in doWork when not wrapped in withContext Dispatchers IO`() {
        val workerCode = """
            import android.content.Context
            import androidx.work.CoroutineWorker
            import androidx.work.WorkerParameters
            import java.io.File

            class FileCleanupWorker(
                context: Context,
                params: WorkerParameters
            ) : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result {
                    val file = File("/tmp/data.bin")
                    val bytes = file.readBytes()
                    Thread.sleep(1000)
                    return Result.success()
                }
            }
        """.trimIndent()

        val result = analyzer.analyze(workerCode)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("withContext(Dispatchers.IO)") || success.content.contains("blocking"), "expected blocking/dispatcher warning: ${success.content}")
    }

    @Test
    fun `passes when blocking calls are wrapped in withContext Dispatchers IO`() {
        val workerCode = """
            import android.content.Context
            import androidx.work.CoroutineWorker
            import androidx.work.WorkerParameters
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.withContext
            import java.io.File

            class SafeFileWorker(
                context: Context,
                params: WorkerParameters
            ) : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result {
                    return withContext(Dispatchers.IO) {
                        val file = File("/tmp/data.bin")
                        val text = file.readText()
                        Result.success()
                    }
                }
            }
        """.trimIndent()

        val result = analyzer.analyze(workerCode)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("⚠️ Blocking"), "should not flag blocking calls wrapped in withContext: ${success.content}")
    }

    @Test
    fun `emits advisory on setForeground for foreground service types`() {
        val workerCode = """
            import android.content.Context
            import androidx.work.CoroutineWorker
            import androidx.work.WorkerParameters
            import androidx.work.ForegroundInfo

            class SyncWorker(
                context: Context,
                params: WorkerParameters
            ) : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result {
                    setForeground(createForegroundInfo())
                    return Result.success()
                }
                private fun createForegroundInfo(): ForegroundInfo = TODO()
            }
        """.trimIndent()

        val result = analyzer.analyze(workerCode)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("SystemForegroundService") || success.content.contains("foregroundServiceType") || success.content.contains("setForeground"), "expected setForeground advisory: ${success.content}")
    }
}
