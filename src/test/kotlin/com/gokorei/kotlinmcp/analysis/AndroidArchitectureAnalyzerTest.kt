package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AndroidArchitectureAnalyzerTest {

    private lateinit var codeAnalysisService: CodeAnalysisService
    private lateinit var coroutinesSafetyAnalyzer: CoroutinesSafetyAnalyzer

    @BeforeEach
    fun setUp() {
        codeAnalysisService = DefaultCodeAnalysisService()
        coroutinesSafetyAnalyzer = CoroutinesSafetyAnalyzer()
    }

    @Test
    fun `inspect_symbol flags Activity and View retention in ViewModel`() {
        val snippet = """
            class UserViewModel(
                private val activity: MainActivity,
                private val customView: View
            ) : ViewModel() {
                var currentContext: Context? = null
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.INSPECT_SYMBOL, snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Memory leak risk"), "expected memory leak warning: ${success.content}")
        assertTrue(success.content.contains("MainActivity"))
        assertTrue(success.content.contains("customView"))
    }

    @Test
    fun `inspect_symbol does not flag ApplicationContext or AndroidViewModel in ViewModel`() {
        val snippet = """
            class UserViewModel(
                @ApplicationContext private val context: Context,
                private val repository: UserRepository
            ) : AndroidViewModel(application) {
                val title = "Profile"
            }
        """.trimIndent()

        val result = codeAnalysisService.execute(CodeAnalysisAction.INSPECT_SYMBOL, snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("Memory leak risk"), "ApplicationContext should not be flagged: ${success.content}")
    }

    @Test
    fun `explain_coroutines flags non-viewModelScope launch in ViewModel`() {
        val snippet = """
            class UserViewModel : ViewModel() {
                fun loadData() {
                    CoroutineScope(Dispatchers.IO).launch {
                        fetchUser()
                    }
                }
            }
        """.trimIndent()

        val result = coroutinesSafetyAnalyzer.explainCoroutines(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("viewModelScope"), "expected viewModelScope recommendation: ${success.content}")
    }

    @Test
    fun `explain_coroutines flags flow collection in Activity without repeatOnLifecycle`() {
        val snippet = """
            class MainActivity : AppCompatActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    lifecycleScope.launch {
                        viewModel.state.collect { state ->
                            updateUi(state)
                        }
                    }
                }
            }
        """.trimIndent()

        val result = coroutinesSafetyAnalyzer.explainCoroutines(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("repeatOnLifecycle"), "expected repeatOnLifecycle recommendation: ${success.content}")
    }

    @Test
    fun `explain_coroutines allows flowWithLifecycle chaining in Activity`() {
        val snippet = """
            class MainActivity : AppCompatActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    lifecycleScope.launch {
                        viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                            updateUi(state)
                        }
                    }
                }
            }
        """.trimIndent()

        val result = coroutinesSafetyAnalyzer.explainCoroutines(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"), "flowWithLifecycle should satisfy lifecycle awareness: ${success.content}")
    }

    @Test
    fun `explain_coroutines allows nested launches inside viewModelScope launch`() {
        val snippet = """
            class UserViewModel : ViewModel() {
                fun loadData() {
                    viewModelScope.launch {
                        launch {
                            fetchUser()
                        }
                        async {
                            fetchStats()
                        }
                    }
                }
            }
        """.trimIndent()

        val result = coroutinesSafetyAnalyzer.explainCoroutines(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("without `viewModelScope`"), "nested launches inside viewModelScope should not be warned: ${success.content}")
    }
}
