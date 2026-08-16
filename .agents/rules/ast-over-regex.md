# Agent Rule: Prefer AST Traversal Over Regex for Kotlin Code Analysis

## Mandatory Constraint
When developing features, strategy scanners, refactoring utilities, or analysis tools for the `kotlin-mcp` codebase:

- **DO NOT** use regular expressions (`Regex`), multiline text matching, string splitting, or string search to inspect, parse, or traverse Kotlin source code.
- **ALWAYS** use embedded IntelliJ PSI AST parsing via `K2SnippetFrontend.parsePsi(code)` and `KtTreeVisitorVoid` (or `KtVisitor` subclasses).

## Rationale
Regular expressions fail to parse Kotlin code robustly because:
1. They break on multi-line parameter lists, class headers, default arguments, and annotations.
2. They cannot resolve class inheritance (e.g. `object X : IntIdTable(...)` vs `object X : Table(...)`).
3. They trigger false positives inside comments, KDoc blocks, and string literals.
4. They fail on nested structures, balanced blocks, and type parameters.

## Standard Pattern

```kotlin
val psi = K2SnippetFrontend.parsePsi(sourceCode) ?: return

psi.accept(object : KtTreeVisitorVoid() {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        // Inspect supertypes, properties, annotations, or functions via PSI AST
        val superTypes = classOrObject.superTypeListEntries
        val properties = classOrObject.declarations.filterIsInstance<KtProperty>()
        super.visitClassOrObject(classOrObject)
    }
})
```

This rule applies to all Kotlin code analysis, scanning, and traversal logic in this codebase.
