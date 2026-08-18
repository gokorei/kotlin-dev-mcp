@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.execution.SnippetCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
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
                    addJvmClasspathRoots(
                        configuration,
                        SnippetCompiler.resolveDefaultImports(System.getProperty("java.class.path").orEmpty())
                            .filter { it.isNotBlank() }
                            .map { java.io.File(it) }
                    )
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

    /**
     * Registers library jars (kotlin-stdlib etc.) as JVM classpath roots so the
     * analysis resolves stdlib symbols. `JvmContentRootsKt.addJvmClasspathRoots`
     * is `internal` in the compiler, so it is invoked reflectively.
     */
    private fun addJvmClasspathRoots(configuration: CompilerConfiguration, roots: List<java.io.File>) {
        if (roots.isEmpty()) return
        try {
            val cls = Class.forName("org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt")
            val method = cls.getMethod("addJvmClasspathRoots", CompilerConfiguration::class.java, List::class.java)
            method.invoke(null, configuration, roots)
        } catch (e: Throwable) {
            System.err.println("K2SnippetFrontend could not register stdlib classpath roots: ${e.message}")
        }
    }

    @Volatile
    private var disposed = false

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { Disposer.dispose(rootDisposable) }
        cachedEnvironment = null
        cachedPsiFactory = null
    }

    @Synchronized
    fun resetEnvironment() {
        if (disposed) return
        runCatching { Disposer.dispose(rootDisposable) }
        rootDisposable = Disposer.newDisposable("K2SnippetFrontend.root")
        cachedEnvironment = null
        cachedPsiFactory = null
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
            System.err.println("K2SnippetFrontend.parsePsi FAILED for code:\n$code\nError: ${e.message}")
            null
        }
    }

    @Synchronized
    fun analyzeSession(code: String, extraFiles: List<KtFile> = emptyList()): K2AnalysisSession? {
        if (disposed) return null
        val file = parsePsi(code) ?: return null
        val allFiles = listOf(file) + extraFiles
        return try {
            val trace = NoScopeRecordCliBindingTrace(environment.project)
            val analyzeMethod = TopDownAnalyzerFacadeForJVM::class.java.methods
                .filter { it.name == "analyzeFilesWithJavaIntegration" }
                .minByOrNull { it.parameterCount } ?: error("No analyzeFilesWithJavaIntegration found")

            val provider = { scope: org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope ->
                environment.createPackagePartProvider(scope)
            }
            val declProviderFactory = { storageManager: org.jetbrains.kotlin.storage.StorageManager, files: Collection<KtFile> ->
                org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory(storageManager, files)
            }
            val scope = org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope.allScope(environment.project)

            val callArgs = mutableListOf<Any?>(
                environment.project,
                allFiles,
                trace,
                environment.configuration,
                provider
            )
            if (analyzeMethod.parameterCount >= 6) {
                callArgs.add(declProviderFactory)
            }
            if (analyzeMethod.parameterCount >= 7) {
                callArgs.add(scope)
            }

            val result = analyzeMethod.invoke(null, *callArgs.toTypedArray()) as org.jetbrains.kotlin.analyzer.AnalysisResult
            K2AnalysisSession(
                file = file,
                bindingContext = result.bindingContext,
                moduleDescriptor = result.moduleDescriptor
            )
        } catch (e: Throwable) {
            System.err.println("K2SnippetFrontend.analyzeSession fallback: ${e.message}")
            K2AnalysisSession(file)
        }
    }
}
