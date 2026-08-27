package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidRuntimeTargetTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var projectService: ProjectService

    @BeforeEach
    fun setUp() {
        projectService = DefaultProjectService()
    }

    @Test
    fun `resolves runtime target from standard manifest with relative activity`() {
        val manifestXml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.myapp">
                <application>
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        val result = projectService.resolveAndroidRuntimeTarget(
            manifestContentOrPath = manifestXml,
            projectPath = null,
            buildScriptContent = null
        )

        assertTrue(result.isSuccess, "expected success: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertEquals("com.example.myapp", success.metadata["applicationId"])
        assertEquals("com.example.myapp", success.metadata["namespace"])
        assertEquals("com.example.myapp.MainActivity", success.metadata["launcherActivity"])
        assertEquals("adb shell am start -n com.example.myapp/com.example.myapp.MainActivity", success.metadata["launchCommand"])
        assertTrue(success.metadata["logcatPidCommand"]?.contains("com.example.myapp") == true)
        assertTrue(success.content.contains("com.example.myapp.MainActivity"))
    }

    @Test
    fun `resolves runtime target from activity-alias launcher`() {
        val manifestXml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.aliasapp">
                <application>
                    <activity android:name=".RealActivity" android:exported="false" />
                    <activity-alias
                        android:name=".LauncherAlias"
                        android:targetActivity=".RealActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity-alias>
                </application>
            </manifest>
        """.trimIndent()

        val result = projectService.resolveAndroidRuntimeTarget(
            manifestContentOrPath = manifestXml,
            projectPath = null,
            buildScriptContent = null
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertEquals("com.example.aliasapp", success.metadata["applicationId"])
        assertEquals("com.example.aliasapp.LauncherAlias", success.metadata["launcherActivity"])
        assertEquals("adb shell am start -n com.example.aliasapp/com.example.aliasapp.LauncherAlias", success.metadata["launchCommand"])
    }

    @Test
    fun `resolves namespace and applicationId from build script when manifest omits package attribute`() {
        val manifestXml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <activity android:name=".ui.RootActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        val buildGradleKts = """
            plugins {
                id("com.android.application")
            }
            android {
                namespace = "com.example.modern"
                defaultConfig {
                    applicationId = "com.example.modern.prod"
                }
            }
        """.trimIndent()

        val result = projectService.resolveAndroidRuntimeTarget(
            manifestContentOrPath = manifestXml,
            projectPath = null,
            buildScriptContent = buildGradleKts
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertEquals("com.example.modern.prod", success.metadata["applicationId"])
        assertEquals("com.example.modern", success.metadata["namespace"])
        assertEquals("com.example.modern.ui.RootActivity", success.metadata["launcherActivity"])
        assertEquals("adb shell am start -n com.example.modern.prod/com.example.modern.ui.RootActivity", success.metadata["launchCommand"])
    }

    @Test
    fun `resolves fully-qualified launcher activity name when name starts with absolute package`() {
        val manifestXml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.base">
                <application>
                    <activity android:name="com.custom.ui.CustomLauncherActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        val result = projectService.resolveAndroidRuntimeTarget(
            manifestContentOrPath = manifestXml,
            projectPath = null,
            buildScriptContent = null
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertEquals("com.example.base", success.metadata["applicationId"])
        assertEquals("com.custom.ui.CustomLauncherActivity", success.metadata["launcherActivity"])
        assertEquals("adb shell am start -n com.example.base/com.custom.ui.CustomLauncherActivity", success.metadata["launchCommand"])
    }

    @Test
    fun `handles project directory with src main AndroidManifest xml and build gradle kts`() {
        val appDir = File(tempDir, "app").apply { mkdirs() }
        val srcMain = File(appDir, "src/main").apply { mkdirs() }
        val manifestFile = File(srcMain, "AndroidManifest.xml")
        manifestFile.writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.fsapp">
                <application>
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent())

        val buildFile = File(appDir, "build.gradle.kts")
        buildFile.writeText("""
            android {
                namespace = "com.example.fsapp"
            }
        """.trimIndent())

        val result = projectService.resolveAndroidRuntimeTarget(
            manifestContentOrPath = "",
            projectPath = appDir.absolutePath,
            buildScriptContent = null
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertEquals("com.example.fsapp", success.metadata["applicationId"])
        assertEquals("com.example.fsapp.MainActivity", success.metadata["launcherActivity"])
    }
}
