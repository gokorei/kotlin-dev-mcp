package com.gokorei.kotlinmcp.linting

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AndroidLintParserTest {

    private lateinit var lintService: LintService

    @BeforeEach
    fun setUp() {
        lintService = DefaultLintService()
    }

    @Test
    fun `parseAndroidLintReport parses XML issues into structured findings`() {
        val xmlReport = """
            <?xml version="1.0" encoding="UTF-8"?>
            <issues format="6" by="lint 8.5.2">
                <issue
                    id="InlinedApi"
                    severity="Warning"
                    message="Field requires API level 33 (current min is 26): android.Manifest.permission#POST_NOTIFICATIONS"
                    category="Correctness"
                    priority="6"
                    summary="Using inlined constants on older versions"
                    explanation="This code uses an inlined constant...">
                    <location
                        file="/src/main/kotlin/MainActivity.kt"
                        line="42"
                        column="18"/>
                </issue>
                <issue
                    id="HardcodedText"
                    severity="Information"
                    message="Hardcoded string &quot;Submit&quot;, should use `@string` resource"
                    category="Internationalization"
                    priority="5"
                    summary="Hardcoded text"
                    explanation="Hardcoding text attributes directly...">
                    <location
                        file="/src/main/res/layout/activity_main.xml"
                        line="15"
                        column="9"/>
                </issue>
            </issues>
        """.trimIndent()

        val result = (lintService as DefaultLintService).parseAndroidLintReport(xmlReport)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("InlinedApi"))
        assertTrue(success.content.contains("MainActivity.kt:42:18"))
        assertTrue(success.content.contains("HardcodedText"))
        assertEquals("2", success.metadata["issuesCount"])
    }

    @Test
    fun `parseAndroidLintReport handles clean XML without issues`() {
        val xmlReport = """
            <?xml version="1.0" encoding="UTF-8"?>
            <issues format="6" by="lint 8.5.2">
            </issues>
        """.trimIndent()

        val result = (lintService as DefaultLintService).parseAndroidLintReport(xmlReport)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("No Android Lint issues found"))
        assertEquals("0", success.metadata["issuesCount"])
    }
}
