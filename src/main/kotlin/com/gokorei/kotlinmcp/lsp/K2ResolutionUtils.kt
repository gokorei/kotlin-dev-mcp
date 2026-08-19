@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import java.io.File

internal object K2ResolutionUtils {

    fun effectiveDescriptor(d: DeclarationDescriptor): DeclarationDescriptor =
        (d as? ClassConstructorDescriptor)?.constructedClass ?: d

    fun safeFqn(descriptor: DeclarationDescriptor?): String? =
        if (descriptor == null) null else runCatching { DescriptorUtils.getFqNameSafe(descriptor).asString() }.getOrNull()

    fun isRealFqn(fqn: String?): Boolean =
        !fqn.isNullOrEmpty() && fqn != "<root>"

    fun sameTarget(a: DeclarationDescriptor, b: DeclarationDescriptor): Boolean {
        if (a == b) return true
        val ea = effectiveDescriptor(a)
        val eb = effectiveDescriptor(b)
        if (ea == eb) return true
        val fa = safeFqn(ea)
        val fb = safeFqn(eb)
        return fa != null && fb != null && isRealFqn(fa) && isRealFqn(fb) && fa == fb
    }

    /** A single named occurrence (declaration or reference) of a symbol in one file. */
    data class SymbolOccurrence(
        val rel: String,
        val file: KtFile,
        val node: PsiElement,
        val kind: String
    )

    /**
     * Collects every named declaration and simple-name reference with the given
     * [symbol] across [files] (snippet plus workspace), in visit order.
     */
    fun collectSymbolOccurrences(symbol: String, files: List<Pair<String, KtFile>>): List<SymbolOccurrence> {
        val occurrences = mutableListOf<SymbolOccurrence>()
        files.forEach { (rel, file) ->
            file.accept(object : KtTreeVisitorVoid() {
                override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                    if (declaration.name == symbol) {
                        occurrences += SymbolOccurrence(rel, file, declaration, "decl")
                    }
                    super.visitNamedDeclaration(declaration)
                }

                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    if (expression.getReferencedName() == symbol) {
                        occurrences += SymbolOccurrence(rel, file, expression, "ref")
                    }
                    super.visitSimpleNameExpression(expression)
                }
            })
        }
        return occurrences
    }

    /**
     * Prefers real (non-local, FQN-bearing) descriptors over local candidates
     * when picking rename/hierarchy targets, mirroring an IDE's selection.
     */
    fun pickTargets(candidates: List<DeclarationDescriptor>): List<DeclarationDescriptor> {
        val real = candidates.filter { !DescriptorUtils.isLocal(it) && isRealFqn(safeFqn(it)) }
        return (if (real.isNotEmpty()) real else candidates).distinct()
    }

    /** Directories never walked as workspace sources (build output, VCS, tooling). */
    fun isExcludedWorkspaceDir(dir: File): Boolean =
        dir.name == "build" || dir.name == ".gradle" || dir.name == ".git" || dir.name == "out" || dir.name == "node_modules"

    fun declarationPsiFor(
        session: K2AnalysisSession,
        files: List<Pair<String, KtFile>>,
        target: DeclarationDescriptor
    ): KtNamedDeclaration? {
        val ctx = session.bindingContext
        val effective = effectiveDescriptor(target)
        fun scan(match: (DeclarationDescriptor) -> Boolean): KtNamedDeclaration? {
            for ((_, file) in files) {
                var hit: KtNamedDeclaration? = null
                file.accept(object : KtTreeVisitorVoid() {
                    override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                        if (hit == null) {
                            val d = ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
                            if (d != null && (d === effective || match(d))) hit = declaration
                        }
                        super.visitNamedDeclaration(declaration)
                    }
                })
                if (hit != null) return hit
            }
            return null
        }
        scan { d -> d == effective }?.let { return it }
        return scan { d -> sameTarget(d, effective) }
    }
}
