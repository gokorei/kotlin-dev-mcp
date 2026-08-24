@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.execution.SnippetCompiler
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.resolve.BindingContext

data class K2AnalysisSession(
    val file: KtFile,
    val bindingContext: BindingContext = BindingContext.EMPTY,
    val moduleDescriptor: ModuleDescriptor? = null
)

/**
 * K2 Frontend service for parsing and analyzing Kotlin code snippets
 * using the Kotlin compiler environment (`KotlinCoreEnvironment`), PSI parser (`KtPsiFactory`),
 * and frontend compiler analyzer (`TopDownAnalyzerFacadeForJVM`).
 */
object K2SnippetFrontend {

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var rootDisposable = Disposer.newDisposable("K2SnippetFrontend.root")

    @Volatile
    private var cachedEnvironment: KotlinCoreEnvironment? = null

    @Volatile
    private var cachedPsiFactory: KtPsiFactory? = null

    val environment: KotlinCoreEnvironment
        get() {
            synchronized(K2SnippetFrontend) {
                var env = cachedEnvironment
                if (env == null) {
                    val configuration = CompilerConfiguration().apply {
                        put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, org.jetbrains.kotlin.cli.common.messages.MessageCollector.NONE)
                        put(CommonConfigurationKeys.MODULE_NAME, "snippet_module")
                    }
                    val defaultRoots = SnippetCompiler.resolveDefaultImports(System.getProperty("java.class.path").orEmpty())
                        .filter { it.isNotBlank() }
                        .map { java.io.File(it) }
                    if (defaultRoots.isNotEmpty()) {
                        configuration.addJvmClasspathRoots(defaultRoots)
                    }
                    env = KotlinCoreEnvironment.createForProduction(
                        rootDisposable,
                        configuration,
                        EnvironmentConfigFiles.JVM_CONFIG_FILES
                    )
                    cachedEnvironment = env
                }
                return env
            }
        }

    val psiFactory: KtPsiFactory
        get() {
            synchronized(K2SnippetFrontend) {
                var factory = cachedPsiFactory
                if (factory == null) {
                    factory = KtPsiFactory(environment.project)
                    cachedPsiFactory = factory
                }
                return factory
            }
        }

    val currentRootDisposable: Disposable
        get() = rootDisposable

    @Volatile
    private var disposed = false

    val isDisposed: Boolean
        get() = disposed

    @Synchronized
    fun dispose() {
        if (!disposed) {
            Disposer.dispose(rootDisposable)
            disposed = true
            cachedEnvironment = null
            cachedPsiFactory = null
        }
    }

    @Synchronized
    fun resetEnvironment() {
        if (!disposed) {
            Disposer.dispose(rootDisposable)
            rootDisposable = Disposer.newDisposable("K2SnippetFrontend.root")
            cachedEnvironment = null
            cachedPsiFactory = null
        }
    }

    @Synchronized
    fun parsePsi(code: String): KtFile? {
        if (disposed) return null
        return try {
            val hash = (code.hashCode() and 0x7fffffff).toString(16)
            val nano = System.nanoTime()
            val ktFile = psiFactory.createFile("Snippet_${hash}_$nano.kt", code)
            val isScript = ktFile.script != null || ktFile.children.any { child ->
                child is org.jetbrains.kotlin.psi.KtScriptInitializer ||
                child is org.jetbrains.kotlin.psi.KtForExpression ||
                child is org.jetbrains.kotlin.psi.KtWhileExpression ||
                child is org.jetbrains.kotlin.psi.KtDoWhileExpression ||
                child is org.jetbrains.kotlin.psi.KtIfExpression ||
                child is org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
            }
            if (isScript) {
                psiFactory.createFile("Snippet_${hash}_$nano.kts", code)
            } else {
                ktFile
            }
        } catch (e: Throwable) {
            logger.warn(e) { "K2SnippetFrontend.parsePsi FAILED (length: ${code.length}): ${e.message}" }
            null
        }
    }

    @Synchronized
    @Suppress("DEPRECATION")
    @OptIn(org.jetbrains.kotlin.K1Deprecation::class)
    fun analyzeSession(code: String, extraFiles: List<KtFile> = emptyList()): K2AnalysisSession? {
        if (disposed) return null
        val file = parsePsi(code) ?: return null
        val allFiles = listOf(file) + extraFiles
        return try {
            val trace = NoScopeRecordCliBindingTrace(environment.project)
            val analyzeMethod = TopDownAnalyzerFacadeForJVM::class.java.methods
                .first { it.name == "analyzeFilesWithJavaIntegration" && it.parameterCount == 7 }
            val provider = { scope: org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope ->
                environment.createPackagePartProvider(scope)
            }
            val declProviderFactory = { storageManager: org.jetbrains.kotlin.storage.StorageManager, files: Collection<KtFile> ->
                org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory(storageManager, files)
            }
            val scope = org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope.allScope(environment.project)
            val result = analyzeMethod.invoke(
                null,
                environment.project,
                allFiles,
                trace,
                environment.configuration,
                provider,
                declProviderFactory,
                scope
            ) as org.jetbrains.kotlin.analyzer.AnalysisResult
            K2AnalysisSession(
                file = file,
                bindingContext = result.bindingContext,
                moduleDescriptor = result.moduleDescriptor
            )
        } catch (e: Throwable) {
            logger.warn(e) { "K2SnippetFrontend.analyzeSession fallback: ${e.message}" }
            K2AnalysisSession(file)
        }
    }
}
