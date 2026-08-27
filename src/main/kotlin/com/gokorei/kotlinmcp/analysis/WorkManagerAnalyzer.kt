package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.jetbrains.kotlin.psi.*

/**
 * K2 PSI AST analyzer for Android WorkManager [CoroutineWorker] and [ListenableWorker] implementations.
 *
 * Enforces:
 * 1. `@HiltWorker` + `@AssistedInject` constructor wiring when Hilt is used.
 * 2. `setForeground()` alignment with Android 14+ foreground service types and manifest declarations.
 * 3. Threading safety: flagging blocking I/O calls in `doWork()` lacking `withContext(Dispatchers.IO)`.
 */
class WorkManagerAnalyzer {

    fun analyze(code: String): KotlinMcpResult {
        if (code.isBlank()) {
            return KotlinMcpResult.Error(
                code = "INVALID_ARGUMENTS",
                message = "Source code snippet cannot be blank."
            )
        }

        val psi = K2SnippetFrontend.parsePsi(code)
            ?: return KotlinMcpResult.Error(
                code = "PARSE_ERROR",
                message = "Failed to parse Kotlin code snippet into AST."
            )

        val findings = mutableListOf<String>()

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitClass(klass: KtClass) {
                super.visitClass(klass)
                val className = klass.name ?: "AnonymousWorker"

                val validWorkerTypes = setOf(
                    "CoroutineWorker", "ListenableWorker", "Worker", "RxWorker",
                    "androidx.work.CoroutineWorker", "androidx.work.ListenableWorker",
                    "androidx.work.Worker", "androidx.work.RxWorker"
                )
                val isWorker = klass.superTypeListEntries.any { entry ->
                    val rawType = entry.typeReference?.text?.trim()?.substringBefore("<")?.trim() ?: ""
                    rawType in validWorkerTypes
                }

                if (!isWorker) return

                val annotations = klass.annotationEntries
                val hasHiltWorker = annotations.any { it.shortName?.asString() == "HiltWorker" }

                // Check constructor for AssistedInject
                val primaryConstructor = klass.primaryConstructor
                val constructorAnnotations = primaryConstructor?.annotationEntries.orEmpty()
                val hasAssistedInject = constructorAnnotations.any { it.shortName?.asString() == "AssistedInject" }

                val valueParams = primaryConstructor?.valueParameters.orEmpty()
                val hasAssistedParams = valueParams.any { param ->
                    param.annotationEntries.any { it.shortName?.asString() == "Assisted" }
                }

                if ((hasAssistedInject || hasAssistedParams) && !hasHiltWorker) {
                    findings.add("⚠️ `class $className`: Uses `@AssistedInject` / `@Assisted` constructor parameters but lacks `@HiltWorker` class annotation. Annotate with `@HiltWorker` to enable Hilt WorkManager injection.")
                }

                if (hasHiltWorker && !hasAssistedInject) {
                    findings.add("⚠️ `class $className`: Annotated with `@HiltWorker` but primary constructor lacks `@AssistedInject`. Declare constructor as `@AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters, ...)`.")
                }

                // Check member functions (doWork)
                for (function in klass.body?.functions.orEmpty()) {
                    if (function.name == "doWork") {
                        inspectDoWorkFunction(className, function, findings)
                    }
                }
            }
        })

        val content = buildString {
            appendLine("# Android WorkManager Architecture Analysis")
            if (findings.isNotEmpty()) {
                appendLine("Found ${findings.size} issue(s) or advisory items in worker declaration:")
                appendLine()
                findings.distinct().forEach { appendLine("- $it") }
            } else {
                appendLine("✅ Worker implementation follows modern architecture best practices.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.distinct().size.toString())
        )
    }

    private fun inspectDoWorkFunction(
        className: String,
        function: KtNamedFunction,
        findings: MutableList<String>
    ) {
        function.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                val callee = expression.calleeExpression?.text ?: return

                // 1. Foreground service check
                if (callee == "setForeground" || callee == "setForegroundAsync") {
                    findings.add("ℹ️ `class $className`: Calls `$callee()`. Ensure `<service android:name=\"androidx.work.impl.foreground.SystemForegroundService\" android:foregroundServiceType=\"...\" android:exported=\"false\" />` is declared in `AndroidManifest.xml` with appropriate `android:foregroundServiceType` and permissions for Android 14+ (SDK 34+).")
                }

                // 2. Blocking I/O calls check
                val isBlockingCall = callee == "sleep" || callee == "readBytes" || callee == "readText" ||
                    callee == "readLines" || callee == "writeBytes" || callee == "writeText" ||
                    callee == "openConnection" || callee == "execute" || callee == "getConnection"

                if (isBlockingCall) {
                    val isInsideWithContextIo = isInsideWithContext(expression)
                    if (!isInsideWithContextIo) {
                        findings.add("⚠️ Blocking operation `$callee(...)` detected inside `$className.doWork()` without `withContext(Dispatchers.IO)`. Wrap long-running or blocking I/O calls in `withContext(Dispatchers.IO) { ... }` to keep worker coroutines non-blocking.")
                    }
                }
            }
        })
    }

    private fun isInsideWithContext(element: KtElement): Boolean {
        var current: org.jetbrains.kotlin.com.intellij.psi.PsiElement? = element.parent
        while (current != null && current !is KtNamedFunction) {
            if (current is KtCallExpression) {
                val callee = current.calleeExpression?.text
                if (callee == "withContext") {
                    val argExpr = current.valueArguments.firstOrNull()?.getArgumentExpression()
                    val argText = argExpr?.text?.trim()
                    if (argText == "Dispatchers.IO" || argText == "kotlinx.coroutines.Dispatchers.IO") {
                        return true
                    }
                }
            }
            current = current.parent
        }
        return false
    }
}
