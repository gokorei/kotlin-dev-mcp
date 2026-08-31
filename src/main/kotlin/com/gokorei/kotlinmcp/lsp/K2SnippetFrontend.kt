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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Result of a frontend compiler analysis pass on a snippet.
 *
 * @property file The parsed [KtFile] AST.
 * @property bindingContext Type-resolved binding context mapping AST nodes to descriptors.
 * @property moduleDescriptor Resolved module descriptor, if analysis succeeded.
 */
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
    private val lifecycleLock = ReentrantReadWriteLock()

    @Volatile
    private var rootDisposable = Disposer.newDisposable("K2SnippetFrontend.root")

    @Volatile
    private var cachedEnvironment: KotlinCoreEnvironment? = null

    @Volatile
    private var cachedPsiFactory: KtPsiFactory? = null

    /**
     * The active [KotlinCoreEnvironment] initialized with default snippet classpath roots.
     */
    val environment: KotlinCoreEnvironment
        get() {
            var env = cachedEnvironment
            if (env == null) {
                synchronized(this) {
                    env = cachedEnvironment
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
                }
            }
            return env!!
        }

    /**
     * The active [KtPsiFactory] bound to the current environment's project.
     */
    val psiFactory: KtPsiFactory
        get() {
            var factory = cachedPsiFactory
            if (factory == null) {
                synchronized(this) {
                    factory = cachedPsiFactory
                    if (factory == null) {
                        factory = KtPsiFactory(environment.project)
                        cachedPsiFactory = factory
                    }
                }
            }
            return factory!!
        }

    /**
     * The root [Disposable] managing compiler environment lifecycle.
     */
    val currentRootDisposable: Disposable
        get() = rootDisposable

    @Volatile
    private var disposed = false

    /**
     * Whether the frontend has been disposed.
     */
    val isDisposed: Boolean
        get() = disposed

    /**
     * Disposes the compiler environment and releases native/memory resources.
     */
    fun dispose() {
        lifecycleLock.write {
            if (!disposed) {
                Disposer.dispose(rootDisposable)
                disposed = true
                cachedEnvironment = null
                cachedPsiFactory = null
            }
        }
    }

    /**
     * Recycles the compiler environment disposable and resets cached factories.
     */
    fun resetEnvironment() {
        lifecycleLock.write {
            if (!disposed) {
                Disposer.dispose(rootDisposable)
                rootDisposable = Disposer.newDisposable("K2SnippetFrontend.root")
                cachedEnvironment = null
                cachedPsiFactory = null
            }
        }
    }

    /**
     * Parses Kotlin source code into an in-memory [KtFile] PSI AST.
     *
     * @param code Kotlin source snippet.
     * @return Parsed [KtFile] or `null` if parsing fails.
     */
    fun parsePsi(code: String): KtFile? {
        return lifecycleLock.read {
            if (disposed) return@read null
            try {
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
    }

    /**
     * Performs a full frontend compiler analysis pass and resolves symbols to descriptors.
     *
     * @param code Kotlin source snippet.
     * @param extraFiles Additional [KtFile] instances to include in the analysis scope.
     * @return [K2AnalysisSession] containing the [BindingContext] and [ModuleDescriptor].
     */
    @Suppress("DEPRECATION", "DEPRECATION_ERROR", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
    @OptIn(org.jetbrains.kotlin.K1Deprecation::class)
    fun analyzeSession(code: String, extraFiles: List<KtFile> = emptyList()): K2AnalysisSession? {
        return lifecycleLock.read {
            if (disposed) return@read null
            val file = parsePsi(code) ?: return@read null
            val allFiles = listOf(file) + extraFiles
            try {
                val trace = NoScopeRecordCliBindingTrace(environment.project)
                val scope = org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope.allScope(environment.project)
                val result = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                    environment.project,
                    allFiles,
                    trace,
                    environment.configuration,
                    { s -> environment.createPackagePartProvider(s) },
                    { storageManager, files ->
                        org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory(storageManager, files)
                    },
                    scope
                )
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
}
