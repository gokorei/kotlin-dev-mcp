package com.gokorei.kotlinmcp.mutation

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/**
 * Category grouping for AST mutators, indicating baseline vs extreme structural mutations.
 */
enum class MutatorCategory {
    STANDARD,
    EXTENDED,
    EXTREME
}

/**
 * Context provided to mutators during AST traversal.
 */
data class MutationContext(
    val code: String,
    val file: KtFile
) {
    fun lineAndCol(offset: Int): Pair<Int, Int> = computeLineAndColumn(code, offset)
}

/**
 * Descriptor of a discrete AST replacement edit.
 */
data class AstEdit(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
    val originalText: String,
    val operator: MutationOperator,
    val description: String,
    val line: Int,
    val column: Int
)

/**
 * Pluggable SPI interface for individual mutation operators.
 * Enables modular addition of new AST mutator rules, extreme structural transforms,
 * or property-based testing (PBT) invariant generators without modifying the engine visitor.
 */
interface AstMutator {
    val operator: MutationOperator
    val category: MutatorCategory
    fun canMutate(element: PsiElement): Boolean
    fun mutate(element: PsiElement, context: MutationContext): List<AstEdit>
}

// -------------------------------------------------------------------------------------------------
// Standard Mutators
// -------------------------------------------------------------------------------------------------

/**
 * Mutates relational boundary operators (< <-> <=, > <-> >=, == <-> !=).
 */
class RelationalBoundaryMutator : AstMutator {
    override val operator: MutationOperator = MutationOperator.RELATIONAL_BOUNDARY
    override val category: MutatorCategory = MutatorCategory.STANDARD

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        val sign = element.operationReference.operationSignTokenType
        return sign == KtTokens.LT || sign == KtTokens.LTEQ ||
            sign == KtTokens.GT || sign == KtTokens.GTEQ ||
            sign == KtTokens.EQEQ || sign == KtTokens.EXCLEQ
    }

    override fun mutate(element: PsiElement, context: MutationContext): List<AstEdit> {
        val expr = element as? KtBinaryExpression ?: return emptyList()
        val opRef = expr.operationReference
        val opElement = opRef.operationSignTokenType
        val replacements = when (opElement) {
            KtTokens.LT -> listOf("<=" to "Replaced < with <=")
            KtTokens.LTEQ -> listOf("<" to "Replaced <= with <")
            KtTokens.GT -> listOf(">=" to "Replaced > with >=")
            KtTokens.GTEQ -> listOf(">" to "Replaced >= with >")
            KtTokens.EQEQ -> listOf("!=" to "Replaced == with !=")
            KtTokens.EXCLEQ -> listOf("==" to "Replaced != with ==")
            else -> emptyList()
        }

        return replacements.map { (replacement, desc) ->
            val range = opRef.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = replacement,
                originalText = expr.text,
                operator = operator,
                description = desc,
                line = line,
                column = col
            )
        }
    }
}

/**
 * Mutates arithmetic operators (+ <-> -, * <-> /, % <-> *).
 */
class ArithmeticOperatorMutator : AstMutator {
    override val operator: MutationOperator = MutationOperator.ARITHMETIC_OPERATOR
    override val category: MutatorCategory = MutatorCategory.STANDARD

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtBinaryExpression) return false
        val sign = element.operationReference.operationSignTokenType
        return sign == KtTokens.PLUS || sign == KtTokens.MINUS ||
            sign == KtTokens.MUL || sign == KtTokens.DIV || sign == KtTokens.PERC
    }

    override fun mutate(element: PsiElement, context: MutationContext): List<AstEdit> {
        val expr = element as? KtBinaryExpression ?: return emptyList()
        val opRef = expr.operationReference
        val opElement = opRef.operationSignTokenType
        val replacements = when (opElement) {
            KtTokens.PLUS -> listOf("-" to "Replaced + with -")
            KtTokens.MINUS -> listOf("+" to "Replaced - with +")
            KtTokens.MUL -> listOf("/" to "Replaced * with /")
            KtTokens.DIV -> listOf("*" to "Replaced / with *")
            KtTokens.PERC -> listOf("*" to "Replaced % with *")
            else -> emptyList()
        }

        return replacements.map { (replacement, desc) ->
            val range = opRef.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = replacement,
                originalText = expr.text,
                operator = operator,
                description = desc,
                line = line,
                column = col
            )
        }
    }
}

/**
 * Mutates boolean prefixes (!flag -> flag), boolean literals (true <-> false),
 * and logical operators (&& <-> ||).
 */
class BooleanInversionMutator : AstMutator {
    override val operator: MutationOperator = MutationOperator.BOOLEAN_INVERSION
    override val category: MutatorCategory = MutatorCategory.STANDARD

    override fun canMutate(element: PsiElement): Boolean {
        if (element is KtPrefixExpression && element.operationToken == KtTokens.EXCL && element.baseExpression != null) {
            return true
        }
        if (element is KtConstantExpression && (element.text == "true" || element.text == "false")) {
            return true
        }
        if (element is KtBinaryExpression) {
            val sign = element.operationReference.operationSignTokenType
            return sign == KtTokens.ANDAND || sign == KtTokens.OROR
        }
        return false
    }

    override fun mutate(element: PsiElement, context: MutationContext): List<AstEdit> {
        return when (element) {
            is KtPrefixExpression -> {
                val base = element.baseExpression ?: return emptyList()
                val range = element.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                listOf(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = base.text,
                        originalText = element.text,
                        operator = operator,
                        description = "Negation inverted (removed '!')",
                        line = line,
                        column = col
                    )
                )
            }
            is KtConstantExpression -> {
                val text = element.text
                val range = element.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                val replacement = if (text == "true") "false" else "true"
                listOf(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = replacement,
                        originalText = text,
                        operator = operator,
                        description = "Inverted boolean literal from '$text' to '$replacement'",
                        line = line,
                        column = col
                    )
                )
            }
            is KtBinaryExpression -> {
                val opRef = element.operationReference
                val sign = opRef.operationSignTokenType
                val (replacement, desc) = if (sign == KtTokens.ANDAND) {
                    "||" to "Replaced && with ||"
                } else {
                    "&&" to "Replaced || with &&"
                }
                val range = opRef.textRange
                val (line, col) = context.lineAndCol(range.startOffset)
                listOf(
                    AstEdit(
                        startOffset = range.startOffset,
                        endOffset = range.endOffset,
                        replacement = replacement,
                        originalText = element.text,
                        operator = operator,
                        description = desc,
                        line = line,
                        column = col
                    )
                )
            }
            else -> emptyList()
        }
    }
}

/**
 * Mutates return expressions by substituting default values (0, false, empty string).
 */
class ReturnValueMutator : AstMutator {
    override val operator: MutationOperator = MutationOperator.RETURN_VALUE
    override val category: MutatorCategory = MutatorCategory.STANDARD

    override fun canMutate(element: PsiElement): Boolean {
        return element is KtReturnExpression && element.returnedExpression != null
    }

    override fun mutate(element: PsiElement, context: MutationContext): List<AstEdit> {
        val returnExpr = element as? KtReturnExpression ?: return emptyList()
        val returned = returnExpr.returnedExpression ?: return emptyList()
        val range = returned.textRange
        val (line, col) = context.lineAndCol(range.startOffset)
        val text = returned.text.trim()
        val isStringExpr = returned is KtStringTemplateExpression ||
            (returned is KtBinaryExpression && (returned.left is KtStringTemplateExpression || returned.right is KtStringTemplateExpression))

        val replacements = mutableListOf<Pair<String, String>>()
        if (isStringExpr) {
            replacements.add("\"\"" to "Replaced return string with empty string")
            replacements.add("\"mutated\"" to "Replaced return string with altered string")
        } else {
            replacements.add("0" to "Replaced return value with 0")
            replacements.add("false" to "Replaced return value with false")
        }

        return replacements.filter { text != it.first }.map { (replacement, desc) ->
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = replacement,
                originalText = returnExpr.text,
                operator = operator,
                description = desc,
                line = line,
                column = col
            )
        }
    }
}

/**
 * Mutates standalone void statements by replacing them with Unit.
 */
class VoidMethodCallMutator : AstMutator {
    override val operator: MutationOperator = MutationOperator.VOID_CALL_REMOVAL
    override val category: MutatorCategory = MutatorCategory.STANDARD

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtCallExpression) return false
        val parent = element.parent
        return parent is KtBlockExpression || (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression)
    }

    override fun mutate(element: PsiElement, context: MutationContext): List<AstEdit> {
        val callExpr = element as? KtCallExpression ?: return emptyList()
        val parent = callExpr.parent
        val targetElement: PsiElement = if (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression) {
            parent
        } else callExpr

        val range = targetElement.textRange
        val (line, col) = context.lineAndCol(range.startOffset)
        return listOf(
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = "Unit",
                originalText = targetElement.text,
                operator = operator,
                description = "Omitted statement '${targetElement.text.take(30)}'",
                line = line,
                column = col
            )
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Extreme / Extended Mutators
// -------------------------------------------------------------------------------------------------

/**
 * Modifies integer constant literals (+1, -1).
 */
class LiteralMutationMutator : AstMutator {
    override val operator: MutationOperator = MutationOperator.LITERAL_MUTATION
    override val category: MutatorCategory = MutatorCategory.EXTREME

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtConstantExpression) return false
        val text = element.text
        return text != "true" && text != "false" && text.toIntOrNull() != null
    }

    override fun mutate(element: PsiElement, context: MutationContext): List<AstEdit> {
        val constExpr = element as? KtConstantExpression ?: return emptyList()
        val text = constExpr.text
        val intVal = text.toIntOrNull() ?: return emptyList()
        val range = constExpr.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf((intVal + 1).toString(), (intVal - 1).toString()).map { mutatedNum ->
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = mutatedNum,
                originalText = text,
                operator = operator,
                description = "Altered integer constant $text -> $mutatedNum",
                line = line,
                column = col
            )
        }
    }
}

/**
 * Inverts higher-order collection methods (filter <-> filterNot, any <-> all, take <-> drop, first <-> last).
 */
class CollectionOperatorMutator : AstMutator {
    override val operator: MutationOperator = MutationOperator.COLLECTION_OPERATOR
    override val category: MutatorCategory = MutatorCategory.EXTREME

    private val supportedMethods = setOf("filter", "filterNot", "any", "all", "take", "drop", "first", "last")

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtCallExpression) return false
        val calleeName = element.calleeExpression?.text
        return calleeName in supportedMethods
    }

    override fun mutate(element: PsiElement, context: MutationContext): List<AstEdit> {
        val callExpr = element as? KtCallExpression ?: return emptyList()
        val callee = callExpr.calleeExpression ?: return emptyList()
        val calleeName = callee.text
        val replacements = when (calleeName) {
            "filter" -> listOf("filterNot" to "Inverted filter -> filterNot")
            "filterNot" -> listOf("filter" to "Inverted filterNot -> filter")
            "any" -> listOf("all" to "Inverted any -> all")
            "all" -> listOf("any" to "Inverted all -> any")
            "take" -> listOf("drop" to "Inverted take -> drop")
            "drop" -> listOf("take" to "Inverted drop -> take")
            "first" -> listOf("last" to "Inverted first -> last")
            "last" -> listOf("first" to "Inverted last -> first")
            else -> emptyList()
        }

        return replacements.map { (rep, desc) ->
            val range = callee.textRange
            val (line, col) = context.lineAndCol(range.startOffset)
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = rep,
                originalText = callee.text,
                operator = operator,
                description = desc,
                line = line,
                column = col
            )
        }
    }
}

/**
 * Replaces boolean conditions in if-expressions with constant true / false.
 */
class ConditionReplacementMutator : AstMutator {
    override val operator: MutationOperator = MutationOperator.CONDITION_REPLACEMENT
    override val category: MutatorCategory = MutatorCategory.EXTREME

    override fun canMutate(element: PsiElement): Boolean {
        if (element !is KtIfExpression) return false
        val cond = element.condition ?: return false
        return cond.text != "true" && cond.text != "false"
    }

    override fun mutate(element: PsiElement, context: MutationContext): List<AstEdit> {
        val ifExpr = element as? KtIfExpression ?: return emptyList()
        val cond = ifExpr.condition ?: return emptyList()
        val range = cond.textRange
        val (line, col) = context.lineAndCol(range.startOffset)

        return listOf("true", "false").map { boolRep ->
            AstEdit(
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                replacement = boolRep,
                originalText = cond.text,
                operator = operator,
                description = "Replaced condition '${cond.text}' with '$boolRep'",
                line = line,
                column = col
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Mutator Registry
// -------------------------------------------------------------------------------------------------

/**
 * Registry containing all active mutation operator rules.
 * Supports registering custom user or property-based testing (PBT) mutation extensions.
 */
class MutatorRegistry(
    mutators: List<AstMutator> = defaultMutators()
) {
    private val registeredMutators = mutableListOf<AstMutator>().apply { addAll(mutators) }

    fun register(mutator: AstMutator): MutatorRegistry {
        registeredMutators.add(mutator)
        return this
    }

    fun mutators(includeExtreme: Boolean = false): List<AstMutator> {
        return if (includeExtreme) {
            registeredMutators.toList()
        } else {
            registeredMutators.filter { it.category == MutatorCategory.STANDARD }
        }
    }

    companion object {
        fun defaultMutators(): List<AstMutator> = listOf(
            RelationalBoundaryMutator(),
            ArithmeticOperatorMutator(),
            BooleanInversionMutator(),
            ReturnValueMutator(),
            VoidMethodCallMutator(),
            LiteralMutationMutator(),
            CollectionOperatorMutator(),
            ConditionReplacementMutator()
        )

        fun default(): MutatorRegistry = MutatorRegistry(defaultMutators())
    }
}

internal fun computeLineAndColumn(source: String, offset: Int): Pair<Int, Int> {
    var line = 1
    var lastLineBreak = -1
    for (i in 0 until offset.coerceAtMost(source.length)) {
        if (source[i] == '\n') {
            line++
            lastLineBreak = i
        }
    }
    val col = offset - lastLineBreak
    return Pair(line, col)
}
