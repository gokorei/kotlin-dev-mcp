import java.io.File
import java.net.URI
import java.time.Duration
import java.time.Instant
import org.gradle.api.DefaultTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jsoup:jsoup:1.18.3")
    }
}

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
    application
}

kotlin {
    explicitApiWarning()
}

group = "com.gokorei"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

configurations.configureEach {
    if (name !in setOf("detektTooling", "ktlintTooling")) {
        resolutionStrategy {
            eachDependency {
                if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-compiler-embeddable") {
                    useVersion("2.3.20")
                    because("Align embedded compiler version for JDK 25 compatibility")
                }
            }
        }
    }
}

// Phase B tooling classpaths (C995FNR9): detekt and ktlint ship with their own
// embedded compiler versions (2.0.x / 1.9.x) that are NOT compatible with the
// server's forced 2.3.20 embeddable. They are resolved into dedicated
// configurations, dumped to generated files, and run in subprocesses so each
// tool loads its own compiler.
val detektTooling by configurations.creating
val ktlintTooling by configurations.creating

dependencies {
    detektTooling("io.gitlab.arturbosch.detekt:detekt-cli:1.23.7")
    ktlintTooling("com.pinterest.ktlint:ktlint-cli:1.3.1")
}

val dumpToolingClasspaths = tasks.register("dumpToolingClasspaths") {
    group = "build"
    description = "Dumps the resolved detekt/ktlint tooling classpaths so LintService can launch them in subprocesses."
    val outDir = layout.buildDirectory.dir("generated/tooling")
    outputs.dir(outDir)
    doLast {
        outDir.get().asFile.mkdirs()
        outDir.get().file("detekt.classpath.txt").asFile.writeText(detektTooling.asPath)
        outDir.get().file("ktlint.classpath.txt").asFile.writeText(ktlintTooling.asPath)
    }
}

sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("generated/tooling"))
            srcDir(layout.buildDirectory.dir("generated/kotlin-docs"))
        }
    }
}

tasks.processResources {
    dependsOn(dumpToolingClasspaths)
    dependsOn(processStdlibIndex)
}

repositories {
    mavenCentral()
}

dependencies {
    // Official MCP SDK for Kotlin
    implementation("io.modelcontextprotocol:kotlin-sdk:0.14.0")

    // Kotlinx Serialization & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.lukelast.ktoon:ktoon:5.0.0")

    // Embedded Kotlin Compiler for lightweight in-memory snippet diagnostics & K2 PSI analysis
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")

    // Logging: kotlin-logging over slf4j, with slf4j-simple as the backend
    // (writes to stderr, keeping stdout reserved for MCP JSON-RPC frames).
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    // Phase C library-awareness dependencies (9PJYFA83). Additive: existing
    // tools are unaffected; these back the library-specific analysis tools.
    implementation("io.arrow-kt:arrow-core:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.mockk:mockk:1.13.13")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        suppressWarnings.set(true)
        allWarningsAsErrors.set(false)
        optIn.add("org.jetbrains.kotlin.K1Deprecation")
    }
}








tasks.test {
    useJUnitPlatform()
    // Serial forks: the 2-core CI runner thrashes under concurrent test JVMs
    // (each fork loads the Kotlin compiler and spawns detekt/ktlint/snippet
    // subprocesses), measurably slowing the suite. Each method is bounded at
    // 5m so a stuck fork still fails the build instead of blocking forever.
    maxParallelForks = 1
    // Hard bound for the whole suite so a stuck test JVM fails the build
    // instead of running forever (per-method cap comes from
    // src/test/resources/junit-platform.properties).
    timeout = Duration.ofMinutes(20)
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
    val testTmpDir = layout.buildDirectory.dir("tmp/test-workers")
    doFirst {
        testTmpDir.get().asFile.mkdirs()
    }
    systemProperty("java.io.tmpdir", testTmpDir.get().asFile.path)
    systemProperty("jna.tmpdir", testTmpDir.get().asFile.path)
    systemProperty("kmcp.disable_network_audits", "true")
}





java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}



application {
    mainClass.set("com.gokorei.kotlinmcp.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
}

val uberJar = tasks.register<Jar>("uberJar") {
    group = "build"
    description = "Assembles an Uber/Fat JAR containing all runtime dependencies and application classes."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.gokorei.kotlinmcp.MainKt"
    }
    val dependencies = configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    from(dependencies)
    with(tasks.jar.get())
}

tasks.assemble {
    dependsOn(uberJar)
}

val dokkaDocs = tasks.register("dokkaDocs") {
    group = "documentation"
    description = "Generates Dokka API documentation publication."
    dependsOn("dokkaGenerate")
}

val generateMcpDocs = tasks.register<JavaExec>("generateMcpDocs") {
    group = "documentation"
    description = "Generates the Markdown MCP tool reference directly from in-code tool definitions."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gokorei.kotlinmcp.doc.McpDocGeneratorKt")
    val docFile = layout.projectDirectory.file("docs/wiki/Tool-Reference.md")
    args = listOf(docFile.asFile.absolutePath)
    outputs.file(docFile)
}


// F6CQ4XSC: syncKotlinDocs — fetch the official Kotlin stdlib API index and update the embedded doc database.
val syncKotlinDocs = tasks.register<DefaultTask>("syncKotlinDocs") {
    group = "documentation"
    description = "Fetches the official Kotlin stdlib API index (all-types page) and updates build/kotlin-docs-src/ (raw source for processStdlibIndex)."
    val srcDir = layout.buildDirectory.dir("kotlin-docs-src")
    val outputFile = srcDir.map { it.file("stdlib-index.html") }
    inputs.property("indexUrl", "https://kotlinlang.org/api/core/kotlin-stdlib/all-types.html")
    outputs.file(outputFile)
    doLast {
        val file: File = outputFile.get().asFile
        if (file.exists() && file.length() > 0) {
            logger.lifecycle("syncedKotlinDocs: using existing cached index ${file.path} (${file.length()} bytes)")
            return@doLast
        }
        try {
            val url: URI = URI("https://kotlinlang.org/api/core/kotlin-stdlib/all-types.html")
            val connection = url.toURL().openConnection()
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("User-Agent", "kotlin-mcp-syncdocs/1.0")
            val html: String = connection.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
            srcDir.get().asFile.mkdirs()
            file.writeText(html)
            logger.lifecycle("syncedKotlinDocs: wrote ${file.path} (${html.length} bytes fetched)")
        } catch (e: Exception) {
            logger.warn("syncedKotlinDocs: failed to fetch Kotlin stdlib index from network (${e.message}). DocService falls back to built-in entries.")
        }
    }
}

// CJCHJBQC: processStdlibIndex — normalize the fetched stdlib HTML into a JSON
// index (symbol name + first-paragraph summary) that DocService seeds from at
// runtime. Output is packaged as a resource in the uberJar.
val processStdlibIndex = tasks.register<DefaultTask>("processStdlibIndex") {
    group = "documentation"
    description = "Normalizes the fetched kotlinlang.org stdlib HTML into build/generated/kotlin-docs/stdlib-index.json (name + first-paragraph summary per symbol) consumed by DocService."
    dependsOn(syncKotlinDocs)
    val srcDir = layout.buildDirectory.dir("kotlin-docs-src")
    val outDir = layout.buildDirectory.dir("generated/kotlin-docs")
    val inputFile = srcDir.map { it.file("stdlib-index.html") }
    val outputFile = outDir.map { it.file("stdlib-index.json") }
    inputs.file(inputFile)
    outputs.file(outputFile)
    doLast {
        val htmlFile = inputFile.get().asFile
        if (!htmlFile.exists()) {
            logger.warn("processStdlibIndex: ${htmlFile.path} absent; skipping index generation (DocService falls back to hardcoded entries).")
            return@doLast
        }
        val doc = org.jsoup.Jsoup.parse(htmlFile, Charsets.UTF_8.name())
        val entries = mutableListOf<Map<String, String>>()
        doc.select("div.table-row").forEach { row ->
            val name = row.select("a[href\$=/index.html]")
                .mapNotNull { it.text()?.trim()?.takeIf { t -> t.startsWith("kotlin.") && t.length <= 200 } }
                .firstOrNull()
            val summary = row.select("p.paragraph").firstOrNull()?.text()?.trim()
            if (name != null && !summary.isNullOrBlank()) {
                entries.add(mapOf("name" to name, "summary" to summary))
            }
        }
        outDir.get().asFile.mkdirs()
        val distinct = entries.distinctBy { it["name"] }
        val json = groovy.json.JsonOutput.toJson(distinct)
        outputFile.get().asFile.writeText(json)
        logger.lifecycle("processStdlibIndex: wrote ${outputFile.get().asFile.path} with ${distinct.size} symbols")
    }
}


