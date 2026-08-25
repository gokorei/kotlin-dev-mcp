package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AndroidManifestInspectorTest {

    private lateinit var projectService: ProjectService

    @BeforeEach
    fun setUp() {
        projectService = DefaultProjectService()
    }

    @Test
    fun `android_manifest flags activity with intent-filter missing android exported attribute`() {
        val manifestXml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <activity android:name=".MainActivity">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.INSPECT_ANDROID_MANIFEST,
            buildScriptContent = manifestXml
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("android:exported"), "expected android:exported warning in: ${success.content}")
        assertTrue(success.content.contains(".MainActivity"))
    }

    @Test
    fun `android_manifest flags foreground service missing foregroundServiceType`() {
        val manifestXml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
                <application>
                    <service
                        android:name=".SyncService"
                        android:exported="false" />
                </application>
            </manifest>
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.INSPECT_ANDROID_MANIFEST,
            buildScriptContent = manifestXml
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("foregroundServiceType"), "expected foregroundServiceType warning in: ${success.content}")
    }

    @Test
    fun `android_manifest passes on valid modern manifest`() {
        val manifestXml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
                <application>
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.INSPECT_ANDROID_MANIFEST,
            buildScriptContent = manifestXml
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("⚠️"), "valid manifest should have no warnings: ${success.content}")
    }
}
