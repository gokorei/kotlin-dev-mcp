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
            val expectMemberFns = expectClass.body?.functions.orEmpty().associateBy { it.name }
            val actualMemberFns = actualClass.body?.functions.orEmpty().associateBy { it.name }

            for ((fnName, expectFn) in expectMemberFns) {
                if (fnName == null) continue
                val actualFn = actualMemberFns[fnName]
                if (actualFn == null) {
                    findings.add("⚠️ `actual class $name`: Missing implementation of expected member function `fun $fnName()`.")
                } else {
                    val expectRet = expectFn.typeReference?.text?.trim() ?: "Unit"
                    val actualRet = actualFn.typeReference?.text?.trim() ?: "Unit"
                    if (expectRet != actualRet) {
                        findings.add("⚠️ `actual class $name`: Return type mismatch for `fun $fnName()`. Expected `$expectRet` but actual is `$actualRet`.")
                    }
                }
            }
        }

        // Compare top-level expect vs actual functions
        for ((name, expectFn) in expectFunctions) {
            val actualFn = actualFunctions[name]
            if (actualFn == null) {
                findings.add("⚠️ `expect fun $name`: Missing corresponding `actual fun $name` declaration in the target platform module.")
            } else {
                val expectRet = expectFn.typeReference?.text?.trim() ?: "Unit"
                val actualRet = actualFn.typeReference?.text?.trim() ?: "Unit"
                if (expectRet != actualRet) {
                    findings.add("⚠️ `actual fun $name`: Return type mismatch. Expected `$expectRet` but actual is `$actualRet`.")
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
