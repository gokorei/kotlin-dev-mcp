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

        fun extractTypeName(typeRef: KtTypeReference?): String? {
            val typeElement = typeRef?.typeElement ?: return null
            return when (typeElement) {
                is KtUserType -> typeElement.referencedName
                is KtNullableType -> (typeElement.innerType as? KtUserType)?.referencedName
                else -> null
            }
        }

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

                // Check subclasses declared inside or outside via PSI type elements
                for (superEntry in klass.superTypeListEntries) {
                    val superName = extractTypeName(superEntry.typeReference) ?: continue
                    sealedHierarchies.computeIfAbsent(superName) { mutableSetOf() }.add(name)
                }
            }

            override fun visitObjectDeclaration(declaration: KtObjectDeclaration) {
                super.visitObjectDeclaration(declaration)
                val name = declaration.name ?: return
                for (superEntry in declaration.superTypeListEntries) {
                    val superName = extractTypeName(superEntry.typeReference) ?: continue
                    sealedHierarchies.computeIfAbsent(superName) { mutableSetOf() }.add(name)
                }
            }
        })

        // Now inspect all when expressions
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitWhenExpression(expression: KtWhenExpression) {
                super.visitWhenExpression(expression)

                val subject = expression.subjectExpression ?: return
                val subjectName = when (subject) {
                    is KtNameReferenceExpression -> subject.getReferencedName()
                    is KtDotQualifiedExpression -> (subject.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
                    else -> null
                }
                val entries = expression.entries

                val hasElse = entries.any { it.isElse }
                if (hasElse) return

                // Determine covered branch types / entries via PSI AST nodes
                val coveredBranches = mutableSetOf<String>()
                for (entry in entries) {
                    for (condition in entry.conditions) {
                        when (condition) {
                            is KtWhenConditionIsPattern -> {
                                val typeName = extractTypeName(condition.typeReference)
                                if (typeName != null) coveredBranches.add(typeName)
                            }
                            is KtWhenConditionWithExpression -> {
                                val expr = condition.expression
                                val name = when (expr) {
                                    is KtDotQualifiedExpression -> (expr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
                                    is KtNameReferenceExpression -> expr.getReferencedName()
                                    else -> null
                                }
                                if (name != null) coveredBranches.add(name)
                            }
                            else -> {}
                        }
                    }
                }

                // Match against known sealed hierarchies
                fun getEffectiveSubtypes(rootName: String, visited: Set<String> = emptySet()): Set<String> {
                    if (rootName in visited) return emptySet()
                    val direct = sealedHierarchies[rootName].orEmpty()
                    val result = mutableSetOf<String>()
                    for (child in direct) {
                        val childSub = sealedHierarchies[child]
                        if (!childSub.isNullOrEmpty()) {
                            if (child in coveredBranches) {
                                result.add(child)
                            } else {
                                result.addAll(getEffectiveSubtypes(child, visited + rootName))
                            }
                        } else {
                            result.add(child)
                        }
                    }
                    return result
                }

                // Identify top-level sealed roots (not a subtype of another sealed type)
                val allChildTypes = sealedHierarchies.values.flatten().toSet()

                for ((sealedName, directSubTypes) in sealedHierarchies) {
                    if (directSubTypes.isNotEmpty()) {
                        val effectiveSubTypes = getEffectiveSubtypes(sealedName)
                        val isSubjectMatch = subjectName != null && (subjectName == sealedName || subject.text.contains(sealedName))
                        val isCoveredMatch = coveredBranches.any { it in effectiveSubTypes || it in directSubTypes }

                        if (isSubjectMatch || isCoveredMatch) {
                            val missing = effectiveSubTypes - coveredBranches
                            if (missing.isNotEmpty()) {
                                findings.add("⚠️ `when (${subject.text})`: Missing branches for sealed type `$sealedName`: ${missing.joinToString(", ") { "`$it`" }}")
                                for (m in missing) {
                                    synthesizedBranches.add("is $sealedName.$m -> TODO()")
                                }
                            }
                        }
                    }
                }

                // Match against known enums
                for ((enumName, enumConstants) in enumDeclarations) {
                    if (enumConstants.isNotEmpty() && (coveredBranches.any { it in enumConstants } || (subjectName != null && subjectName == enumName))) {
                        val missing = enumConstants - coveredBranches
                        if (missing.isNotEmpty()) {
                            findings.add("⚠️ `when (${subject.text})`: Missing enum constants for `$enumName`: ${missing.joinToString(", ") { "`$it`" }}")
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
