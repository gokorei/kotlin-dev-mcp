@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType

internal object K2CompletionResolver {

    fun completionCandidates(session: K2AnalysisSession, prefix: String): KotlinCompletionCandidates {
        val dot = prefix.lastIndexOf('.')
        val memberPrefix = if (dot >= 0) prefix.substring(dot + 1) else ""
        val receiver = if (dot >= 0) prefix.substring(0, dot).trim() else ""
        val members = if (receiver.isNotEmpty()) {
            receiverType(session, receiver)?.let { memberNamesOf(it, memberPrefix, session.moduleDescriptor) }.orEmpty()
        } else {
            emptyList()
        }
        return KotlinCompletionCandidates(members, scopeCandidates(session, prefix))
    }

    /** Best resolved type for a receiver expression text, preferring the last occurrence. */
    private fun receiverType(session: K2AnalysisSession, receiver: String): KotlinType? {
        val ctx = session.bindingContext
        val hits = mutableListOf<Pair<Int, KotlinType>>()
        session.file.accept(object : KtTreeVisitorVoid() {
            override fun visitExpression(expression: KtExpression) {
                if (expression.text == receiver) {
                    ctx[BindingContext.EXPRESSION_TYPE_INFO, expression]?.type?.let { hits.add(expression.textRange.startOffset to it) }
                }
                super.visitExpression(expression)
            }

            override fun visitProperty(property: KtProperty) {
                if (property.name == receiver) {
                    val t = property.initializer?.let { ctx[BindingContext.EXPRESSION_TYPE_INFO, it]?.type }
                        ?: property.typeReference?.let { ctx[BindingContext.TYPE, it] }
                    t?.let { hits.add(property.textRange.startOffset to it) }
                }
                super.visitProperty(property)
            }

            override fun visitParameter(parameter: KtParameter) {
                if (parameter.name == receiver) {
                    parameter.typeReference?.let { ctx[BindingContext.TYPE, it]?.let { t -> hits.add(parameter.textRange.startOffset to t) } }
                }
                super.visitParameter(parameter)
            }
        })
        return hits.maxByOrNull { it.first }?.second
    }

    private fun memberNamesOf(type: KotlinType, prefix: String, module: ModuleDescriptor?): List<String> {
        val cls = type.constructor.declarationDescriptor as? ClassDescriptor ?: return emptyList()
        val names = linkedSetOf<String>()
        runCatching {
            cls.unsubstitutedMemberScope.getContributedDescriptors(DescriptorKindFilter.ALL, MemberScope.ALL_NAME_FILTER).forEach { d ->
                val n = d.name.asString()
                val vis = (d as? MemberDescriptor)?.visibility
                if (!n.startsWith("<") && vis != DescriptorVisibilities.PRIVATE && vis != DescriptorVisibilities.PRIVATE_TO_THIS) {
                    if (prefix.isBlank() || n.startsWith(prefix, ignoreCase = true)) names.add(n)
                }
            }
        }
        if (module != null) {
            for (pkgName in listOf("kotlin.text", "kotlin.collections", "kotlin")) {
                runCatching {
                    val pkg = module.getPackage(org.jetbrains.kotlin.name.FqName(pkgName))
                    pkg.memberScope.getContributedDescriptors(DescriptorKindFilter.CALLABLES, MemberScope.ALL_NAME_FILTER)
                        .filterIsInstance<FunctionDescriptor>()
                        .forEach { fn ->
                            val n = fn.name.asString()
                            if (!n.startsWith("<")) {
                                val recv = fn.extensionReceiverParameter?.type
                                if (recv != null && safeFqn(recv.constructor.declarationDescriptor) == safeFqn(cls) &&
                                    (prefix.isBlank() || n.startsWith(prefix, ignoreCase = true))
                                ) {
                                    names.add(n)
                                }
                            }
                        }
                }
            }
        }
        return names.sorted()
    }

    /** In-scope names: declarations, parameters and imported names matching [prefix]. */
    private fun scopeCandidates(session: K2AnalysisSession, prefix: String): List<String> {
        val names = linkedSetOf<String>()
        session.file.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                if (declaration !is org.jetbrains.kotlin.psi.KtPrimaryConstructor) {
                    declaration.name?.let { names.add(it) }
                }
                super.visitNamedDeclaration(declaration)
            }

            override fun visitParameter(parameter: KtParameter) {
                parameter.name?.let { names.add(it) }
                super.visitParameter(parameter)
            }
        })
        session.file.importDirectives.forEach { dir ->
            dir.importedFqName?.let { names.add(it.shortName().asString()) }
            dir.aliasName?.let { names.add(it) }
        }
        return names
            .filter { prefix.isBlank() || it.startsWith(prefix, ignoreCase = true) || it.contains(prefix, ignoreCase = true) }
            .sortedBy { it.lowercase() }
            .distinct()
    }

    private fun safeFqn(descriptor: DeclarationDescriptor?): String? =
        if (descriptor == null) null else runCatching { DescriptorUtils.getFqNameSafe(descriptor).asString() }.getOrNull()
}
