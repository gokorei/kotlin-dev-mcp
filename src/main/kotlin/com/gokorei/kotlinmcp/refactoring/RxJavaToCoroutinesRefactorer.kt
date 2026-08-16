package com.gokorei.kotlinmcp.refactoring

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Strategy component for RxJava stream types (Observable, Flowable, Single, Maybe, Completable, Disposable)
 * conversion to modern Kotlin Coroutines (Flow, suspend, Result, Job) via PSI AST analysis.
 */
class RxJavaToCoroutinesRefactorer {

    fun convertRxJavaToCoroutines(code: String): KotlinMcpResult {
        return migrateRxJavaToCoroutines(code)
    }

    fun migrateRxJavaToCoroutines(code: String): KotlinMcpResult {
        val mappings = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        val detectedTypes = mutableSetOf<String>()
        val detectedOps = mutableSetOf<String>()

        psi?.accept(object : KtTreeVisitorVoid() {
            override fun visitUserType(type: KtUserType) {
                val name = type.referencedName.orEmpty()
                if (name in setOf("Observable", "Flowable", "Single", "Maybe", "Completable", "Disposable")) {
                    detectedTypes.add(name)
                }
                super.visitUserType(type)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text.orEmpty()
                val receiver = (expression.parent as? KtDotQualifiedExpression)?.receiverExpression?.text.orEmpty()

                if (receiver in setOf("Observable", "Flowable", "Single", "Maybe", "Completable") || callee in setOf("create", "just", "fromCallable")) {
                    detectedTypes.add(receiver.ifEmpty { callee })
                }
                if (callee in setOf("subscribeOn", "observeOn", "flatMap", "map", "subscribe", "dispose", "addTo")) {
                    detectedOps.add(callee)
                }
                super.visitCallExpression(expression)
            }
        })

        fun discover(type: String, suggestion: String) {
            if (detectedTypes.contains(type) || code.contains(type)) {
                mappings.add("`$type` → $suggestion")
            }
        }
        discover("Observable", "`Flow<T>` (cold stream) — `flow { emit(...) }` / `asFlow()`")
        discover("Flowable", "`Flow<T>` with backpressure handling via `buffer()`/`conflate()`")
        discover("Single", "`suspend fun foo(): T` or `Result<T>` for one-shot results")
        discover("Maybe", "`suspend fun foo(): T?` (empty == null)")
        discover("Completable", "`suspend fun foo()` (Unit result; errors as exceptions/Result)")
        discover("Disposable", "`Job` (structured concurrency; cancelled with the parent scope)")

        if (detectedTypes.contains("Optional") || detectedTypes.contains("Maybe")) {
            mappings.add("`Optional<T>` / `Maybe<T>` wrapper → nullable `T?` (or `Result<T>` when absence must carry meaning)")
        }

        val opMappings = mapOf(
            "subscribeOn" to "withContext(Dispatchers.IO) { } (inject dispatcher instead of hardcoding)",
            "observeOn" to "`withContext` on the UI/consumer context",
            "flatMap" to "`flatMapConcat { }` / `flatMapMerge { }` (Flow)",
            "map" to "`map { }` (Flow) or plain `map { }` on collections",
            "subscribe" to "`collect { }` (Flow) / `await()` (Deferred)",
            "dispose" to "`Job.cancel()` — drop manual subscription management in favor of structured concurrency"
        )
        opMappings.forEach { (rx, kt) ->
            if (detectedOps.contains(rx) || code.contains("$rx(")) mappings.add("operator `$rx` → `$kt`")
        }

        val stage = if (psi != null) {
            val rangesToReplace = mutableListOf<Pair<org.jetbrains.kotlin.com.intellij.openapi.util.TextRange, String>>()

            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    val callee = expression.calleeExpression?.text.orEmpty()
                    val parentDot = expression.parent as? KtDotQualifiedExpression
                    val receiver = parentDot?.receiverExpression?.text.orEmpty()

                    if (receiver == "Observable" && callee == "create") {
                        rangesToReplace.add(parentDot!!.textRange to "flow { emit")
                    } else if (receiver in setOf("Observable", "Flowable") && callee == "just") {
                        rangesToReplace.add(parentDot!!.textRange to "flowOf")
                    } else if (receiver == "Single" && callee == "just") {
                        rangesToReplace.add(parentDot!!.textRange to "Result.success")
                    } else if (receiver == "Single" && callee == "fromCallable") {
                        rangesToReplace.add(parentDot!!.textRange to "withContext(Dispatchers.IO)")
                    } else if (receiver == "Completable" && callee == "fromAction") {
                        rangesToReplace.add(parentDot!!.textRange to "withContext(Dispatchers.IO)")
                    } else if (callee == "subscribeOn") {
                        expression.textRange.let { rangesToReplace.add(it to "/* withContext(Dispatchers.IO) */") }
                    } else if (callee == "observeOn") {
                        expression.textRange.let { rangesToReplace.add(it to "/* withContext(Dispatchers.Main) */") }
                    } else if (callee == "subscribe") {
                        expression.calleeExpression?.textRange?.let { rangesToReplace.add(it to "collect") }
                    } else if (callee == "dispose") {
                        expression.calleeExpression?.textRange?.let { rangesToReplace.add(it to "/* parent-scope cancellation */") }
                    }
                    super.visitCallExpression(expression)
                }
            })

            if (rangesToReplace.isNotEmpty()) {
                val sorted = rangesToReplace.distinctBy { it.first }.sortedByDescending { it.first.startOffset }
                val sb = StringBuilder(code)
                for ((range, replacement) in sorted) {
                    if (range.startOffset in sb.indices && range.endOffset <= sb.length) {
                        sb.replace(range.startOffset, range.endOffset, replacement)
                    }
                }
                sb.toString()
            } else {
                code
            }
        } else {
            code
        }

        val rewritten = StringBuilder("# RxJava → Kotlin Coroutines (PSI AST Analysis)\n\n")
        if (mappings.isEmpty() && code.isNotBlank()) {
            rewritten.appendLine("No RxJava stream types detected. This refactoring targets `Observable`, `Single`, `Maybe`, `Completable`, `Disposable`, and custom `Optional` wrappers.")
            rewritten.appendLine()
            rewritten.appendLine("Code left unchanged.")
        } else {
            rewritten.appendLine("Per-type mapping (PSI AST analysis):")
            mappings.distinct().forEach { rewritten.appendLine(" - $it") }
            if (stage != code) {
                rewritten.appendLine()
                rewritten.appendLine("## Initial rewrite sketch (verify each step with kotlin_check_snippet):")
                rewritten.appendLine("```kotlin")
                rewritten.appendLine(stage)
                rewritten.appendLine("```")
            }
        }

        return KotlinMcpResult.Success(
            content = rewritten.toString().trimEnd(),
            metadata = mapOf("mappingCount" to mappings.distinct().size.toString())
        )
    }
}
