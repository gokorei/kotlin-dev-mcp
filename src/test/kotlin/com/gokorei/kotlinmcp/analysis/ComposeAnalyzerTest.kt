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

    @Test
    fun `analyzeCompose warns on non-plain modifier default value like Modifier padding`() {
        val snippet = """
            @Composable
            fun ProfileCard(userName: String, modifier: Modifier = Modifier.padding(8.dp)) {
                Card(modifier = modifier) {
                    Text(userName)
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("does not declare a default value `= Modifier`"), "non-plain modifier default should be warned: ${success.content}")
    }

    @Test
    fun `analyzeCompose detects duplicate keys in LazyColumn item calls`() {
        val snippet = """
            @Composable
            fun WordScreen(modifier: Modifier = Modifier) {
                LazyColumn(modifier = modifier) {
                    item(key = "header") {
                        Text("Header 1")
                    }
                    item(key = "header") {
                        Text("Header 2")
                    }
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(
            success.content.contains("Duplicate key `\"header\"`") || success.content.contains("Duplicate key"),
            "expected duplicate key warning in: ${success.content}"
        )
    }

    @Test
    fun `analyzeCompose detects constant literal key in items lambda`() {
        val snippet = """
            @Composable
            fun WordList(words: List<String>, modifier: Modifier = Modifier) {
                LazyColumn(modifier = modifier) {
                    items(words, key = { "constant_key" }) { word ->
                        Text(word)
                    }
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(
            success.content.contains("returns a constant literal") || success.content.contains("constant key"),
            "expected constant key warning in: ${success.content}"
        )
    }

    @Test
    fun `analyzeCompose warns when items call omits key parameter`() {
        val snippet = """
            @Composable
            fun WordList(words: List<String>, modifier: Modifier = Modifier) {
                LazyColumn(modifier = modifier) {
                    items(words) { word ->
                        Text(word)
                    }
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(
            success.content.contains("does not specify a `key` parameter") || success.content.contains("missing `key`"),
            "expected missing key warning in: ${success.content}"
        )
    }

    @Test
    fun `analyzeCompose accepts valid distinct keys in LazyColumn`() {
        val snippet = """
            data class WordItem(val id: String, val text: String)

            @Composable
            fun WordList(words: List<WordItem>, modifier: Modifier = Modifier) {
                LazyColumn(modifier = modifier) {
                    item(key = "header") {
                        Text("Header")
                    }
                    items(words, key = { it.id }) { word ->
                        Text(word.text)
                    }
                    item(key = "footer") {
                        Text("Footer")
                    }
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(
            success.content.contains("Duplicate key"),
            "valid distinct keys should not report duplicate: ${success.content}",
        )
        assertFalse(
            success.content.contains("constant literal"),
            "dynamic key lambda should not report constant: ${success.content}",
        )
        assertFalse(
            success.content.contains("does not specify a `key` parameter"),
            "explicit key should not report missing: ${success.content}",
        )
    }

    @Test
    fun `analyzeCompose detects mutated identifier used in Lazy layout item key`() {
        val snippet = """
            @Composable
            fun PackScreen(packs: List<Pack>, modifier: Modifier = Modifier) {
                LazyColumn(modifier = modifier) {
                    packs.forEach { pack ->
                        item(key = "pack_${'$'}{pack.id}") { Text(pack.name) }
                        var globalIndex = 0
                        pack.levelIds.chunked(5).forEach { row ->
                            item(key = "pack_${'$'}{pack.id}_row_${'$'}globalIndex") {
                                globalIndex++
                                Text("row")
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(
            success.content.contains("globalIndex") &&
                (success.content.contains("mutated inside the layout") || success.content.contains("mutated")),
            "expected finding for key depending on mutated identifier in: ${success.content}",
        )
        assertTrue(
            success.content.contains("IllegalArgumentException"),
            "expected warning to mention runtime IllegalArgumentException: ${success.content}",
        )
    }

    @Test
    fun `analyzeCompose emits missing-key advisory at most once per lazy container`() {
        val snippet = """
            @Composable
            fun MultiList(listA: List<String>, listB: List<String>, modifier: Modifier = Modifier) {
                LazyColumn(modifier = modifier) {
                    items(listA) { Text(it) }
                    items(listB) { Text(it) }
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        val occurrences = success.content.split("does not specify a `key` parameter").size - 1
        assertEquals(
            1,
            occurrences,
            "missing-key advisory should only fire once per lazy container: ${success.content}",
        )
    }

    @Test
    fun `analyzeCompose does not report missing key on item singleton calls`() {
        val snippet = """
            @Composable
            fun SingleItemScreen(modifier: Modifier = Modifier) {
                LazyColumn(modifier = modifier) {
                    item {
                        Text("Header")
                    }
                }
            }
        """.trimIndent()

        val result = analyzer.analyzeCompose(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(
            success.content.contains("does not specify a `key` parameter"),
            "item singleton without key should not be reported: ${success.content}",
        )
    }

    @Test
    fun `analyzeWorkspace scans kt files and reports Compose findings`() {
        val tempDir = java.nio.file.Files.createTempDirectory("kmcp-compose-ws-test")
        try {
            val srcDir = tempDir.resolve("src/main/kotlin/ui")
            java.nio.file.Files.createDirectories(srcDir)
            val composeFile = srcDir.resolve("MyList.kt")
            java.nio.file.Files.writeString(
                composeFile,
                """
                @Composable
                fun MyList(modifier: Modifier = Modifier) {
                    LazyColumn(modifier = modifier) {
                        item(key = "fixed") { Text("1") }
                        item(key = "fixed") { Text("2") }
                    }
                }
                """.trimIndent(),
            )

            val result = analyzer.analyzeWorkspace(tempDir.toString())
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertTrue(
                success.content.contains("Duplicate key `\"fixed\"`") &&
                    success.content.contains("MyList.kt"),
                "expected findings with file path in: ${success.content}",
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
