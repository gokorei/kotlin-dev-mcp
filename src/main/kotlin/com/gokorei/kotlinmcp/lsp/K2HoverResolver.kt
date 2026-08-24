@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.shared.SourceUtils
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext

object K2HoverResolver {

    fun hover(
        session: K2AnalysisSession,
        symbol: String,
        files: List<Pair<String, KtFile>>
    ): KtHoverInfo? {
        val ctx = session.bindingContext

        var reference: KtSimpleNameExpression? = null
        var declaration: KtNamedDeclaration? = null
        session.file.accept(object : KtTreeVisitorVoid() {
            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                if (reference == null && expression.getReferencedName() == symbol) {
                    reference = expression
                }
                super.visitSimpleNameExpression(expression)
            }

            override fun visitNamedDeclaration(decl: KtNamedDeclaration) {
                if (declaration == null && decl !is org.jetbrains.kotlin.psi.KtPrimaryConstructor && decl.name == symbol) {
                    declaration = decl
                }
                super.visitNamedDeclaration(decl)
            }
        })
        if (reference == null && declaration == null) return null

        val descriptor = (reference?.let { ctx[BindingContext.REFERENCE_TARGET, it] }
            ?: declaration?.let { ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, it] })
            ?: return null
        val effective = K2ResolutionUtils.effectiveDescriptor(descriptor)

        val expressionType: String? = reference?.let { ref ->
            val typeNode: KtExpression = (ref.parent as? KtCallExpression) ?: ref
            ctx[BindingContext.EXPRESSION_TYPE_INFO, typeNode]?.type
                ?.let { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(it) }
        }
        val declaredType: String? = (declaration as? KtProperty)?.let { prop ->
            val t = prop.initializer?.let { ctx[BindingContext.EXPRESSION_TYPE_INFO, it]?.type }
                ?: prop.typeReference?.let { ctx[BindingContext.TYPE, it] }
            t?.let { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(it) }
        }
        val type = expressionType ?: declaredType

        val signature = runCatching { DescriptorRenderer.FQ_NAMES_IN_TYPES.render(descriptor) }.getOrNull()
        val fqn = K2ResolutionUtils.safeFqn(effective)

        val declPsi = K2ResolutionUtils.declarationPsiFor(session, files, descriptor)
        val source: ResolvedSource
        val file: String?
        val line: Int?
        val kdoc: String?
        if (declPsi != null) {
            val containing = declPsi.containingFile as KtFile
            val isSnippet = containing == session.file || containing.name.startsWith("Snippet_")
            source = if (isSnippet) ResolvedSource.SNIPPET else ResolvedSource.WORKSPACE
            file = if (isSnippet) "Snippet.kt" else files.firstOrNull { it.second == containing }?.first ?: containing.name
            line = SourceUtils.lineOf(containing.text, declPsi.textRange.startOffset)
            kdoc = declPsi.docComment?.text?.trim()?.takeIf { it.isNotBlank() }
        } else {
            source = ResolvedSource.EXTERNAL
            file = null
            line = null
            kdoc = null
        }
        return KtHoverInfo(symbol, type, signature, fqn, source, file, line, kdoc)
    }
}
