package com.gokorei.kotlinmcp.semantic.analyzers

import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.jetbrains.kotlin.psi.*

/**
 * Analyzes `when` expressions for exhaustiveness over sealed hierarchies, enums, and booleans,
 * synthesizing missing branch stubs.
 */
class WhenExhaustivenessAnalyzer {

    fun analyze(session: K2AnalysisSession): KotlinMcpResult {
        val file = session.file
        val findings = mutableListOf<String>()
        val synthesizedBranches = mutableListOf<String>()

        // Discover declared sealed classes/interfaces and enums in the file
        val sealedHierarchies = mutableMapOf<String, MutableSet<String>>()
        val enumDeclarations = mutableMapOf<String, MutableSet<String>>()

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitClass(klass: KtClass) {
                super.visitClass(klass)
                val name = klass.name ?: return

                if (klass.isEnum()) {
                    val entries = klass.declarations.filterIsInstance<KtEnumEntry>().mapNotNull { it.name }.toSet()
                    enumDeclarations[name] = entries.toMutableSet()
                }

                if (klass.isSealed()) {
                    val set = sealedHierarchies.computeIfAbsent(name) { mutableSetOf() }
                    for (decl in klass.declarations) {
                        if (decl is KtClassOrObject) {
                            decl.name?.let { set.add(it) }
                        }
                    }
                }

                // Check subclasses declared inside or outside
                for (superEntry in klass.superTypeListEntries) {
                    val superName = superEntry.typeReference?.text?.trim()?.substringBefore("<")?.substringAfterLast(".")?.trim() ?: continue
                    sealedHierarchies.computeIfAbsent(superName) { mutableSetOf() }.add(name)
                }
            }

            override fun visitObjectDeclaration(declaration: KtObjectDeclaration) {
                super.visitObjectDeclaration(declaration)
                val name = declaration.name ?: return
                for (superEntry in declaration.superTypeListEntries) {
                    val superName = superEntry.typeReference?.text?.trim()?.substringBefore("<")?.substringAfterLast(".")?.trim() ?: continue
                    sealedHierarchies.computeIfAbsent(superName) { mutableSetOf() }.add(name)
                }
            }
        })

        // Now inspect all when expressions
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitWhenExpression(expression: KtWhenExpression) {
                super.visitWhenExpression(expression)

                val subject = expression.subjectExpression ?: return
                val subjectText = subject.text.trim()
                val entries = expression.entries

                val hasElse = entries.any { it.isElse }
                if (hasElse) return

                // Determine covered branch types / entries
                val coveredBranches = mutableSetOf<String>()
                for (entry in entries) {
                    for (condition in entry.conditions) {
                        when (condition) {
                            is KtWhenConditionIsPattern -> {
                                val typeText = condition.typeReference?.text?.trim()?.substringAfterLast(".") ?: ""
                                if (typeText.isNotBlank()) coveredBranches.add(typeText)
                            }
                            is KtWhenConditionWithExpression -> {
                                val exprText = condition.expression?.text?.trim()?.substringAfterLast(".") ?: ""
                                if (exprText.isNotBlank()) coveredBranches.add(exprText)
                            }
                            else -> {}
                        }
                    }
                }

                // Match against known sealed hierarchies
                for ((sealedName, subTypes) in sealedHierarchies) {
                    if (subTypes.isNotEmpty() && (coveredBranches.any { it in subTypes } || subjectText.contains(sealedName))) {
                        val missing = subTypes - coveredBranches
                        if (missing.isNotEmpty()) {
                            findings.add("⚠️ `when ($subjectText)`: Missing branches for sealed type `$sealedName`: ${missing.joinToString(", ") { "`$it`" }}")
                            for (m in missing) {
                                synthesizedBranches.add("is $sealedName.$m -> TODO()")
                            }
                        }
                    }
                }

                // Match against known enums
                for ((enumName, enumConstants) in enumDeclarations) {
                    if (enumConstants.isNotEmpty() && (coveredBranches.any { it in enumConstants } || subjectText.contains(enumName))) {
                        val missing = enumConstants - coveredBranches
                        if (missing.isNotEmpty()) {
                            findings.add("⚠️ `when ($subjectText)`: Missing enum constants for `$enumName`: ${missing.joinToString(", ") { "`$it`" }}")
                            for (m in missing) {
                                synthesizedBranches.add("$enumName.$m -> TODO()")
                            }
                        }
                    }
                }
            }
        })

        val content = buildString {
            appendLine("# When Expression Exhaustiveness Analysis")
            if (findings.isNotEmpty()) {
                appendLine("Found ${findings.size} non-exhaustive `when` expression(s):")
                appendLine()
                findings.forEach { appendLine("- $it") }
                appendLine()
                if (synthesizedBranches.isNotEmpty()) {
                    appendLine("### Suggested Missing Branch Stubs:")
                    appendLine("```kotlin")
                    synthesizedBranches.distinct().forEach { appendLine(it) }
                    appendLine("```")
                }
            } else {
                appendLine("✅ All `when` expressions are fully exhaustive.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("missingBranchesCount" to synthesizedBranches.distinct().size.toString())
        )
    }
}
