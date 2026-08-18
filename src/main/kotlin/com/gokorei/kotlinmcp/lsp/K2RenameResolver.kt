@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils

internal object K2RenameResolver {

    fun renameEditsForSymbol(
        session: K2AnalysisSession,
        symbol: String,
        workspaceFiles: List<Pair<String, KtFile>>
    ): List<ResolvedRenameEdit> {
        val ctx = session.bindingContext

        data class Occurrence(val rel: String, val file: KtFile, val node: PsiElement, val kind: String)

        val occurrences = mutableListOf<Occurrence>()
        fun collect(rel: String, file: KtFile) {
            file.accept(object : KtTreeVisitorVoid() {
                override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                    if (declaration.name == symbol) {
                        occurrences += Occurrence(rel, file, declaration, "decl")
                    }
                    super.visitNamedDeclaration(declaration)
                }

                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    if (expression.getReferencedName() == symbol) {
                        occurrences += Occurrence(rel, file, expression, "ref")
                    }
                    super.visitSimpleNameExpression(expression)
                }
            })
        }

        collect("Snippet.kt", session.file)
        workspaceFiles.forEach { collect(it.first, it.second) }

        fun descriptorOf(decl: KtNamedDeclaration): DeclarationDescriptor? =
            ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, decl]

        fun pickTargets(candidates: List<DeclarationDescriptor>): List<DeclarationDescriptor> {
            val real = candidates.filter { !DescriptorUtils.isLocal(it) && K2ResolutionUtils.isRealFqn(K2ResolutionUtils.safeFqn(it)) }
            return (if (real.isNotEmpty()) real else candidates).distinct()
        }

        val decls = occurrences.filter { it.kind == "decl" }
        val snippetDecls = decls.filter { it.rel == "Snippet.kt" }
        val targets: List<DeclarationDescriptor> = when {
            snippetDecls.isNotEmpty() -> pickTargets(snippetDecls.mapNotNull { descriptorOf(it.node as KtNamedDeclaration) })
            else -> {
                val snippetRefTargets = occurrences
                    .filter { it.kind == "ref" && it.rel == "Snippet.kt" }
                    .mapNotNull { ctx[BindingContext.REFERENCE_TARGET, it.node as KtSimpleNameExpression] }
                if (snippetRefTargets.isNotEmpty()) {
                    pickTargets(snippetRefTargets)
                } else {
                    pickTargets(decls.mapNotNull { descriptorOf(it.node as KtNamedDeclaration) })
                }
            }
        }

        data class Edit(val rel: String, val offset: Int)

        val edits = mutableListOf<Edit>()
        occurrences.forEach { occ ->
            when (occ.kind) {
                "decl" -> {
                    val decl = occ.node as KtNamedDeclaration
                    val d = descriptorOf(decl)
                    if (d != null && targets.any { K2ResolutionUtils.sameTarget(it, d) }) {
                        val offset = decl.nameIdentifier?.textRange?.startOffset ?: decl.textRange.startOffset
                        edits += Edit(occ.rel, offset)
                    }
                }
                "ref" -> {
                    val expr = occ.node as KtSimpleNameExpression
                    val target = ctx[BindingContext.REFERENCE_TARGET, expr]
                    if (target != null && targets.any { K2ResolutionUtils.sameTarget(it, target) }) {
                        edits += Edit(occ.rel, expr.textRange.startOffset)
                    }
                }
            }
        }
        return edits.distinct().map { ResolvedRenameEdit(it.rel, it.offset, symbol.length) }
    }
}
