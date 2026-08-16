package com.gokorei.kotlinmcp.refactoring

import com.gokorei.kotlinmcp.execution.CompileResult
import com.gokorei.kotlinmcp.execution.SnippetCompiler
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RefactoringServiceTest {

    private lateinit var refactoringService: RefactoringService

    @BeforeEach
    fun setUp() {
        refactoringService = DefaultRefactoringService()
    }

    @Test
    fun `imperative_to_functional converts manual loop to map and filter pipeline`() {
        val imperativeCode = """
            val result = mutableListOf<String>()
            for (item in items) {
                if (item.length > 3) {
                    result.add(item.uppercase())
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.IMPERATIVE_TO_FUNCTIONAL,
            code = imperativeCode
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("filter"))
        assertTrue(success.content.contains("map"))
    }

    @Test
    fun `imperative_to_functional converts short-circuit boolean loop to any`() {
        val code = """
            for (x in items) {
                if (x.isValid()) return true
            }
            return false
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, code = code)
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("items.any"), "expected items.any in: $content")
    }

    @Test
    fun `imperative_to_functional converts short-circuit search loop to firstOrNull`() {
        val code = """
            for (item in list) {
                if (item.id == targetId) return item
            }
            return null
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, code = code)
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("list.firstOrNull"), "expected list.firstOrNull in: $content")
    }

    @Test
    fun `imperative_to_functional converts accumulator loop to fold`() {
        val code = """
            var acc = 0
            for (x in numbers) {
                acc = acc + x * 2
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, code = code)
        assertTrue(result is KotlinMcpResult.Success, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("numbers.fold"), "expected numbers.fold in: $content")
    }

    @Test
    fun `imperative_to_functional converts guarded even-sum to filter and sum`() {
        val imperativeCode = """
            fun sumEvens(numbers: List<Int>): Int {
                var s = 0
                for (n in numbers) {
                    if (n % 2 == 0) s += n
                }
                return s
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.IMPERATIVE_TO_FUNCTIONAL,
            code = imperativeCode
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("filter"), "expected filter in: ${success.content}")
        assertTrue(success.content.contains("sum"), "expected sum in: ${success.content}")
        assertFalse(success.content.contains(".length"), "must not invent fields: ${success.content}")
    }

    @Test
    fun `imperative_to_functional converts plain sum loop to sum`() {
        val imperativeCode = """
            fun sum(xs: List<Int>): Int {
                var total = 0
                for (x in xs) {
                    total += x
                }
                return total
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.IMPERATIVE_TO_FUNCTIONAL,
            code = imperativeCode
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains(".sum()"), "expected .sum() in: ${success.content}")
    }

    @Test
    fun `imperative_to_functional does not truncate nested calls in add argument`() {
        val imperativeCode = """
            val result = mutableListOf<String>()
            for (user in users) {
                if (user.isActive) {
                    result.add(user.name.uppercase())
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.IMPERATIVE_TO_FUNCTIONAL,
            code = imperativeCode
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("user.name.uppercase()"), "nested call must not be truncated: ${success.content}")
        assertFalse(success.content.contains("uppercase( }"), "must not emit truncated pipeline: ${success.content}")
    }

    @Test
    fun `imperative_to_functional handles chained calls without truncation`() {
        val imperativeCode = """
            val out = mutableListOf<Int>()
            for (s in strings) {
                out.add(s.trim().take(3).length)
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.IMPERATIVE_TO_FUNCTIONAL,
            code = imperativeCode
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("s.trim().take(3).length"), "chained call must be intact: ${success.content}")
    }

    @Test
    fun `java_to_kotlin preserves derived methods alongside properties`() {
        val javaCode = """
            public class User {
                private String name;
                private int age;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                public boolean isAdult() { return age >= 18; }
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.JAVA_TO_KOTLIN,
            code = javaCode
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("var name: String"), "expected property: ${success.content}")
        assertTrue(success.content.contains("var age: Int"), "expected property: ${success.content}")
        assertTrue(success.content.contains("fun isAdult()"), "derived method must be preserved: ${success.content}")
        assertTrue(success.content.contains("age >= 18"), "method body must be preserved: ${success.content}")
    }

    @Test
    fun `java_to_kotlin converts Java getter setter boilerplates to Kotlin properties`() {
        val javaCode = """
            public class User {
                private String name;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.JAVA_TO_KOTLIN,
            code = javaCode
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("class User"))
        assertTrue(success.content.contains("var name: String"))
    }

    @Test
    fun `java_to_kotlin preserves semicolons and this dot inside string literals`() {
        val javaCode = """
            public class Config {
                private String setting;
                public String getSetting() { return setting; }
                public void setSetting(String setting) { this.setting = setting; }
                public String getMessage() {
                    return "this.setting = 123; active";
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.JAVA_TO_KOTLIN,
            code = javaCode
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("\"this.setting = 123; active\""), "string literal contents must remain intact: ${success.content}")
    }

    @Test
    fun `java_to_kotlin handles annotated fields and generic parameters via PSI`() {
        val javaCode = """
            public class User {
                @NotNull
                private List<String> tags;
                public List<String> getTags() { return tags; }
                public void setTags(List<String> tags) { this.tags = tags; }
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.JAVA_TO_KOTLIN,
            code = javaCode
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("var tags: List<String>"), "expected tags property in: ${success.content}")
    }

    @Test
    fun `suggest_idioms recommends Result and scope functions`() {
        val snippet = """
            fun parse(input: String): Int {
                try {
                    return input.toInt()
                } catch (e: Exception) {
                    return -1
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.SUGGEST_IDIOMS,
            code = snippet
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("runCatching") || success.content.contains("Result"))
    }

    @Test
    fun `imperative_to_functional converts multiline loop with whitespace via AST parsing`() {
        val imperativeCode = """
            fun process(items: List<String>): List<String> {
                val result = mutableListOf<String>()
                for (item in items) {
                    val formatted = item.trim().uppercase()
                    if (formatted.isNotEmpty()) {
                        result.add(formatted)
                    }
                }
                return result
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.IMPERATIVE_TO_FUNCTIONAL,
            code = imperativeCode
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("map") || success.content.contains("filter"), "expected pipeline in: ${success.content}")
    }

    @Test
    fun `imperative_to_functional handles multiline lambda additions cleanly`() {
        val code = """
            val result = mutableListOf<String>()
            for (item in items) {
                result.add {
                    item.trim().lowercase()
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, code)

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("map"), "expected map pipeline in: ${success.content}")
        assertFalse(success.content.contains(": Any"), "must not erase types to Any: ${success.content}")
    }

    @Test
    fun `generate_quick_fix suggests import for unresolved reference`() {
        val code = """
            val x = UUID.randomUUID()
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.GENERATE_QUICK_FIX,
            code = code,
            diagnostic = "e: Unresolved reference 'UUID'"
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Unresolved reference"), "expected unresolved-reference fix in: ${success.content}")
        assertTrue(success.content.contains("import com.example.UUID"), "expected import suggestion in: ${success.content}")
    }

    @Test
    fun `generate_quick_fix suggests safe handling for non-null assertion`() {
        val code = """
            fun f(s: String?) {
                val l = s!!.length
            }
        """.trimIndent()

        val result = refactoringService.execute(
            action = RefactoringAction.GENERATE_QUICK_FIX,
            code = code,
            diagnostic = "error: Safe call expected"
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("?."), "expected safe-call suggestion in: ${success.content}")
    }

    @Test
    fun `rxjava_to_coroutines maps Observable and Disposable`() {
        val code = """
            Observable.just("a").subscribe { println(it) }
            val d: Disposable = Observable.just(1).subscribe()
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.RXJAVA_TO_COROUTINES, code)

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Flow<T>"), "expected Flow mapping in: ${success.content}")
        assertTrue(success.content.contains("Job"), "expected Job mapping in: ${success.content}")
    }

    @Test
    fun `suggest_idioms proposes abstract mapper as top-level extension`() {
        val code = """
            abstract class Base {
                abstract fun map(src: String): Int
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.SUGGEST_IDIOMS, code)

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("extension"), "expected extension suggestion in: ${success.content}")
    }

    @Test
    fun `refactor_to_arrow detects try catch returning null and produces Either code`() {
        val code = """
            fun parse(input: String): Int? {
                return try {
                    input.toInt()
                } catch (e: NumberFormatException) {
                    return null
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.MIGRATE_ARROW_RAISE, code)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Either<Throwable, Int>"), "expected Either type in: ${success.content}")
        assertTrue(success.content.contains("import arrow.core.Either"), "expected Either import in: ${success.content}")
        assertTrue(success.content.contains("Arrow 2.x"), "default target should be Arrow 2.x: ${success.content}")
    }

    @Test
    fun `refactor_to_arrow legacy knob targets arrow 1x`() {
        val code = """
            fun parse(input: String): Int? {
                return try {
                    input.toInt()
                } catch (e: NumberFormatException) {
                    return null
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.MIGRATE_ARROW_RAISE, code, "true")
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Arrow 1.x"), "expected Arrow 1.x target: ${success.content}")
        assertTrue(success.content.contains(".right()"), "expected monad right() usage in: ${success.content}")
    }

    @Test
    fun `refactor_to_arrow output compiles against arrow-core on classpath`() {
        val code = """
            fun parse(input: String): Int? {
                return try {
                    input.toInt()
                } catch (e: NumberFormatException) {
                    return null
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.MIGRATE_ARROW_RAISE, code)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success

        val fenced = Regex("""```kotlin\n([\s\S]*?)```""").find(success.content)?.groupValues?.get(1) ?: fail("no fenced block")
        val snippet = "$fenced\nfun main() { println(parse(\"1\")) }"
        val compiled = SnippetCompiler.compile(snippet)
        val errors = (compiled as? CompileResult.Compiled)
            ?.diagnostics?.filter { it.severity == "error" }.orEmpty()
        SnippetCompiler.cleanup(compiled)
        assertTrue(errors.isEmpty(), "arrow refactor must compile, got: ${errors.map { it.message }}")
    }

    @Test
    fun `suggest_kotlinx_datetime flags legacy date APIs`() {
        val code = """
            fun now(): Long {
                val d = Date()
                return d.time
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.MIGRATE_DATETIME, code)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Instant"), "expected Instant suggestion in: ${success.content}")
        assertTrue(success.content.contains("import kotlinx.datetime.*"), "expected imports in: ${success.content}")
    }

    @Test
    fun `imperative_to_functional avoids Any typed parameter signatures in multiline loop refactoring`() {
        val code = """
            val result = mutableListOf<String>()
            for (item in items) {
                val trimmed = item.trim()
                if (trimmed.isNotEmpty()) {
                    result.add(trimmed)
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, code)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains(": Any"), "refactored pipeline must not use raw Any type signatures: ${success.content}")
    }

    @Test
    fun `java_to_kotlin translates multi-statement method bodies without inserting TODO stubs`() {
        val javaCode = """
            public class Processor {
                public int compute(int a, int b) {
                    int sum = a + b;
                    return sum * 2;
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.JAVA_TO_KOTLIN, javaCode)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("TODO"), "multi-statement method translation must not collapse into TODO: ${success.content}")
        assertTrue(success.content.contains("val sum = a + b") || success.content.contains("var sum = a + b"), "method statements should be preserved: ${success.content}")
    }

    @Test
    fun `java_to_kotlin does not replace int or String keywords inside string literals in method statements`() {
        val javaCode = """
            public class Formatter {
                public String format() {
                    int count = 42;
                    String message = "int count = 42; String label = \"info\"; this.count = 0;";
                    return message;
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.JAVA_TO_KOTLIN, javaCode)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("val count = 42") || success.content.contains("var count = 42"), "local variable declaration should become val/var: ${success.content}")
        assertTrue(
            success.content.contains("\"int count = 42; String label = \\\"info\\\"; this.count = 0;\""),
            "string literal contents must remain intact without regex replacement corruption: ${success.content}"
        )
    }

    @Test
    fun `java_to_kotlin converts Java code snippet lacking class header via synthetic PSI wrapper`() {
        val javaSnippet = """
            int x = 10;
            String label = "int x = 10";
            return x * 2;
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.JAVA_TO_KOTLIN, javaSnippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("class ConvertedClass"), "expected synthetic class wrapper: ${success.content}")
        assertTrue(success.content.contains("\"int x = 10\""), "string literal in snippet must remain untouched: ${success.content}")
    }

    @Test
    fun `java_to_kotlin converts Java record to Kotlin data class`() {
        val javaRecord = """
            public record Person(String name, int age) {}
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.JAVA_TO_KOTLIN, javaRecord)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("data class Person"), "expected data class: ${success.content}")
        assertTrue(success.content.contains("val name: String"), "expected name prop: ${success.content}")
        assertTrue(success.content.contains("val age: Int"), "expected age prop: ${success.content}")
    }

    @Test
    fun `java_to_kotlin converts Java try-with-resources to use block`() {
        val javaTry = """
            public class Reader {
                public void read(InputStream in) throws Exception {
                    try (InputStream input = in) {
                        input.read();
                    }
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.JAVA_TO_KOTLIN, javaTry)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains(".use {") || success.content.contains("use"), "expected .use block: ${success.content}")
    }

    @Test
    fun `java_to_kotlin converts Java switch statement to Kotlin when`() {
        val javaSwitch = """
            public class SwitchTest {
                public String check(int day) {
                    switch (day) {
                        case 1: return "Monday";
                        default: return "Other";
                    }
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.JAVA_TO_KOTLIN, javaSwitch)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("when (day)"), "expected when expression: ${success.content}")
    }

    @Test
    fun `imperative_to_functional converts map put loop to associate`() {
        val code = """
            val map = mutableMapOf<String, Int>()
            for (item in items) {
                map[item.name] = item.id
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, code)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("associate") || success.content.contains("associateBy"), "expected associate transformation: ${success.content}")
    }

    @Test
    fun `imperative_to_functional converts two-branch loop to partition`() {
        val code = """
            val evens = mutableListOf<Int>()
            val odds = mutableListOf<Int>()
            for (n in numbers) {
                if (n % 2 == 0) {
                    evens.add(n)
                } else {
                    odds.add(n)
                }
            }
        """.trimIndent()

        val result = refactoringService.execute(RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, code)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("partition"), "expected partition transformation: ${success.content}")
    }
}


