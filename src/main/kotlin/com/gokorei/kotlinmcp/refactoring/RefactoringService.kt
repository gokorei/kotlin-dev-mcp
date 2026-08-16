package com.gokorei.kotlinmcp.refactoring

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.shared.CommandService

enum class RefactoringAction {
    JAVA_TO_KOTLIN,
    CONVERT_JAVA_TO_KOTLIN,
    IMPERATIVE_TO_FUNCTIONAL,
    CONVERT_IMPERATIVE_LOOP_TO_FUNCTIONAL,
    RXJAVA_TO_COROUTINES,
    MIGRATE_RXJAVA_TO_COROUTINES,
    MIGRATE_ARROW_RAISE,
    GENERATE_QUICK_FIX,
    SUGGEST_IDIOMS,
    SUGGEST_IDIOMATIC_KOTLIN,
    MIGRATE_DATETIME
}

/**
 * Service interface for code refactorings, Java-to-Kotlin translation, and Arrow/RxJava/Datetime migration.
 */
interface RefactoringService : CommandService<RefactoringAction> {
    fun execute(action: RefactoringAction, code: String, diagnostic: String? = null): KotlinMcpResult
    override fun execute(action: RefactoringAction, code: String): KotlinMcpResult =
        execute(action, code, diagnostic = null)
}

/**
 * Single-responsibility facade routing [RefactoringAction] operations to dedicated refactoring strategy components.
 */
class DefaultRefactoringService(
    private val javaToKotlinRefactorer: JavaToKotlinRefactorer = JavaToKotlinRefactorer(),
    private val loopToFunctionalRefactorer: LoopToFunctionalRefactorer = LoopToFunctionalRefactorer(),
    private val rxJavaToCoroutinesRefactorer: RxJavaToCoroutinesRefactorer = RxJavaToCoroutinesRefactorer(),
    private val arrowRefactorer: ArrowRefactorer = ArrowRefactorer(),
    private val quickFixGenerator: QuickFixGenerator = QuickFixGenerator(),
    private val idiomaticKotlinSuggestor: IdiomaticKotlinSuggestor = IdiomaticKotlinSuggestor(),
    private val datetimeMigrationSuggestor: DatetimeMigrationSuggestor = DatetimeMigrationSuggestor()
) : RefactoringService {

    override fun execute(action: RefactoringAction, code: String, diagnostic: String?): KotlinMcpResult {
        return when (action) {
            RefactoringAction.JAVA_TO_KOTLIN, RefactoringAction.CONVERT_JAVA_TO_KOTLIN ->
                javaToKotlinRefactorer.convertJavaToKotlin(code)
            RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, RefactoringAction.CONVERT_IMPERATIVE_LOOP_TO_FUNCTIONAL ->
                loopToFunctionalRefactorer.convertImperativeToFunctional(code)
            RefactoringAction.RXJAVA_TO_COROUTINES, RefactoringAction.MIGRATE_RXJAVA_TO_COROUTINES ->
                rxJavaToCoroutinesRefactorer.migrateRxJavaToCoroutines(code)
            RefactoringAction.MIGRATE_ARROW_RAISE ->
                arrowRefactorer.refactorToArrow(code, diagnostic)
            RefactoringAction.GENERATE_QUICK_FIX ->
                quickFixGenerator.generateQuickFix(code, diagnostic.orEmpty())
            RefactoringAction.SUGGEST_IDIOMS, RefactoringAction.SUGGEST_IDIOMATIC_KOTLIN ->
                idiomaticKotlinSuggestor.suggestIdiomaticKotlin(code)
            RefactoringAction.MIGRATE_DATETIME ->
                datetimeMigrationSuggestor.suggestDatetimeMigration(code)
        }
    }
}
