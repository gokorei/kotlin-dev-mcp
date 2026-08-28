@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.semantic

import com.gokorei.kotlinmcp.execution.SnippetCompiler
import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/**
 * Manages cached [KotlinCoreEnvironment] and [K2AnalysisSession] instances for deep semantic analysis.
 * Capped at [maxSessions] entries with thread-safe LRU eviction and [ENABLE_SEMANTIC] configuration gating.
 */
class KtSessionProvider(
    private val maxSessions: Int = 4,
    private val enableSemantic: Boolean = isSemanticEnabledByDefault()
) : Disposable {

    private val logger = KotlinLogging.logger {}

    private val lock = Any()

    private class CachedEnv(
        val disposable: Disposable,
        val environment: KotlinCoreEnvironment,
        val psiFactory: KtPsiFactory
    ) {
        var activeLeases = 0
        var isEvicted = false

        fun retain() {
            activeLeases++
        }

        fun release() {
            activeLeases--
            if (activeLeases <= 0 && isEvicted) {
                runCatching { Disposer.dispose(disposable) }
            }
        }
    }

    private val sessionCache = object : LinkedHashMap<String, CachedEnv>(maxSessions, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEnv>?): Boolean {
            val shouldRemove = size > maxSessions
            if (shouldRemove && eldest != null) {
                val env = eldest.value
                env.isEvicted = true
                if (env.activeLeases <= 0) {
                    runCatching { Disposer.dispose(env.disposable) }
                }
            }
            return shouldRemove
        }
    }

    val cachedSessionCount: Int
        get() = synchronized(lock) { sessionCache.size }

    @Suppress("DEPRECATION", "DEPRECATION_ERROR", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
    @OptIn(org.jetbrains.kotlin.K1Deprecation::class)
    fun acquireSession(code: String, classpath: List<String> = emptyList()): K2AnalysisSession? {
        if (!enableSemantic) return null

        val cpKey = computeClasspathKey(classpath)
        val cachedEnv = synchronized(lock) {
            val env = sessionCache.computeIfAbsent(cpKey) { key ->
                createEnvironment(classpath, key)
            }
            env.retain()
            env
        }

        return try {
            val hash = (code.hashCode() and 0x7fffffff).toString(16)
            val nano = System.nanoTime()
            val ktFile = cachedEnv.psiFactory.createFile("SemanticSnippet_${hash}_$nano.kt", code)

            val trace = NoScopeRecordCliBindingTrace(cachedEnv.environment.project)
            val scope = org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope.allScope(cachedEnv.environment.project)
            val result = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                cachedEnv.environment.project,
                listOf(ktFile),
                trace,
                cachedEnv.environment.configuration,
                { s -> cachedEnv.environment.createPackagePartProvider(s) },
                { storageManager, files ->
                    org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory(storageManager, files)
                },
                scope
            )

            K2AnalysisSession(
                file = ktFile,
                bindingContext = result.bindingContext,
                moduleDescriptor = result.moduleDescriptor
            )
        } catch (e: Throwable) {
            logger.warn(e) { "KtSessionProvider.acquireSession failed; falling back to syntactic PSI: ${e.message}" }
            val fallbackFile = K2SnippetFrontend.parsePsi(code)
            fallbackFile?.let { K2AnalysisSession(file = it) }
        } finally {
            synchronized(lock) {
                cachedEnv.release()
            }
        }
    }

    private fun createEnvironment(classpath: List<String>, key: String): CachedEnv {
        val disposable = Disposer.newDisposable("KtSessionProvider.env.$key")
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, org.jetbrains.kotlin.cli.common.messages.MessageCollector.NONE)
            put(CommonConfigurationKeys.MODULE_NAME, "semantic_module_$key")
        }

        val effectiveClasspath = (classpath + SnippetCompiler.resolveDefaultImports(System.getProperty("java.class.path").orEmpty()))
            .filter { it.isNotBlank() }
            .distinct()
            .map { File(it) }
            .filter { it.exists() }

        if (effectiveClasspath.isNotEmpty()) {
            configuration.addJvmClasspathRoots(effectiveClasspath)
        }

        val env = KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        val psiFactory = KtPsiFactory(env.project)

        return CachedEnv(disposable, env, psiFactory)
    }

    private fun computeClasspathKey(classpath: List<String>): String {
        if (classpath.isEmpty()) return "default"
        val sorted = classpath.sorted().joinToString(";")
        val digest = MessageDigest.getInstance("SHA-256").digest(sorted.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    override fun dispose() {
        synchronized(lock) {
            sessionCache.values.forEach { env ->
                runCatching { Disposer.dispose(env.disposable) }
            }
            sessionCache.clear()
        }
    }

    companion object {
        fun isSemanticEnabledByDefault(): Boolean {
            val prop = System.getProperty("kmcp.enable_semantic")
            val env = System.getenv("ENABLE_SEMANTIC")
            return (prop ?: env)?.toBooleanStrictOrNull() ?: true
        }
    }
}
