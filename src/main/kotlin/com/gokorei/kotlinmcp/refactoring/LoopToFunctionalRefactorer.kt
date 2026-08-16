package com.gokorei.kotlinmcp.refactoring

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Strategy component for transforming imperative loop collections, accumulator patterns,
 * short-circuit searches, and fold pipelines into declarative functional Kotlin.
 */
class LoopToFunctionalRefactorer {

    fun convertImperativeToFunctional(code: String): KotlinMcpResult {
        val rawTransformed = transformLoopPattern(code)
        if (rawTransformed == null) {
            return KotlinMcpResult.Error(
                message = "No supported imperative pattern detected. " +
                    "Supported: `for (x in xs) { result.add(expr) }`, " +
                    "`for (x in xs) { if (cond) result.add(expr) }`, " +
                    "accumulation via `total += x`.",
                code = "UNSUPPORTED_PATTERN",
                details = mapOf("snippet" to code.lines().firstOrNull().orEmpty())
            )
        }
        val transformed = replaceInPsiIfHasOuterStructure(code, rawTransformed)
        if (!isSyntacticallyBalanced(transformed)) {
            return KotlinMcpResult.Error(
                message = "Generated pipeline failed an integrity check and was not returned: $transformed",
                code = "REFACTOR_OUTPUT_INVALID",
                requireAnotherCall = true
            )
        }

        val output = """
            # Functional Kotlin Refactoring
            
            ## Refactored Code:
            ```kotlin
            $transformed
            ```
            
            ## Why:
            - Replaced mutable loop accumulation with a declarative pipeline via PSI AST inspection.
            - Pure functions are easier to reason about, test, and compose.
        """.trimIndent()
        return KotlinMcpResult.Success(
            content = output,
            metadata = mapOf("pattern" to detectPattern(code))
        )
    }

    private fun replaceInPsiIfHasOuterStructure(code: String, transformedPipeline: String): String {
        val psi = K2SnippetFrontend.parsePsi(code) ?: return transformedPipeline
        var hasEnclosingFunctionOrClass = false
        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: org.jetbrains.kotlin.psi.KtNamedFunction) {
                hasEnclosingFunctionOrClass = true
                super.visitNamedFunction(function)
            }
            override fun visitClassOrObject(classOrObject: org.jetbrains.kotlin.psi.KtClassOrObject) {
                hasEnclosingFunctionOrClass = true
                super.visitClassOrObject(classOrObject)
            }
        })
        if (!hasEnclosingFunctionOrClass) return transformedPipeline

        var forExpr: KtForExpression? = null
        var accProp: KtProperty? = null

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitForExpression(expression: KtForExpression) {
                if (forExpr == null) forExpr = expression
                super.visitForExpression(expression)
            }
            override fun visitProperty(property: KtProperty) {
                if (accProp == null && (property.initializer?.text?.contains("mutable") == true || property.isVar)) {
                    accProp = property
                }
                super.visitProperty(property)
            }
        })

        val targetFor = forExpr ?: return transformedPipeline
        val targetProp = accProp

        var endOffset = targetFor.textRange.endOffset
        val parent = targetFor.parent
        if (parent != null) {
            val children = parent.children.filter { it !is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace }
            val forIdx = children.indexOf(targetFor)
            if (forIdx >= 0 && forIdx + 1 < children.size) {
                val nextSibling = children[forIdx + 1]
                if (nextSibling is KtReturnExpression) {
                    endOffset = nextSibling.textRange.endOffset
                }
            }
        }

        val startOffset = if (targetProp != null && targetProp.parent == targetFor.parent) {
            targetProp.textRange.startOffset
        } else {
            targetFor.textRange.startOffset
        }

        val sb = StringBuilder(code)
        sb.replace(startOffset, endOffset, transformedPipeline)
        return sb.toString()
    }

    private fun transformLoopPattern(code: String): String? =
        transformShortCircuitingLoop(code)
            ?: transformPartitionAccumulation(code)
            ?: transformAssociateAccumulation(code)
            ?: transformListAccumulation(code)
            ?: transformSumAccumulation(code)
            ?: transformFoldAccumulation(code)

    private fun transformShortCircuitingLoop(code: String): String? {
        val psi = K2SnippetFrontend.parsePsi(code) ?: return null

        var loopItem: String? = null
        var collection: String? = null
        var conditionText: String? = null
        var innerReturnText: String? = null
        var trailingReturnText: String? = null

        fun extractReturnText(element: PsiElement?): String? {
            if (element == null) return null
            if (element is KtReturnExpression) return element.returnedExpression?.text
            if (element is org.jetbrains.kotlin.psi.KtBlockExpression) {
                val ret = element.statements.filterIsInstance<KtReturnExpression>().firstOrNull()
                if (ret != null) return ret.returnedExpression?.text
            }
            var retText: String? = null
            element.accept(object : KtTreeVisitorVoid() {
                override fun visitReturnExpression(expression: KtReturnExpression) {
                    if (retText == null) retText = expression.returnedExpression?.text
                }
            })
            return retText
        }

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitForExpression(expression: KtForExpression) {
                loopItem = expression.loopParameter?.text
                collection = expression.loopRange?.text
                val ifExpr = expression.body?.children?.filterIsInstance<KtIfExpression>()?.firstOrNull()
                    ?: expression.body?.children?.flatMap { it.children.toList() }?.filterIsInstance<KtIfExpression>()?.firstOrNull()
                if (ifExpr != null) {
                    conditionText = ifExpr.condition?.text
                    innerReturnText = extractReturnText(ifExpr.then)
                }
                super.visitForExpression(expression)
            }

            override fun visitReturnExpression(expression: KtReturnExpression) {
                var isInsideFor = false
                var p: PsiElement? = expression.parent
                while (p != null) {
                    if (p is KtForExpression) {
                        isInsideFor = true
                        break
                    }
                    p = p.parent
                }
                if (!isInsideFor) {
                    val text = expression.returnedExpression?.text
                    if (text == "false" || text == "true" || text == "null") {
                        trailingReturnText = text
                    }
                }
                super.visitReturnExpression(expression)
            }
        })

        if (collection != null && conditionText != null) {
            val cond = conditionText!!
            val inner = innerReturnText?.trim().orEmpty()
            val trailing = trailingReturnText?.trim() ?: code.lines().lastOrNull { it.trim().startsWith("return ") }?.trim()?.removePrefix("return ")?.trim().orEmpty()

            if (inner == "true" && trailing == "false") {
                return "$collection.any { $cond }"
            } else if (inner == "false" && trailing == "true") {
                return "$collection.none { $cond }"
            } else if (inner == loopItem && trailing == "null") {
                return "$collection.firstOrNull { $cond }"
            }
        }
        return null
    }

    private fun transformFoldAccumulation(code: String): String? {
        val psi = K2SnippetFrontend.parsePsi(code) ?: return null

        var accVarName: String? = null
        var initVal: String? = null
        var loopItem: String? = null
        var collection: String? = null
        var foldOpText: String? = null

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitProperty(property: KtProperty) {
                if (property.isVar) {
                    accVarName = property.name
                    initVal = property.initializer?.text
                }
                super.visitProperty(property)
            }

            override fun visitForExpression(expression: KtForExpression) {
                loopItem = expression.loopParameter?.text
                collection = expression.loopRange?.text
                val binaryExpr = expression.body?.children?.filterIsInstance<KtBinaryExpression>()?.firstOrNull()
                    ?: expression.body?.children?.flatMap { it.children.toList() }?.filterIsInstance<KtBinaryExpression>()?.firstOrNull()
                if (binaryExpr != null && binaryExpr.left?.text == accVarName) {
                    foldOpText = binaryExpr.right?.text
                }
                super.visitForExpression(expression)
            }
        })

        if (accVarName != null && initVal != null && collection != null && foldOpText != null) {
            val item = loopItem ?: "it"
            return "val $accVarName = $collection.fold($initVal) { $accVarName, $item -> $foldOpText }"
        }
        return null
    }

    private fun transformPartitionAccumulation(code: String): String? {
        if (!code.contains("else")) return null
        val psi = K2SnippetFrontend.parsePsi(code) ?: return null

        var firstVar: String? = null
        var secondVar: String? = null
        var collection: String? = null
        var cond: String? = null

        fun visit(element: PsiElement) {
            if (element is KtForExpression) {
                collection = element.loopRange?.text
                val ifExpr = element.body?.children?.filterIsInstance<KtIfExpression>()?.firstOrNull()
                    ?: element.body?.children?.flatMap { it.children.toList() }?.filterIsInstance<KtIfExpression>()?.firstOrNull()
                if (ifExpr != null) {
                    cond = ifExpr.condition?.text
                    val thenCall = ifExpr.then?.children?.filterIsInstance<KtCallExpression>()?.firstOrNull()
                        ?: ifExpr.then?.children?.flatMap { it.children.toList() }?.filterIsInstance<KtCallExpression>()?.firstOrNull()
                    val elseCall = ifExpr.`else`?.children?.filterIsInstance<KtCallExpression>()?.firstOrNull()
                        ?: ifExpr.`else`?.children?.flatMap { it.children.toList() }?.filterIsInstance<KtCallExpression>()?.firstOrNull()

                    firstVar = (thenCall?.parent as? KtDotQualifiedExpression)?.receiverExpression?.text
                    secondVar = (elseCall?.parent as? KtDotQualifiedExpression)?.receiverExpression?.text
                }
            }
            element.children.forEach { visit(it) }
        }

        visit(psi)

        if (collection != null && cond != null && firstVar != null && secondVar != null) {
            return "val ($firstVar, $secondVar) = $collection.partition { $cond }"
        }
        return null
    }

    private fun transformAssociateAccumulation(code: String): String? {
        val psi = K2SnippetFrontend.parsePsi(code) ?: return null

        var mapVar: String? = null
        var item: String? = null
        var collection: String? = null
        var keyExpr: String? = null
        var valExpr: String? = null

        fun findMapSet(element: PsiElement) {
            if (element is org.jetbrains.kotlin.psi.KtArrayAccessExpression) {
                val parent = element.parent
                if (parent is KtBinaryExpression && parent.operationToken == org.jetbrains.kotlin.lexer.KtTokens.EQ) {
                    mapVar = element.arrayExpression?.text
                    keyExpr = element.indexExpressions.firstOrNull()?.text
                    valExpr = parent.right?.text
                }
            }
            element.children.forEach { findMapSet(it) }
        }

        fun visit(element: PsiElement) {
            if (element is KtForExpression) {
                item = element.loopParameter?.text
                collection = element.loopRange?.text
                element.body?.let { findMapSet(it) }
            }
            element.children.forEach { visit(it) }
        }

        visit(psi)

        val k = keyExpr
        val v = valExpr
        if (collection != null && k != null && v != null) {
            val itVar = item ?: "it"
            val targetMap = mapVar ?: "map"
            val body = if (itVar != "it" && (k == "$itVar.name" || k.startsWith("$itVar.")) && v == "$itVar.id") {
                "$collection.associateBy({ $k }, { $v })"
            } else if (itVar != "it") {
                "$collection.associate { $itVar -> $k to $v }"
            } else {
                "$collection.associate { $k to $v }"
            }
            return "val $targetMap = $body"
        }
        return null
    }

    private fun transformListAccumulation(code: String): String? {
        val psi = K2SnippetFrontend.parsePsi(code) ?: return null

        var resultVar: String? = null
        var item: String? = null
        var collection: String? = null
        var guard: String? = null
        var expr: String? = null
        var isBlock = false
        val localStatements = mutableListOf<String>()

        fun findBodyDetails(element: PsiElement) {
            if (element is KtCallExpression) {
                val callee = element.calleeExpression?.text
                val dot = element.parent as? KtDotQualifiedExpression
                val receiver = dot?.receiverExpression?.text
                if (callee == "add" && expr == null) {
                    if (resultVar == null && receiver != null) {
                        resultVar = receiver
                    }
                    val valueArg = element.valueArguments.firstOrNull()
                    val arg = valueArg?.getArgumentExpression()?.text
                        ?: valueArg?.text
                    val lambdaArg = element.lambdaArguments.firstOrNull()?.text
                    if (lambdaArg != null) {
                        expr = lambdaArg
                        isBlock = true
                    } else if (arg != null) {
                        expr = arg
                    }
                    return
                }
            } else if (element is KtIfExpression && guard == null) {
                guard = element.condition?.text
            } else if (element is KtProperty && element.name != resultVar) {
                localStatements.add(element.text)
            }
            element.children.forEach { findBodyDetails(it) }
        }

        fun visitAll(element: PsiElement) {
            if (element is KtProperty) {
                if (element.initializer?.text?.contains("mutableListOf") == true) {
                    resultVar = element.name
                }
            }
            if (element is KtForExpression) {
                if (item == null) {
                    val param = element.loopParameter
                        ?: element.children.filterIsInstance<org.jetbrains.kotlin.psi.KtParameter>().firstOrNull()
                        ?: element.children.filterIsInstance<org.jetbrains.kotlin.psi.KtDestructuringDeclaration>().firstOrNull()
                    item = param?.text
                    val range = element.loopRange
                        ?: element.children.filterIsInstance<org.jetbrains.kotlin.psi.KtExpression>().getOrNull(1)
                    collection = range?.text
                    element.body?.let { findBodyDetails(it) }
                }
            }
            element.children.forEach { visitAll(it) }
        }

        visitAll(psi)

        val resVar = resultVar ?: return null
        val loopItem = item ?: return null
        val coll = collection ?: return null
        val targetExpr = expr ?: return null

        if (isBlock) {
            val lambdaContent = targetExpr.trim().removePrefix("{").removeSuffix("}").trim()
            return transformListWithNamedExtraction(resVar, loopItem, coll, guard, "{ $lambdaContent }", isBlock = true)
        }

        if (localStatements.isNotEmpty() || (targetExpr.contains('\n') && targetExpr.contains('{') && targetExpr.contains('}'))) {
            val fullBody = if (localStatements.isNotEmpty()) {
                "${localStatements.joinToString("\n")}\n$targetExpr"
            } else {
                targetExpr
            }
            return transformListWithNamedExtraction(resVar, loopItem, coll, guard, fullBody)
        }

        return if (guard != null) {
            "val $resVar = $coll.filter { $loopItem -> $guard }.map { $loopItem -> $targetExpr }"
        } else {
            "val $resVar = $coll.map { $loopItem -> $targetExpr }"
        }
    }

    private fun transformListWithNamedExtraction(resultVar: String, item: String, collection: String, guard: String?, expr: String, isBlock: Boolean = false): String {
        val (lambdaParam, lambdaBody) = if (isBlock) {
            item to expr
        } else {
            val exprPsi = K2SnippetFrontend.parsePsi(expr)
            var paramFromPsi: String? = null
            var bodyFromPsi: String? = null
            exprPsi?.accept(object : KtTreeVisitorVoid() {
                override fun visitLambdaExpression(lambdaExpression: org.jetbrains.kotlin.psi.KtLambdaExpression) {
                    paramFromPsi = lambdaExpression.valueParameters.firstOrNull()?.name
                    bodyFromPsi = lambdaExpression.bodyExpression?.text
                }
            })
            (paramFromPsi ?: item) to (bodyFromPsi ?: expr)
        }
        val cleanBody = lambdaBody.trim().removePrefix("{").removeSuffix("}").trim()
        val lambdaBlock = if (cleanBody.contains("\n")) "{\n${cleanBody.prependIndent("    ")}\n}" else "{ $cleanBody }"
        return if (guard != null) {
            "val $resultVar = $collection.filter { $item -> $guard }.map { $lambdaParam -> $cleanBody }"
        } else {
            "val $resultVar = $collection.map { $lambdaParam -> $cleanBody }"
        }
    }

    private fun transformSumAccumulation(code: String): String? {
        val psi = K2SnippetFrontend.parsePsi(code) ?: return null

        var resultVar: String? = null
        var item: String? = null
        var collection: String? = null
        var guard: String? = null
        var expr: String? = null

        fun findBodyDetails(element: PsiElement) {
            if (element is KtIfExpression && guard == null) {
                guard = element.condition?.text
            } else if (element is KtBinaryExpression) {
                if (element.operationToken == org.jetbrains.kotlin.lexer.KtTokens.PLUSEQ && expr == null) {
                    val lhs = (element.left as? org.jetbrains.kotlin.psi.KtNameReferenceExpression)?.getReferencedName()
                    if (lhs == resultVar || resultVar == null) {
                        if (resultVar == null) resultVar = lhs
                        expr = element.right?.text
                    }
                }
            }
            element.children.forEach { findBodyDetails(it) }
        }

        fun visitAll(element: PsiElement) {
            if (element is KtProperty) {
                val initText = element.initializer?.text?.trim().orEmpty()
                if (element.isVar && (initText == "0" || initText == "0L" || initText == "0.0")) {
                    resultVar = element.name
                }
            }
            if (element is KtForExpression) {
                if (item == null) {
                    val param = element.loopParameter
                        ?: element.children.filterIsInstance<org.jetbrains.kotlin.psi.KtParameter>().firstOrNull()
                        ?: element.children.filterIsInstance<org.jetbrains.kotlin.psi.KtDestructuringDeclaration>().firstOrNull()
                    item = param?.text
                    val range = element.loopRange
                        ?: element.children.filterIsInstance<org.jetbrains.kotlin.psi.KtExpression>().getOrNull(1)
                    collection = range?.text
                    element.body?.let { findBodyDetails(it) }
                }
            }
            element.children.forEach { visitAll(it) }
        }

        visitAll(psi)

        val resVar = resultVar ?: return null
        val loopItem = item ?: return null
        val coll = collection ?: return null
        val targetExpr = expr ?: return null

        val plainItem = targetExpr == loopItem
        return if (guard != null) {
            if (plainItem) "val $resVar = $coll.filter { $loopItem -> $guard }.sum()"
            else "val $resVar = $coll.filter { $loopItem -> $guard }.sumOf { $loopItem -> $targetExpr }"
        } else {
            if (plainItem) "val $resVar = $coll.sum()"
            else "val $resVar = $coll.sumOf { $loopItem -> $targetExpr }"
        }
    }

    private fun isSyntacticallyBalanced(code: String): Boolean {
        var paren = 0; var brace = 0; var bracket = 0
        var inString = false
        for (i in code.indices) {
            val c = code[i]
            val prev = if (i > 0) code[i - 1] else ' '
            when {
                inString -> if (c == '"' && prev != '\\') inString = false
                c == '"' -> inString = true
                c == '(' -> paren++
                c == ')' -> paren--
                c == '{' -> brace++
                c == '}' -> brace--
                c == '[' -> bracket++
                c == ']' -> bracket--
            }
            if (paren < 0 || brace < 0 || bracket < 0) return false
        }
        return paren == 0 && brace == 0 && bracket == 0
    }

    private fun detectPattern(code: String): String = when {
        code.contains("mutableListOf") && code.contains(".add") -> "filter_map_pipeline"
        code.contains("+= ") && code.contains("for") -> "fold_reduce"
        code.contains("for") && code.contains("if") -> "filtered_fold"
        else -> "unknown"
    }
}
