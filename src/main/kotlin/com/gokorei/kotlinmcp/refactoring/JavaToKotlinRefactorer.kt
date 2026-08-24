package com.gokorei.kotlinmcp.refactoring

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock
import org.jetbrains.kotlin.com.intellij.psi.PsiDeclarationStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReturnStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiSwitchLabelStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiSwitchStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiThisExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiTryStatement

/**
 * Strategy component for converting Java source constructs and records
 * into idiomatic Kotlin structures using PSI AST analysis.
 */
class JavaToKotlinRefactorer {

    private val primitiveTypeMap = mapOf(
        "int" to "Int",
        "long" to "Long",
        "boolean" to "Boolean",
        "double" to "Double",
        "float" to "Float",
        "short" to "Short",
        "byte" to "Byte",
        "char" to "Char",
        "void" to "Unit",
        "Object" to "Any",
        "String" to "String"
    )

    fun convertJavaToKotlin(javaCode: String): KotlinMcpResult {
        val project = K2SnippetFrontend.parsePsi(javaCode)?.project
        val fullJavaCode = if (javaCode.contains("class ") || javaCode.contains("record ")) {
            javaCode
        } else {
            "public class ConvertedClass {\n    public void syntheticMethod() {\n        $javaCode\n    }\n}"
        }

        val psiJavaFile = project?.let {
            runCatching {
                PsiFileFactory.getInstance(it).createFileFromText("ConvertedClass.java", JavaFileType.INSTANCE, fullJavaCode) as? PsiJavaFile
            }.getOrNull()
        }

        val cls = psiJavaFile?.classes?.firstOrNull()
        val recordComponents = cls?.recordComponents
        val isRecord = cls != null && (cls.recordHeader != null || cls.text.contains("record "))
        if (cls != null && isRecord && recordComponents != null && recordComponents.isNotEmpty()) {
            val name = cls.name ?: "ConvertedRecord"
            val params = recordComponents.map { rc ->
                val rawType = rc.typeElement?.text ?: rc.type.presentableText
                val type = mapJavaTypeToKotlin(rawType, false)
                "val ${rc.name}: $type"
            }
            val recordDecl = "data class $name(\n${params.joinToString(",\n") { "    $it" }}\n)"
            return KotlinMcpResult.Success(
                content = "# Java to Kotlin Refactoring Output\n\n```kotlin\n$recordDecl\n```\n\n## Applied Improvements\n- Converted Java record into concise Kotlin data class via PSI AST analysis.",
                metadata = mapOf("originalLanguage" to "java", "targetLanguage" to "kotlin")
            )
        }

        val className = cls?.name ?: "ConvertedClass"

        val fields = cls?.fields?.map { field ->
            val isNullable = field.isNullableAnnotated()
            val rawType = field.typeElement?.text ?: field.type.presentableText
            mapJavaTypeToKotlin(rawType, isNullable) to field.name
        }.orEmpty()

        val fieldNames = fields.map { it.second }.toSet()
        val properties = fields.map { (type, name) -> "var $name: $type" }

        val renderedMethods = cls?.methods?.mapNotNull { m ->
            if (m.name == "syntheticMethod") {
                m.body?.let { convertJavaBodyPsi(it).second }
            } else {
                renderKotlinMethodFromPsi(m, fieldNames)
            }
        }.orEmpty()

        val classDecl = buildString {
            if (properties.isNotEmpty()) {
                append("data class $className(\n")
                append(properties.joinToString(",\n") { "    $it" })
                append("\n)")
            } else {
                append("class $className")
            }
            if (renderedMethods.isNotEmpty()) {
                append(" {\n")
                append(renderedMethods.joinToString("\n\n") { it.prependIndent("    ") })
                append("\n}")
            }
        }

        val output = """
            # Java to Kotlin Refactoring Output
            
            ```kotlin
            $classDecl
            ```
            
            ## Applied Improvements
            - Converted Java fields, getters, and setters into Kotlin concise constructor properties via PSI AST analysis.
            - Preserved non-accessor methods as Kotlin functions.
            - Eliminated nullability ambiguity with explicit Kotlin types.
        """.trimIndent()

        return KotlinMcpResult.Success(
            content = output,
            metadata = mapOf("originalLanguage" to "java", "targetLanguage" to "kotlin")
        )
    }

    private fun renderKotlinMethodFromPsi(m: PsiMethod, fieldNames: Set<String>): String? {
        if (m.isConstructor) return null
        val name = m.name
        val accessorProp = when {
            name.startsWith("get") -> name.removePrefix("get")
            name.startsWith("is") -> name.removePrefix("is")
            name.startsWith("set") -> name.removePrefix("set")
            else -> null
        }?.replaceFirstChar { it.lowercase() }

        val isAccessor = accessorProp != null && accessorProp in fieldNames &&
            (name.startsWith("get") || name.startsWith("is") || name.startsWith("set"))
        if (isAccessor) return null

        val params = m.parameterList.parameters.joinToString(", ") { p ->
            val isNullable = p.isNullableAnnotated()
            val rawType = p.typeElement?.text ?: p.type.presentableText
            "${p.name}: ${mapJavaTypeToKotlin(rawType, isNullable)}"
        }
        val retType = m.returnTypeElement?.text ?: "void"
        val isRetNullable = m.isNullableAnnotated()
        val kotlinReturn = if (retType == "void") "Unit" else mapJavaTypeToKotlin(retType, isRetNullable)

        val body = m.body ?: return "fun $name($params): $kotlinReturn {\n    TODO(\"translated from Java; verify this body\")\n}"
        val (isSingleReturn, content) = convertJavaBodyPsi(body)

        return when {
            isSingleReturn -> "fun $name($params): $kotlinReturn = $content"
            content.isNotBlank() -> "fun $name($params): $kotlinReturn {\n${content.prependIndent("    ")}\n}"
            else -> "fun $name($params): $kotlinReturn {\n    TODO(\"translated from Java; verify this body\")\n}"
        }
    }

    private fun org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner.isNullableAnnotated(): Boolean {
        val annotations = modifierList?.annotations ?: return false
        return annotations.any { ann ->
            val qName = ann.qualifiedName ?: ann.nameReferenceElement?.referenceName
            qName == "Nullable" || qName?.endsWith(".Nullable") == true || ann.text.contains("Nullable")
        }
    }

    private fun convertJavaBodyPsi(body: PsiCodeBlock): Pair<Boolean, String> {
        val statements = body.statements
        if (statements.size == 1 && statements[0] is PsiReturnStatement) {
            val retStmt = statements[0] as PsiReturnStatement
            val retExpr = retStmt.returnValue
            if (retExpr != null) {
                return true to cleanJavaExpressionPsi(retExpr)
            }
        }
        val cleanStmts = statements.joinToString("\n") { cleanJavaStatementPsi(it) }
        return false to cleanStmts
    }

    private fun cleanJavaExpressionPsi(expr: PsiExpression): String {
        val text = expr.text
        val rangesToStrip = mutableListOf<TextRange>()
        expr.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                val qualifier = expression.qualifierExpression
                if (qualifier is PsiThisExpression) {
                    rangesToStrip.add(qualifier.textRange)
                }
                super.visitReferenceExpression(expression)
            }
        })
        if (rangesToStrip.isEmpty()) return text
        val baseOffset = expr.textRange.startOffset
        val sb = StringBuilder(text)
        for (range in rangesToStrip.sortedByDescending { it.startOffset }) {
            val start = range.startOffset - baseOffset
            val end = range.endOffset - baseOffset
            if (start in 0..sb.length && end <= sb.length) {
                val lengthToRemove = if (end < sb.length && sb[end] == '.') (end - start + 1) else (end - start)
                sb.delete(start, start + lengthToRemove)
            }
        }
        return sb.toString()
    }

    private fun cleanJavaStatementPsi(stmt: PsiStatement): String {
        if (stmt is PsiTryStatement) {
            val resourceList = stmt.resourceList
            if (resourceList != null) {
                val resText = resourceList.text.trim().removePrefix("(").removeSuffix(")")
                if (resText.isNotBlank()) {
                    val body = stmt.tryBlock?.statements?.joinToString("\n") { cleanJavaStatementPsi(it) }.orEmpty()
                    val parts = resText.split("=").map { it.trim() }
                    val resName = parts.firstOrNull()?.split(" ")?.lastOrNull() ?: "it"
                    val resExpr = parts.getOrNull(1)?.removeSuffix(";") ?: "resource"
                    return "$resExpr.use { $resName ->\n${body.prependIndent("    ")}\n}"
                }
            }
        }
        if (stmt is PsiSwitchStatement) {
            val expr = stmt.expression?.text ?: "it"
            val cases = mutableListOf<String>()
            val switchStatements = stmt.body?.statements.orEmpty()
            var currentCase: String? = null
            for (s in switchStatements) {
                if (s is PsiSwitchLabelStatement) {
                    currentCase = if (s.isDefaultCase) "else" else s.caseValue?.text
                } else if (currentCase != null) {
                    val bodyText = cleanJavaStatementPsi(s).removePrefix("return ").trim()
                    if (bodyText.isNotBlank() && bodyText != "break") {
                        cases.add("$currentCase -> $bodyText")
                        currentCase = null
                    }
                }
            }
            if (cases.isNotEmpty()) {
                return "return when ($expr) {\n${cases.joinToString("\n") { "    $it" }}\n}"
            }
        }

        var text = stmt.text.trim()
        if (text.endsWith(";")) {
            text = text.substring(0, text.length - 1).trimEnd()
        }
        val rangesToReplace = mutableListOf<Pair<TextRange, String>>()

        stmt.accept(object : JavaRecursiveElementVisitor() {
            override fun visitDeclarationStatement(statement: PsiDeclarationStatement) {
                for (element in statement.declaredElements) {
                    if (element is PsiLocalVariable) {
                        var isReassigned = false
                        var p: org.jetbrains.kotlin.com.intellij.psi.PsiElement? = statement.parent
                        while (p != null && p !is PsiCodeBlock && p !is PsiMethod) {
                            p = p.parent
                        }
                        p?.accept(object : JavaRecursiveElementVisitor() {
                            override fun visitAssignmentExpression(expression: org.jetbrains.kotlin.com.intellij.psi.PsiAssignmentExpression) {
                                val ref = expression.lExpression as? PsiReferenceExpression
                                if (ref?.referenceName == element.name) isReassigned = true
                                super.visitAssignmentExpression(expression)
                            }
                            override fun visitUnaryExpression(expression: org.jetbrains.kotlin.com.intellij.psi.PsiUnaryExpression) {
                                val token = expression.operationTokenType
                                if (token == org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.PLUSPLUS ||
                                    token == org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.MINUSMINUS) {
                                    val ref = expression.operand as? PsiReferenceExpression
                                    if (ref?.referenceName == element.name) isReassigned = true
                                }
                                super.visitUnaryExpression(expression)
                            }
                        })
                        val keyword = if (isReassigned) "var" else "val"
                        rangesToReplace.add(element.typeElement.textRange to keyword)
                    }
                }
                super.visitDeclarationStatement(statement)
            }

            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                val qualifier = expression.qualifierExpression
                if (qualifier is PsiThisExpression) {
                    val fullRange = TextRange(qualifier.textRange.startOffset, qualifier.textRange.endOffset + 1)
                    rangesToReplace.add(fullRange to "")
                }
                super.visitReferenceExpression(expression)
            }
        })

        if (rangesToReplace.isEmpty()) return text

        val baseOffset = stmt.textRange.startOffset
        val sb = StringBuilder(text)
        for ((range, replacement) in rangesToReplace.distinctBy { it.first }.sortedByDescending { it.first.startOffset }) {
            val start = range.startOffset - baseOffset
            val end = range.endOffset - baseOffset
            if (start in 0..sb.length && end <= sb.length) {
                sb.replace(start, end, replacement)
            }
        }
        return sb.toString()
    }

    private fun mapJavaTypeToKotlin(javaType: String, isNullable: Boolean = false): String {
        val mapped = primitiveTypeMap[javaType] ?: javaType
            .replace("java.util.List", "List")
            .replace("java.util.Map", "Map")
            .replace("java.util.Set", "Set")

        return if (isNullable && !mapped.endsWith("?")) "$mapped?" else mapped
    }
}
