package com.gokorei.kotlinmcp.semantic.analyzers

import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Validates `@JvmInline value class` constraints, constructor properties, and boxing avoidance.
 */
class ValueClassAnalyzer {

    fun analyze(session: K2AnalysisSession): KotlinMcpResult {
        val file = session.file
        val findings = mutableListOf<String>()
        var valueClassCount = 0

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitClass(klass: KtClass) {
                super.visitClass(klass)
                val className = klass.name ?: return

                val isValueClass = klass.hasModifier(KtTokens.VALUE_KEYWORD) || klass.isValue()

                if (!isValueClass) return
                valueClassCount++

                val hasJvmInline = klass.annotationEntries.any {
                    val name = it.shortName?.asString()
                    name == "JvmInline" || name == "kotlin.jvm.JvmInline"
                }
                if (!hasJvmInline) {
                    findings.add("⚠️ `value class $className`: Missing `@JvmInline` annotation on JVM target. Add `@JvmInline` to prevent compilation errors.")
                }

                val primaryConstructor = klass.primaryConstructor
                if (primaryConstructor == null) {
                    findings.add("⚠️ `value class $className`: Must declare a primary constructor with exactly one `val` parameter.")
                    return
                }

                val valueParameters = primaryConstructor.valueParameters
                if (valueParameters.size != 1) {
                    findings.add("⚠️ `value class $className`: Value classes must have exactly one primary constructor parameter, but found ${valueParameters.size}.")
                } else {
                    val param = valueParameters[0]
                    if (param.isMutable) {
                        findings.add("⚠️ `value class $className`: Primary constructor parameter `${param.name}` cannot be `var`. It must be an immutable `val`.")
                    }
                    if (!param.hasValOrVar()) {
                        findings.add("⚠️ `value class $className`: Primary constructor parameter `${param.name}` must be declared explicitly with `val`.")
                    }
                }

                if (klass.secondaryConstructors.isNotEmpty()) {
                    findings.add("ℹ️ `value class $className`: Declares secondary constructor(s). Ensure all secondary constructors delegate directly to the primary constructor.")
                }

                if (klass.superTypeListEntries.isNotEmpty()) {
                    findings.add("ℹ️ `value class $className`: Implements interface(s). Note that casting this value class to an interface will cause object boxing allocation.")
                }
            }
        })

        val content = buildString {
            appendLine("# Kotlin Value Class & Inlining Analysis")
            if (findings.isNotEmpty()) {
                appendLine("Found ${findings.size} issue(s) across $valueClassCount value class declaration(s):")
                appendLine()
                findings.forEach { appendLine("- $it") }
            } else if (valueClassCount > 0) {
                appendLine("✅ All $valueClassCount value class declaration(s) are valid and follow modern inlining best practices.")
            } else {
                appendLine("ℹ️ No value class declarations found in snippet.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.size.toString(), "valueClassCount" to valueClassCount.toString())
        )
    }
}
