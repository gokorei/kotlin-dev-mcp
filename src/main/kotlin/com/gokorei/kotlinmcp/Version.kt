package com.gokorei.kotlinmcp

/**
 * Single source of truth for runtime version and server identity resolution.
 */
object Version {
    const val NAME: String = "kotlin-mcp"

    /**
     * Dynamically resolved server version.
     *
     * Resolution order:
     * 1. JAR Manifest (`Package.implementationVersion`)
     * 2. Build-generated classpath resource (`kotlin-mcp-version.txt`)
     * 3. Fallback constant ("1.1.0")
     */
    val CURRENT: String by lazy {
        Version::class.java.`package`?.implementationVersion
            ?: Version::class.java.classLoader
                ?.getResourceAsStream("kotlin-mcp-version.txt")
                ?.bufferedReader()
                ?.use { it.readText().trim() }
                ?.takeIf { it.isNotEmpty() }
            ?: "1.1.0"
    }
}
