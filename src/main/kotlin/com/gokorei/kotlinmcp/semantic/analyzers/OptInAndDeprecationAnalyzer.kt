package com.gokorei.kotlinmcp.semantic.analyzers

import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.jetbrains.kotlin.psi.*

/**
 * Validates `@RequiresOptIn` / `@OptIn` propagation and extracts `@Deprecated(ReplaceWith)` migrations.
 */
class OptInAndDeprecationAnalyzer {

    fun analyzeOptIn(session: K2AnalysisSession): KotlinMcpResult {
        val file = session.file
        val findings = mutableListOf<String>()

        // Discover custom opt-in marker annotations
        val optInMarkers = mutableSetOf<String>()
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitClass(klass: KtClass) {
                super.visitClass(klass)
                if (klass.isAnnotation()) {
                    val isOptInMarker = klass.annotationEntries.any {
                        it.shortName?.asString() == "RequiresOptIn"
                    }
                    if (isOptInMarker) {
                        klass.name?.let { optInMarkers.add(it) }
                    }
                }
            }
        })

        // Track declarations guarded by opt-in markers
        val guardedDeclarations = mutableMapOf<String, String>() // declName -> markerName
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                val fnName = function.name ?: return
                for (entry in function.annotationEntries) {
                    val name = entry.shortName?.asString() ?: continue
                    if (name in optInMarkers) {
                        guardedDeclarations[fnName] = name
                    }
                }
            }
        })

        // Check call sites
        fun hasOptInFor(caller: KtNamedFunction, marker: String): Boolean {
            val fileAnnotations = file.fileAnnotationList?.annotationEntries.orEmpty()
            for (entry in fileAnnotations) {
                if (entry.shortName?.asString() == "OptIn") {
                    val args = entry.valueArguments.mapNotNull { it.getArgumentExpression()?.text }
                    if (args.isEmpty() || args.any { it.contains(marker) }) return true
                }
            }

            var parent = caller.parent
            while (parent != null) {
                if (parent is KtClassOrObject) {
                    for (entry in parent.annotationEntries) {
                        if (entry.shortName?.asString() == "OptIn") {
                            val args = entry.valueArguments.mapNotNull { it.getArgumentExpression()?.text }
                            if (args.isEmpty() || args.any { it.contains(marker) }) return true
                        }
                        if (entry.shortName?.asString() == marker) return true
                    }
                }
                parent = parent.parent
            }

            for (entry in caller.annotationEntries) {
                if (entry.shortName?.asString() == "OptIn") {
                    val args = entry.valueArguments.mapNotNull { it.getArgumentExpression()?.text }
                    if (args.isEmpty() || args.any { it.contains(marker) }) return true
                }
                if (entry.shortName?.asString() == marker) return true
            }

            return false
        }

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                val callerName = function.name ?: "anonymous"

                function.accept(object : KtTreeVisitorVoid() {
                    override fun visitCallExpression(expression: KtCallExpression) {
                        super.visitCallExpression(expression)
                        val callee = expression.calleeExpression?.text ?: return
                        val marker = guardedDeclarations[callee]
                        if (marker != null && !hasOptInFor(function, marker)) {
                            findings.add("⚠️ `fun $callerName`: Calls experimental `$callee()` which requires `@$marker`. Add `@OptIn($marker::class)` to the calling function, class, or file.")
                        }
                    }
                })
            }
        })

        val content = buildString {
            appendLine("# Kotlin Experimental API & @OptIn Analysis")
            if (findings.isNotEmpty()) {
                appendLine("Found ${findings.size} un-opted experimental API usage(s):")
                appendLine()
                findings.forEach { appendLine("- $it") }
            } else {
                appendLine("✅ All experimental API call sites are properly annotated with `@OptIn`.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.size.toString())
        )
    }

    fun analyzeDeprecated(session: K2AnalysisSession): KotlinMcpResult {
        val file = session.file
        val deprecatedDeclarations = mutableMapOf<String, Pair<String, String?>>() // name -> Pair(message, replaceWithExpr)

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                val fnName = function.name ?: return
                for (entry in function.annotationEntries) {
                    if (entry.shortName?.asString() == "Deprecated") {
                        val args = entry.valueArguments
                        val message = args.firstOrNull()?.getArgumentExpression()?.text?.trim('"', '\'').orEmpty()
                        var replaceWith: String? = null
                        for (arg in args) {
                            val expr = arg.getArgumentExpression()
                            if (expr is KtCallExpression && expr.calleeExpression?.text == "ReplaceWith") {
                                replaceWith = expr.valueArguments.firstOrNull()?.getArgumentExpression()?.text?.trim('"', '\'')
                            }
                        }
                        deprecatedDeclarations[fnName] = Pair(message, replaceWith)
                    }
                }
            }
        })

        val findings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                val callee = expression.calleeExpression?.text ?: return
                val depInfo = deprecatedDeclarations[callee]
                if (depInfo != null) {
                    val (msg, replaceWith) = depInfo
                    val finding = "⚠️ Deprecated call `$callee()`: $msg"
                    findings.add(finding)
                    if (!replaceWith.isNullOrBlank()) {
                        suggestions.add("Replace `$callee(...)` with `$replaceWith`")
                    }
                }
            }
        })

        val content = buildString {
            appendLine("# Kotlin @Deprecated & Migration Analysis")
            if (findings.isNotEmpty()) {
                appendLine("Found ${findings.size} invocation(s) of deprecated APIs:")
                appendLine()
                findings.forEach { appendLine("- $it") }
                if (suggestions.isNotEmpty()) {
                    appendLine()
                    appendLine("### Suggested Replacements:")
                    suggestions.distinct().forEach { appendLine("- $it") }
                }
            } else if (deprecatedDeclarations.isNotEmpty()) {
                appendLine("ℹ️ Found ${deprecatedDeclarations.size} deprecated declaration(s), but no call sites in this snippet.")
            } else {
                appendLine("✅ No deprecated API usages or declarations found in snippet.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.size.toString())
        )
    }
}
