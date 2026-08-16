package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.shared.SourceUtils
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Strategy component for flow-aware nullability analysis, `!!` assertions, and unguarded dereference detection.
 */
class NullabilityAnalyzer {

    fun analyzeNullability(code: String): KotlinMcpResult {
        val findings = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        if (psi == null) {
            return KotlinMcpResult.Success(
                content = "# Nullability Analysis Findings\nUnable to parse snippet as Kotlin PSI.",
                metadata = mapOf("findingsCount" to "0")
            )
        }

        val text = code
        val lineOf = { offset: Int -> SourceUtils.lineOf(text, offset) }

        val nullableIdentifiers = mutableSetOf<String>()
        val nullableFunctions = mutableSetOf<String>()
        val nonNullFnParams = mutableMapOf<String, Set<Int>>()

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitParameter(parameter: KtParameter) {
                val type = parameter.typeReference?.text
                if (type != null && type.trimEnd().endsWith("?")) {
                    parameter.name?.let { nullableIdentifiers.add(it) }
                }
                super.visitParameter(parameter)
            }
            override fun visitProperty(property: KtProperty) {
                val type = property.typeReference?.text
                if (type != null && type.trimEnd().endsWith("?")) {
                    property.name?.let { nullableIdentifiers.add(it) }
                }
                super.visitProperty(property)
            }
            override fun visitNamedFunction(function: KtNamedFunction) {
                val fnName = function.name.orEmpty()
                val returnType = function.typeReference?.text.orEmpty()
                if (returnType.trimEnd().endsWith("?")) {
                    nullableFunctions.add(fnName)
                }
                val nonNullIndices = mutableSetOf<Int>()
                function.valueParameters.forEachIndexed { idx, p ->
                    val pType = p.typeReference?.text.orEmpty()
                    if (pType.isNotBlank() && !pType.trimEnd().endsWith("?")) {
                        nonNullIndices.add(idx)
                    }
                }
                if (fnName.isNotBlank() && nonNullIndices.isNotEmpty()) {
                    nonNullFnParams[fnName] = nonNullIndices
                }
                super.visitNamedFunction(function)
            }
        })


        val reassignedNonNull = mutableSetOf<String>()
        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                val opText = expression.operationReference.text
                if (opText == "=") {
                    val lhs = expression.left as? KtSimpleNameExpression
                    val rhs = expression.right?.text
                    if (lhs != null && rhs != null && !rhs.trimEnd().endsWith("?") && !rhs.contains("!!") &&
                        lhs.getReferencedName() in nullableIdentifiers
                    ) {
                        if (!rhs.contains("null")) reassignedNonNull.add(lhs.getReferencedName())
                    }
                }
                super.visitBinaryExpression(expression)
            }
        })

        fun walkBlock(block: KtBlockExpression?, inherited: Set<String>) {
            if (block == null) return
            val guards = mutableSetOf<String>().apply { addAll(inherited) }
            for (statement in block.statements) {
                statement.accept(object : KtTreeVisitorVoid() {
                    override fun visitCallExpression(callExpression: KtCallExpression) {
                        val callee = callExpression.calleeExpression?.text
                        if (callee == "requireNotNull" || callee == "checkNotNull" || callee == "check" || callee == "assert") {
                            val argRef = (callExpression.valueArguments.firstOrNull()?.getArgumentExpression() as? KtNameReferenceExpression)?.getReferencedName()
                                ?: ((callExpression.valueArguments.firstOrNull()?.getArgumentExpression() as? KtBinaryExpression)?.left as? KtNameReferenceExpression)?.getReferencedName()
                            if (argRef != null) guards.add(argRef)
                        }
                        super.visitCallExpression(callExpression)
                    }

                    override fun visitBinaryExpression(binaryExpression: KtBinaryExpression) {
                        if (binaryExpression.operationToken == KtTokens.ELVIS) {
                            val elvisRef = (binaryExpression.left as? KtNameReferenceExpression)?.getReferencedName()
                            if (elvisRef != null) guards.add(elvisRef)
                        }
                        super.visitBinaryExpression(binaryExpression)
                    }
                })

                if (statement is KtIfExpression) {
                    val condBin = statement.condition as? KtBinaryExpression
                    val condIs = statement.condition as? org.jetbrains.kotlin.psi.KtIsExpression
                    val guardName = if (condBin != null && condBin.operationToken == KtTokens.EXCLEQ) {
                        (condBin.left as? KtNameReferenceExpression)?.getReferencedName()
                            ?: (condBin.right as? KtNameReferenceExpression)?.getReferencedName()
                    } else if (condIs != null && !condIs.isNegated) {
                        (condIs.leftHandSide as? KtNameReferenceExpression)?.getReferencedName()
                    } else null
                    val thenBlock = statement.then as? KtBlockExpression
                    if (guardName != null && thenBlock != null) {
                        walkBlock(thenBlock, guards + guardName)
                    } else {
                        checkDerefsIn(code, statement, guards, nullableIdentifiers, nullableFunctions, nonNullFnParams, reassignedNonNull, findings)
                    }
                    val elseBlock = statement.`else` as? KtBlockExpression
                    if (elseBlock != null) walkBlock(elseBlock, guards)
                } else {
                    checkDerefsIn(code, statement, guards, nullableIdentifiers, nullableFunctions, nonNullFnParams, reassignedNonNull, findings)
                }
            }
        }

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                walkBlock(function.bodyBlockExpression, emptySet())
                super.visitNamedFunction(function)
            }
        })

        (psi.declarations + psi.script?.declarations.orEmpty()).filterIsInstance<KtProperty>()
            .forEach { prop ->
                val initializer = prop.initializer
                if (initializer != null) {
                    checkDerefsIn(code, initializer, emptySet(), nullableIdentifiers, nullableFunctions, nonNullFnParams, reassignedNonNull, findings)
                }
            }


        psi.declarations.filterIsInstance<KtNamedFunction>()
            .filter { it.bodyBlockExpression == null }
            .forEach { fn ->
                val bodyText = fn.bodyExpression?.text.orEmpty()
                if (bodyText.contains("!!")) {
                    val offset = fn.bodyExpression?.textRange?.startOffset ?: 0
                    findings.add("Line ${lineOf(offset)}: Unsafe non-null assertion `!!` detected (`${bodyText.trim()}`). Prefer `?.let` / `?:` to avoid NPE.")
                }
            }

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitPrefixExpression(expression: KtPrefixExpression) {
                if (expression.operationReference.text == "!!") {
                    val offset = expression.textRange.startOffset
                    findings.add("Line ${lineOf(offset)}: Unsafe non-null assertion `!!` detected (`${expression.text.take(80)}`). Prefer `?.let` / `?:` to avoid NPE.")
                }
                super.visitPrefixExpression(expression)
            }
            override fun visitPostfixExpression(expression: KtPostfixExpression) {
                if (expression.operationReference.text == "!!") {
                    val offset = expression.textRange.startOffset
                    findings.add("Line ${lineOf(offset)}: Unsafe non-null assertion `!!` detected (`${expression.text.take(80)}`). Prefer `?.let` / `?:` to avoid NPE.")
                }
                super.visitPostfixExpression(expression)
            }
        })

        val content = if (findings.isNotEmpty()) {
            "# Nullability Analysis Findings\n" + findings.distinct().joinToString("\n\n")
        } else {
            "# Nullability Analysis Findings\nNo unsafe nullability patterns detected."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.distinct().size.toString())
        )
    }

    private fun checkDerefsIn(
        code: String,
        element: KtElement,
        guards: Set<String>,
        nullableIdentifiers: Set<String>,
        nullableFunctions: Set<String>,
        nonNullFnParams: Map<String, Set<Int>>,
        reassignedNonNull: Set<String>,
        findings: MutableList<String>
    ) {
        val scopeFunctions = setOf("let", "also", "run", "apply", "takeIf", "takeUnless")
        element.accept(object : KtTreeVisitorVoid() {
            override fun visitIfExpression(expression: KtIfExpression) {
                val condBin = expression.condition as? KtBinaryExpression
                val condIs = expression.condition as? org.jetbrains.kotlin.psi.KtIsExpression
                val guardName = if (condBin != null && condBin.operationToken == KtTokens.EXCLEQ) {
                    (condBin.left as? KtNameReferenceExpression)?.getReferencedName()
                        ?: (condBin.right as? KtNameReferenceExpression)?.getReferencedName()
                } else if (condIs != null && !condIs.isNegated) {
                    (condIs.leftHandSide as? KtNameReferenceExpression)?.getReferencedName()
                } else null
                val thenBlock = expression.then as? KtBlockExpression
                if (guardName != null && thenBlock != null) {
                    return
                }
                super.visitIfExpression(expression)
            }
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text.orEmpty()
                val nonNullIndices = nonNullFnParams[callee]
                if (nonNullIndices != null && nonNullIndices.isNotEmpty()) {
                    expression.valueArguments.forEachIndexed { idx, arg ->
                        if (idx in nonNullIndices) {
                            val argExpr = arg.getArgumentExpression()
                            val argText = argExpr?.text.orEmpty()
                            val isNullableCall = argExpr is KtCallExpression && argExpr.calleeExpression?.text in nullableFunctions
                            val isNullableId = argText in nullableIdentifiers && argText !in guards && argText !in reassignedNonNull
                            if ((isNullableCall || isNullableId) && !argText.contains("!!") && !argText.contains("?:")) {
                                findings.add("Line ${SourceUtils.lineOf(code, expression.textRange.startOffset)}: Unsafe dereference / parameter passing: argument `$argText` passed to non-null parameter in `$callee(...)` is nullable. Use `?:` or smart-cast guard.")
                            }
                        }
                    }
                }
                super.visitCallExpression(expression)
            }
            override fun visitSafeQualifiedExpression(expression: org.jetbrains.kotlin.psi.KtSafeQualifiedExpression) {
                val selectorCall = expression.selectorExpression as? KtCallExpression
                val callee = selectorCall?.calleeExpression?.text.orEmpty()
                if (callee in scopeFunctions) {
                    val lambda = selectorCall?.lambdaArguments?.firstOrNull()?.getLambdaExpression()
                    val lambdaParam = lambda?.valueParameters?.firstOrNull()?.name ?: "it"
                    val localGuards = guards + setOf("it", lambdaParam)
                    lambda?.bodyExpression?.let { body ->
                        checkDerefsIn(code, body, localGuards, nullableIdentifiers, nullableFunctions, nonNullFnParams, reassignedNonNull, findings)
                    }
                    return
                }
                super.visitSafeQualifiedExpression(expression)
            }
            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                if (expression.parent is org.jetbrains.kotlin.psi.KtSafeQualifiedExpression) {
                    return
                }
                val receiver = expression.receiverExpression
                val receiverText = receiver.text
                val selector = expression.selectorExpression?.text.orEmpty()
                if (receiver is KtNameReferenceExpression) {
                    val name = receiver.getReferencedName()
                    val isDefiniteCall = name in nullableIdentifiers && name !in reassignedNonNull && name !in guards
                    if (isDefiniteCall && !selector.startsWith("!!") && !receiverText.endsWith("!!")) {
                        findings.add("Line ${SourceUtils.lineOf(code, expression.textRange.startOffset)}: Unsafe dereference of nullable `$name` (`${expression.text.take(100)}`). Use `$name?.let { }`, `?:`, or a smart-cast guard.")
                    }
                }
                super.visitDotQualifiedExpression(expression)
            }
        })
    }
}
