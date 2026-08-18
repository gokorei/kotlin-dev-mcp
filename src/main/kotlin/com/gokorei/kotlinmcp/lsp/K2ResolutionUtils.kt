@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils

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
