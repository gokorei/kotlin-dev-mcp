package com.gokorei.kotlinmcp.semantic.analyzers

import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Validates Kotlin Multiplatform `expect` and `actual` declarations for signature alignment and completeness.
 */
class ExpectActualAnalyzer {

    fun analyze(session: K2AnalysisSession): KotlinMcpResult {
        val file = session.file
        val findings = mutableListOf<String>()

        val expectClasses = mutableMapOf<String, KtClass>()
        val actualClasses = mutableMapOf<String, KtClass>()
        val expectFunctions = mutableMapOf<String, KtNamedFunction>()
        val actualFunctions = mutableMapOf<String, KtNamedFunction>()

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitClass(klass: KtClass) {
                super.visitClass(klass)
                val name = klass.name ?: return
                if (klass.hasModifier(KtTokens.EXPECT_KEYWORD)) {
                    expectClasses[name] = klass
                }
                if (klass.hasModifier(KtTokens.ACTUAL_KEYWORD)) {
                    actualClasses[name] = klass
                }
            }

            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                val name = function.name ?: return
                // Top-level or member
                if (function.hasModifier(KtTokens.EXPECT_KEYWORD)) {
                    expectFunctions[name] = function
                }
                if (function.hasModifier(KtTokens.ACTUAL_KEYWORD)) {
                    actualFunctions[name] = function
                }
            }
        })

        // Compare expect vs actual classes
        for ((name, expectClass) in expectClasses) {
            val actualClass = actualClasses[name]
            if (actualClass == null) {
                findings.add("⚠️ `expect class $name`: Missing corresponding `actual class $name` declaration in the target platform module.")
                continue
            }

            // Compare member functions
            fun signatureOf(fn: KtNamedFunction): Pair<String, List<String>> {
                val pTypes = fn.valueParameters.map { it.typeReference?.text?.trim() ?: "Any?" }
                return Pair(fn.name.orEmpty(), pTypes)
            }

            val expectMemberFns = expectClass.body?.functions.orEmpty().associateBy { signatureOf(it) }
            val actualMemberFns = actualClass.body?.functions.orEmpty().associateBy { signatureOf(it) }

            for ((sig, expectFn) in expectMemberFns) {
                val (fnName, params) = sig
                if (fnName.isBlank()) continue
                val actualFn = actualMemberFns[sig]
                if (actualFn == null) {
                    findings.add("⚠️ `actual class $name`: Missing implementation of expected member function `fun $fnName(${params.joinToString(", ")})`.")
                } else {
                    val expectRet = expectFn.typeReference?.text?.trim() ?: "Unit"
                    val actualRet = actualFn.typeReference?.text?.trim() ?: "Unit"
                    if (expectRet != actualRet) {
                        findings.add("⚠️ `actual class $name`: Return type mismatch for `fun $fnName(${params.joinToString(", ")})`. Expected `$expectRet` but actual is `$actualRet`.")
                    }
                    if (expectFn.hasModifier(KtTokens.SUSPEND_KEYWORD) != actualFn.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
                        findings.add("⚠️ `actual class $name`: Suspend modifier mismatch for `fun $fnName()`. Expect and actual must both be suspend or non-suspend.")
                    }
                }
            }
        }

        // Compare top-level expect vs actual functions
        fun topSignatureOf(fn: KtNamedFunction): Pair<String, List<String>> {
            val pTypes = fn.valueParameters.map { it.typeReference?.text?.trim() ?: "Any?" }
            return Pair(fn.name.orEmpty(), pTypes)
        }

        val expectTopFns = expectFunctions.values.associateBy { topSignatureOf(it) }
        val actualTopFns = actualFunctions.values.associateBy { topSignatureOf(it) }

        for ((sig, expectFn) in expectTopFns) {
            val (fnName, params) = sig
            if (fnName.isBlank()) continue
            val actualFn = actualTopFns[sig]
            if (actualFn == null) {
                findings.add("⚠️ `expect fun $fnName(${params.joinToString(", ")})`: Missing corresponding `actual fun` declaration in the target platform module.")
            } else {
                val expectRet = expectFn.typeReference?.text?.trim() ?: "Unit"
                val actualRet = actualFn.typeReference?.text?.trim() ?: "Unit"
                if (expectRet != actualRet) {
                    findings.add("⚠️ `actual fun $fnName(${params.joinToString(", ")})`: Return type mismatch. Expected `$expectRet` but actual is `$actualRet`.")
                }
                if (expectFn.hasModifier(KtTokens.SUSPEND_KEYWORD) != actualFn.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
                    findings.add("⚠️ `actual fun $fnName`: Suspend modifier mismatch. Expect and actual must both be suspend or non-suspend.")
                }
            }
        }

        val totalExpect = expectClasses.size + expectFunctions.size
        val content = buildString {
            appendLine("# Kotlin Multiplatform Expect / Actual Analysis")
            if (findings.isNotEmpty()) {
                appendLine("Found ${findings.size} mismatch(es) across $totalExpect expect declaration(s):")
                appendLine()
                findings.forEach { appendLine("- $it") }
            } else if (totalExpect > 0) {
                appendLine("✅ All $totalExpect `expect` declaration(s) have matching, type-compatible `actual` implementations.")
            } else {
                appendLine("ℹ️ No `expect` / `actual` multiplatform declarations found in snippet.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.size.toString(), "expectDeclarationsCount" to totalExpect.toString())
        )
    }
}
