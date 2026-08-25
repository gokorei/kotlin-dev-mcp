package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AndroidDiAnalysisTest {

    private lateinit var libraryService: LibraryAnalysisService

    @BeforeEach
    fun setUp() {
        libraryService = DefaultLibraryAnalysisService()
    }

    @Test
    fun `analyze_android_di flags ViewModel with Inject constructor missing HiltViewModel`() {
        val snippet = """
            class UserViewModel @Inject constructor(
                private val repository: UserRepository
            ) : ViewModel()
        """.trimIndent()

        val result = libraryService.execute(LibraryAnalysisAction.ANALYZE_ANDROID_DI, snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("@HiltViewModel"), "expected @HiltViewModel warning in: ${success.content}")
    }

    @Test
    fun `analyze_android_di flags Activity with injected fields missing AndroidEntryPoint`() {
        val snippet = """
            class MainActivity : AppCompatActivity() {
                @Inject
                lateinit var analytics: AnalyticsTracker
            }
        """.trimIndent()

        val result = libraryService.execute(LibraryAnalysisAction.ANALYZE_ANDROID_DI, snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("@AndroidEntryPoint"), "expected @AndroidEntryPoint warning in: ${success.content}")
    }

    @Test
    fun `analyze_android_di flags Module missing InstallIn`() {
        val snippet = """
            @Module
            object NetworkModule {
                @Provides
                fun provideOkHttpClient(): OkHttpClient = OkHttpClient()
            }
        """.trimIndent()

        val result = libraryService.execute(LibraryAnalysisAction.ANALYZE_ANDROID_DI, snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("@InstallIn"), "expected @InstallIn warning in: ${success.content}")
    }

    @Test
    fun `analyze_android_di succeeds on correctly annotated Hilt architecture`() {
        val snippet = """
            @HiltViewModel
            class UserViewModel @Inject constructor(
                private val repository: UserRepository
            ) : ViewModel()

            @AndroidEntryPoint
            class MainActivity : AppCompatActivity() {
                @Inject
                lateinit var analytics: AnalyticsTracker
            }

            @Module
            @InstallIn(SingletonComponent::class)
            object NetworkModule
        """.trimIndent()

        val result = libraryService.execute(LibraryAnalysisAction.ANALYZE_ANDROID_DI, snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("⚠️"), "valid Hilt DI should have no warnings: ${success.content}")
    }
}
