@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.resolve.BindingContext

internal object K2RenameResolver {

    fun renameEditsForSymbol(
        session: K2AnalysisSession,
        symbol: String,
        workspaceFiles: List<Pair<String, KtFile>>
    ): List<ResolvedRenameEdit> {
        val ctx = session.bindingContext

        val occurrences = K2ResolutionUtils.collectSymbolOccurrences(
            symbol,
            listOf("Snippet.kt" to session.file) + workspaceFiles
        )

        fun descriptorOf(decl: KtNamedDeclaration): DeclarationDescriptor? =
            ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, decl]

        val decls = occurrences.filter { it.kind == "decl" }
        val snippetDecls = decls.filter { it.rel == "Snippet.kt" }
        val targets: List<DeclarationDescriptor> = when {
            snippetDecls.isNotEmpty() -> K2ResolutionUtils.pickTargets(snippetDecls.mapNotNull { descriptorOf(it.node as KtNamedDeclaration) })
            else -> {
                val snippetRefTargets = occurrences
                    .filter { it.kind == "ref" && it.rel == "Snippet.kt" }
                    .mapNotNull { ctx[BindingContext.REFERENCE_TARGET, it.node as KtSimpleNameExpression] }
                if (snippetRefTargets.isNotEmpty()) {
                    K2ResolutionUtils.pickTargets(snippetRefTargets)
                } else {
                    K2ResolutionUtils.pickTargets(decls.mapNotNull { descriptorOf(it.node as KtNamedDeclaration) })
                }
            }
        }

        data class Edit(val rel: String, val offset: Int, val length: Int)

        val edits = mutableListOf<Edit>()
        occurrences.forEach { occ ->
            when (occ.kind) {
                "decl" -> {
                    val decl = occ.node as KtNamedDeclaration
                    val d = descriptorOf(decl)
                    if (d != null && targets.any { K2ResolutionUtils.sameTarget(it, d) }) {
                        val range = decl.nameIdentifier?.textRange ?: decl.textRange
                        edits += Edit(occ.rel, range.startOffset, range.length)
                    }
                }
                "ref" -> {
                    val expr = occ.node as KtSimpleNameExpression
                    val target = ctx[BindingContext.REFERENCE_TARGET, expr]
                    if (target != null && targets.any { K2ResolutionUtils.sameTarget(it, target) }) {
                        edits += Edit(occ.rel, expr.textRange.startOffset, expr.textRange.length)
                    }
                }
            }
        }
        return edits.distinct().map { ResolvedRenameEdit(it.rel, it.offset, it.length) }
    }
}
