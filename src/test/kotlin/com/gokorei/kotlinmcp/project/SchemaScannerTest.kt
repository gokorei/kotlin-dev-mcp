package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SchemaScannerTest {

    private val scanner = SchemaScanner()

    private fun fixtureProject(): java.nio.file.Path {
        val dir = Files.createTempDirectory("kmcp-schema")
        val sqlDir = dir.resolve("src/main/resources/db")
        Files.createDirectories(sqlDir)
        Files.writeString(sqlDir.resolve("schema.sql"), """
            CREATE TABLE users (
                id BIGINT PRIMARY KEY,
                email VARCHAR(255) NOT NULL,
                is_active BOOLEAN DEFAULT true
            );

            CREATE TABLE orders (
                id BIGINT,
                user_id BIGINT,
                total DECIMAL(10, 2),
                CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id)
            );
        """.trimIndent())

        val ktDir = dir.resolve("src/main/kotlin/com/app")
        Files.createDirectories(ktDir)
        Files.writeString(ktDir.resolve("UsersTable.kt"), """
            package com.app

            import org.jetbrains.exposed.sql.Table

            object UsersTable : Table("users") {
                val id = long("id")
                val name = varchar("name", 255)
            }
        """.trimIndent())
        Files.writeString(ktDir.resolve("UserDto.kt"), """
            package com.app.dto

            import kotlinx.serialization.Serializable

            @Serializable
            data class UserDto(
                val id: Long,
                val name: String,
                val email: String? = null
            )
        """.trimIndent())

        Files.writeString(dir.resolve("openapi.yaml"), """
            openapi: 3.0.0
            paths:
              /api/users:
                get:
                  summary: List users
                post:
                  summary: Create user
              /api/users/{id}:
                get:
                  summary: Get user
        """.trimIndent())
        return dir
    }

    @Test
    fun `scanSchemas detects SQL CREATE TABLE statements with columns`() {
        val dir = fixtureProject()
        try {
            val result = scanner.scanSchemas(dir.toString())
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("users"), "expected users table: ${success.content}")
            assertTrue(success.content.contains("email VARCHAR(255) NOT NULL"), "expected email column: ${success.content}")
            assertTrue(success.content.contains("orders"), "expected orders table: ${success.content}")
            assertEquals("2", success.metadata["sqlTableCount"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scanSchemas detects Exposed Table objects with columns`() {
        val dir = fixtureProject()
        try {
            val result = scanner.scanSchemas(dir.toString())
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("UsersTable"), "expected exposed table: ${success.content}")
            assertTrue(success.content.contains("id = long(\"id\")"), "expected exposed column: ${success.content}")
            assertEquals("1", success.metadata["exposedTableCount"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scanSchemas detects Exposed subclasses like IntIdTable and LongIdTable`() {
        val dir = Files.createTempDirectory("kmcp-schema-idtable")
        val ktDir = dir.resolve("src/main/kotlin/com/app")
        Files.createDirectories(ktDir)
        Files.writeString(ktDir.resolve("ProductsTable.kt"), """
            package com.app

            import org.jetbrains.exposed.dao.id.IntIdTable

            object ProductsTable : IntIdTable("products") {
                val sku = varchar("sku", 64)
                val price = decimal("price", 10, 2)
            }
        """.trimIndent())

        try {
            val result = scanner.scanSchemas(dir.toString())
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("ProductsTable"), "expected ProductsTable: ${success.content}")
            assertTrue(success.content.contains("sku = varchar(\"sku\", 64)"), "expected sku column: ${success.content}")
            assertEquals("1", success.metadata["exposedTableCount"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scanSchemas detects serializable DTOs with fields and types`() {
        val dir = fixtureProject()
        try {
            val result = scanner.scanSchemas(dir.toString())
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("UserDto"), "expected dto: ${success.content}")
            assertTrue(success.content.contains("name: String"), "expected dto field: ${success.content}")
            assertEquals("1", success.metadata["dtoCount"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scanSchemas detects OpenAPI path definitions`() {
        val dir = fixtureProject()
        try {
            val result = scanner.scanSchemas(dir.toString())
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("/api/users"), "expected openapi path: ${success.content}")
            assertTrue(success.content.contains("get /api/users"), "expected openapi operation: ${success.content}")
            assertEquals("1", success.metadata["openApiSpecCount"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `schema_digest routes through project service with projectPath only`() {
        val dir = fixtureProject()
        try {
            val service = DefaultProjectService()
            val result = service.execute(
                action = ProjectAction.SCHEMA_DIGEST,
                buildScriptContent = "",
                projectPath = dir.toString()
            )
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("users"), "expected sql table in routed result: ${success.content}")
            assertTrue(success.content.contains("UserDto"), "expected dto in routed result: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scanSchemas returns MISSING_PROJECT_PATH when path is blank`() {
        val result = scanner.scanSchemas(null)
        assertTrue(result.isError)
        assertTrue(result.toFormattedText().contains("MISSING_PROJECT_PATH"))
    }

    @Test
    fun `scanSchemas returns INVALID_PATH for a missing directory`() {
        val result = scanner.scanSchemas("/nonexistent/kmcp-schema-missing")
        assertTrue(result.isError)
        assertTrue(result.toFormattedText().contains("INVALID_PATH"))
    }

    @Test
    fun `scanSchemas ignores commented-out SQL columns`() {
        val dir = Files.createTempDirectory("kmcp-schema-comments")
        val sqlDir = dir.resolve("src/main/resources/db")
        Files.createDirectories(sqlDir)
        Files.writeString(sqlDir.resolve("schema.sql"), """
            CREATE TABLE accounts (
                id BIGINT PRIMARY KEY,
                -- legacy_column VARCHAR(255),
                /* multiline_old_column TEXT, */
                active BOOLEAN
            );
        """.trimIndent())

        try {
            val result = scanner.scanSchemas(dir.toString())
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("accounts"))
            assertTrue(success.content.contains("active BOOLEAN"))
            assertFalse(success.content.contains("legacy_column"), "should not contain commented legacy_column: ${success.content}")
            assertFalse(success.content.contains("multiline_old_column"), "should not contain multiline_old_column: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scanSchemas reports no schemas for an empty project`() {
        val dir = Files.createTempDirectory("kmcp-schema-empty")
        try {
            val result = scanner.scanSchemas(dir.toString())
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("No schema definitions detected"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}