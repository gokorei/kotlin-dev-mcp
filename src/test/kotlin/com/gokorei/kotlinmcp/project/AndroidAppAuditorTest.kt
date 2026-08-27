package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidAppAuditorTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var auditor: AndroidAppAuditor

    @BeforeEach
    fun setUp() {
        auditor = AndroidAppAuditor()
    }

    @Test
    fun `flags compose performance issues for unstable collections and missing lazy keys`() {
        val composeCode = """
            import androidx.compose.runtime.Composable
            import androidx.compose.foundation.lazy.LazyColumn
            import androidx.compose.foundation.lazy.items

            @Composable
            fun ItemList(items: List<String>, onClick: (String) -> Unit) {
                LazyColumn {
                    items(items) { item ->
                        ItemRow(item = item, onClick = { onClick(item) })
                    }
                }
            }

            @Composable
            fun ItemRow(item: String, onClick: () -> Unit) {}
        """.trimIndent()

        val result = auditor.audit(
            code = composeCode,
            projectPath = null,
            categories = listOf(AndroidAuditCategory.COMPOSE_PERFORMANCE)
        )

        assertTrue(result.isSuccess, "expected success: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("COMPOSE_PERFORMANCE"), "Expected category header in: ${success.content}")
        assertTrue(success.content.contains("ImmutableList") || success.content.contains("unstable collection"), "Expected unstable collection warning")
        assertTrue(success.content.contains("key = { ... }") || success.content.contains("missing `key`"), "Expected lazy key warning")
    }

    @Test
    fun `flags runtime permission issues for dangerous permissions without runtime request`() {
        val manifestWithCamera = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.cam">
                <uses-permission android:name="android.permission.CAMERA" />
                <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
                <application />
            </manifest>
        """.trimIndent()

        val result = auditor.audit(
            code = manifestWithCamera,
            projectPath = null,
            categories = listOf(AndroidAuditCategory.RUNTIME_PERMISSIONS)
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("RUNTIME_PERMISSIONS"))
        assertTrue(success.content.contains("CAMERA"))
        assertTrue(success.content.contains("POST_NOTIFICATIONS"))
    }

    @Test
    fun `flags r8 minification issues when minify enabled but proguard rules missing`() {
        val appDir = File(tempDir, "app").apply { mkdirs() }
        val buildKts = File(appDir, "build.gradle.kts")
        buildKts.writeText("""
            android {
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    }
                }
            }
        """.trimIndent())

        val result = auditor.audit(
            code = "",
            projectPath = appDir.absolutePath,
            categories = listOf(AndroidAuditCategory.R8_MINIFICATION)
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("R8_MINIFICATION"))
        assertTrue(success.content.contains("proguard-rules.pro"))
    }

    @Test
    fun `runs full audit when categories are unspecified`() {
        val snippet = """
            import androidx.compose.runtime.Composable
            @Composable
            fun MyScreen(list: List<Int>) {}
        """.trimIndent()

        val result = auditor.audit(code = snippet, projectPath = null, categories = emptyList())
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("# Android App Audit"))
    }
}
