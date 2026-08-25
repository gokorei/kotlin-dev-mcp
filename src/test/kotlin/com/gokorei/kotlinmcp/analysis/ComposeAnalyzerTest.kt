package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ComposeAnalyzerTest {

    private lateinit var analyzer: ComposeAnalyzer

    @BeforeEach
    fun setUp() {
        analyzer = ComposeAnalyzer()
    }

    @Test
    fun `analyzeCompose detects collectAsState and recommends collectAsStateWithLifecycle`() {
        val snippet = """
            @Composable
            fun UserScreen(viewModel: UserViewModel) {
                val state by viewModel.uiState.collectAsState()
                Text(state.name)
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("collectAsStateWithLifecycle"), "expected collectAsStateWithLifecycle recommendation in: ${success.content}")
        assertTrue(success.content.contains("androidx.lifecycle.compose"))
    }

    @Test
    fun `analyzeCompose does not flag collectAsStateWithLifecycle`() {
        val snippet = """
            @Composable
            fun UserScreen(viewModel: UserViewModel) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                Text(state.name)
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("collectAsStateWithLifecycle() from"), "collectAsStateWithLifecycle should not be warned against")
    }

    @Test
    fun `analyzeCompose warns on missing modifier parameter in custom UI composables`() {
        val snippet = """
            @Composable
            fun ProfileCard(userName: String) {
                Card {
                    Text(userName)
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("modifier: Modifier = Modifier"), "expected modifier parameter advisory in: ${success.content}")
    }

    @Test
    fun `analyzeCompose allows custom UI composable with valid modifier parameter`() {
        val snippet = """
            @Composable
            fun ProfileCard(userName: String, modifier: Modifier = Modifier) {
                Card(modifier = modifier) {
                    Text(userName)
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("does not declare a `modifier: Modifier = Modifier` parameter"), "valid modifier should not be warned")
    }

    @Test
    fun `analyzeCompose detects legacy systemUiVisibility and recommends enableEdgeToEdge`() {
        val snippet = """
            fun setupWindow(window: Window) {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
                window.statusBarColor = Color.TRANSPARENT
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("enableEdgeToEdge"), "expected edge-to-edge recommendation in: ${success.content}")
    }
}
