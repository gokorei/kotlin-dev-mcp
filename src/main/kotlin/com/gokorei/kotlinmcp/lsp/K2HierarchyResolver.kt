@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.shared.SourceUtils
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext

object K2HierarchyResolver {

    fun typeHierarchy(
        session: K2AnalysisSession,
        symbol: String,
        workspaceFiles: List<Pair<String, KtFile>>
    ): KtTypeHierarchyResult {
        val ctx = session.bindingContext

        data class ClassEntry(val rel: String, val psi: KtFile, val node: KtClassOrObject)

        val classes = mutableListOf<ClassEntry>()
        fun collect(rel: String, psi: KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                    if (classOrObject.name == symbol) classes += ClassEntry(rel, psi, classOrObject)
                    super.visitClassOrObject(classOrObject)
                }
            })
        }
        collect("Snippet.kt", session.file)
        workspaceFiles.forEach { collect(it.first, it.second) }

        val ordered = classes.filter { it.rel == "Snippet.kt" } + classes.filter { it.rel != "Snippet.kt" }
        val descriptors = ordered.mapNotNull { ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, it.node] as? ClassDescriptor }
        val realDescriptors = descriptors.filter { K2ResolutionUtils.isRealFqn(K2ResolutionUtils.safeFqn(it)) }
        val target = (realDescriptors.ifEmpty { descriptors }).firstOrNull()
            ?: return KtTypeHierarchyResult(symbol, emptyList(), emptyList())

        val supertypes = target.typeConstructor.supertypes
            .mapNotNull { runCatching { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(it) }.getOrNull() }
            .distinct()

        val inheritsCache = hashMapOf<ClassDescriptor, Boolean>()
        fun inherits(d: ClassDescriptor): Boolean = inheritsCache.getOrPut(d) {
            val queue = ArrayDeque<ClassDescriptor>()
            val seen = hashSetOf<DeclarationDescriptor>()
            queue.add(d)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (!seen.add(cur)) continue
                for (superType in cur.typeConstructor.supertypes) {
                    val dd = superType.constructor.declarationDescriptor as? ClassDescriptor ?: continue
                    if (K2ResolutionUtils.sameTarget(dd, target)) return@getOrPut true
                    queue.add(dd)
                }
            }
            false
        }

        val subtypes = mutableListOf<KtTypeOccurrence>()
        fun scanForSubtypes(rel: String, psi: KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                    val d = ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, classOrObject] as? ClassDescriptor
                    val name = classOrObject.name
                    if (d != null && inherits(d) && name != null) {
                        val offset = classOrObject.nameIdentifier?.textRange?.startOffset ?: classOrObject.textRange.startOffset
                        subtypes += KtTypeOccurrence(name, rel, SourceUtils.lineOf(psi.text, offset))
                    }
                    super.visitClassOrObject(classOrObject)
                }
            })
        }
        scanForSubtypes("Snippet.kt", session.file)
        workspaceFiles.forEach { scanForSubtypes(it.first, it.second) }

        return KtTypeHierarchyResult(symbol, supertypes, subtypes.distinctBy { Pair(it.name, it.file) })
    }

    fun callHierarchy(
        session: K2AnalysisSession,
        symbol: String,
        workspaceFiles: List<Pair<String, KtFile>>
    ): KtCallHierarchyResult {
        val ctx = session.bindingContext

        fun pickTargets(candidates: List<DeclarationDescriptor>): List<DeclarationDescriptor> =
            K2ResolutionUtils.pickTargets(candidates)

        val functionDecls = mutableListOf<Pair<String, KtNamedDeclaration>>()
        val snippetCallTargets = mutableListOf<DeclarationDescriptor>()
        fun collect(rel: String, psi: KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(function: KtNamedFunction) {
                    if (function.name == symbol) functionDecls += rel to function
                    super.visitNamedFunction(function)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    val callee = expression.calleeExpression as? KtSimpleNameExpression
                    if (callee != null && callee.getReferencedName() == symbol && rel == "Snippet.kt") {
                        ctx[BindingContext.REFERENCE_TARGET, callee]?.let { snippetCallTargets.add(it) }
                    }
                    super.visitCallExpression(expression)
                }
            })
        }
        collect("Snippet.kt", session.file)
        workspaceFiles.forEach { collect(it.first, it.second) }

        val snippetDeclTargets = functionDecls
            .filter { it.first == "Snippet.kt" }
            .mapNotNull { (_, decl) -> ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, decl] }
        val targets: List<DeclarationDescriptor> = when {
            snippetDeclTargets.isNotEmpty() -> pickTargets(snippetDeclTargets)
            snippetCallTargets.isNotEmpty() -> pickTargets(snippetCallTargets)
            else -> pickTargets(functionDecls.mapNotNull { (_, decl) -> ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, decl] })
        }
        if (targets.isEmpty()) return KtCallHierarchyResult(symbol, emptyList())

        fun enclosingFunction(node: org.jetbrains.kotlin.com.intellij.psi.PsiElement): KtNamedFunction? {
            var ancestor = node.parent
            while (ancestor != null) {
                if (ancestor is KtNamedFunction) return ancestor
                ancestor = ancestor.parent
            }
            return null
        }

        val callers = mutableListOf<KtCallOccurrence>()
        fun scanCalls(rel: String, psi: KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    val callee = expression.calleeExpression as? KtSimpleNameExpression
                    val resolved = callee?.let { ctx[BindingContext.REFERENCE_TARGET, it] }
                    if (resolved != null && targets.any { K2ResolutionUtils.sameTarget(it, resolved) }) {
                        val enclosing = enclosingFunction(expression)
                        val line = SourceUtils.lineOf(psi.text, expression.textRange.startOffset)
                        callers += KtCallOccurrence(enclosing?.name, rel, line, expression.text.take(80))
                    }
                    super.visitCallExpression(expression)
                }
            })
        }
        scanCalls("Snippet.kt", session.file)
        workspaceFiles.forEach { scanCalls(it.first, it.second) }

        return KtCallHierarchyResult(symbol, callers.distinct())
    }
}
