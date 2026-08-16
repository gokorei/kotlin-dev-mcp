package com.gokorei.kotlinmcp.refactoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RefactoringComponentsTest {

    @Test
    fun `JavaToKotlinRefactorer converts Java class definition to Kotlin`() {
        val javaCode = """
            public class User {
                private String name;
                public String getName() { return name; }
            }
        """.trimIndent()

        val converter = JavaToKotlinRefactorer()
        val result = converter.convertJavaToKotlin(javaCode)
        assertTrue(result.isSuccess)
        val text = result.toFormattedText()
        assertTrue(text.contains("class User") || text.contains("var name") || text.contains("val name"))
    }

    @Test
    fun `LoopToFunctionalRefactorer converts imperative list accumulation to map`() {
        val code = """
            fun process(items: List<Int>): List<Int> {
                val result = mutableListOf<Int>()
                for (item in items) {
                    result.add(item * 2)
                }
                return result
            }
        """.trimIndent()

        val converter = LoopToFunctionalRefactorer()
        val result = converter.convertImperativeToFunctional(code)
        assertTrue(result.isSuccess)
        val text = result.toFormattedText()
        assertTrue(text.contains("items.map"))
    }

    @Test
    fun `RxJavaToCoroutinesRefactorer converts Observable create to flow`() {
        val code = """
            fun stream(): Observable<String> {
                return Observable.create { emitter ->
                    emitter.onNext("hello")
                    emitter.onComplete()
                }
            }
        """.trimIndent()

        val converter = RxJavaToCoroutinesRefactorer()
        val result = converter.migrateRxJavaToCoroutines(code)
        assertTrue(result.isSuccess)
        val text = result.toFormattedText()
        assertTrue(text.contains("flow") || text.contains("Flow<String>"))
    }

    @Test
    fun `JavaToKotlinRefactorer preserves string literals containing type keywords`() {
        val javaCode = """
            public class Logger {
                public void log() {
                    System.out.println("int count = 5");
                }
            }
        """.trimIndent()

        val converter = JavaToKotlinRefactorer()
        val result = converter.convertJavaToKotlin(javaCode)
        assertTrue(result.isSuccess)
        val text = result.toFormattedText()
        assertTrue(text.contains("\"int count = 5\""), "String literal must not be corrupted by type replacements: $text")
    }

    @Test
    fun `RxJavaToCoroutinesRefactorer converts Single and subscribeOn operator chains`() {
        val code = """
            fun loadData(): Single<String> {
                return Single.fromCallable { "data" }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
            }
        """.trimIndent()

        val converter = RxJavaToCoroutinesRefactorer()
        val result = converter.migrateRxJavaToCoroutines(code)
        assertTrue(result.isSuccess)
        val text = result.toFormattedText()
        assertTrue(text.contains("withContext") || text.contains("flowOn") || text.contains("Dispatchers"), "expected coroutine dispatcher mapping: $text")
    }

    @Test
    fun `QuickFixGenerator places import after package directive and file annotations`() {
        val code = """
            @file:OptIn(ExperimentalStdlibApi::class)
            package com.example.app

            fun runMe() {
                val item = FooHelper()
            }
        """.trimIndent()

        val generator = QuickFixGenerator()
        val result = generator.generateQuickFix(code, "Unresolved reference: FooHelper")
        assertTrue(result.isSuccess)
        val text = result.toFormattedText()
        assertTrue(text.contains("import com.example.FooHelper"))
        // DiffUtils shows line number 3 for inserted import (after line 2 package declaration)
        assertTrue(text.contains("3|+|import com.example.FooHelper"), "Import must be placed at line 3 (after package directive). Output:\n$text")
    }
}
