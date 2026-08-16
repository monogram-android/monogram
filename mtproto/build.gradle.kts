import org.monogram.buildlogic.tl.GenerateTlKotlinTask
import org.monogram.buildlogic.tl.ValidateTlSchemasTask
import org.monogram.buildlogic.tl.VerifyGeneratedTlKotlinTask

plugins {
    alias(libs.plugins.android.library)
}

val tlKotlinCodegenClasspath = configurations.create("tlKotlinCodegenClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Build-time classpath for deterministic Telegram TL Kotlin generation."
}

val telegramTlSchemaRoot = rootProject.layout.projectDirectory.dir("protocol/schema")
val telegramTlSchemaManifest = telegramTlSchemaRoot.file("manifest.json")
val telegramTlSchemaSnapshots = rootProject.fileTree(telegramTlSchemaRoot) {
    include("**/*.json")
    exclude("manifest.json")
}
val telegramTlGeneratedRoot = layout.buildDirectory.dir("generated/source/tl/main/kotlin")

val validateTelegramTlSchemas = tasks.register<ValidateTlSchemasTask>("validateTelegramTlSchemas") {
    group = "verification"
    description = "Validates the pinned Telegram TL schema manifest and snapshots."
    cliClasspath.from(tlKotlinCodegenClasspath)
    schemaManifest.set(telegramTlSchemaManifest)
    schemaSnapshots.from(telegramTlSchemaSnapshots)
    validationReport.set(layout.buildDirectory.file("reports/tl/schema-validation.json"))
}

val generateTelegramTlKotlin = tasks.register<GenerateTlKotlinTask>("generateTelegramTlKotlin") {
    group = "build"
    description = "Generates deterministic Kotlin declarations, codecs, and registries from pinned schemas."
    dependsOn(validateTelegramTlSchemas)
    cliClasspath.from(tlKotlinCodegenClasspath)
    schemaManifest.set(telegramTlSchemaManifest)
    schemaSnapshots.from(telegramTlSchemaSnapshots)
    generatedSources.set(telegramTlGeneratedRoot)
    generationReport.set(layout.buildDirectory.file("reports/tl/generation.json"))
}

val verifyTelegramTlGeneration = tasks.register<VerifyGeneratedTlKotlinTask>("verifyTelegramTlGeneration") {
    group = "verification"
    description = "Regenerates in memory and byte-verifies the existing Telegram TL Kotlin output."
    dependsOn(validateTelegramTlSchemas)
    mustRunAfter(generateTelegramTlKotlin)
    cliClasspath.from(tlKotlinCodegenClasspath)
    schemaManifest.set(telegramTlSchemaManifest)
    schemaSnapshots.from(telegramTlSchemaSnapshots)
    generatedSources.set(telegramTlGeneratedRoot)
    verificationReport.set(layout.buildDirectory.file("reports/tl/generation-verification.json"))
}

android {
    namespace = "org.monogram.mtproto"
    compileSdk = 37

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaToolchain.get().toIntOrNull() ?: 25)
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaToolchain.get().toIntOrNull() ?: 25)
    }

    kotlin {
        jvmToolchain(libs.versions.javaToolchain.get().toIntOrNull() ?: 25)
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateTelegramTlKotlin,
            GenerateTlKotlinTask::generatedSources,
        )
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(generateTelegramTlKotlin, verifyTelegramTlGeneration)
}

dependencies {
    add(tlKotlinCodegenClasspath.name, project(":tools:tl-kotlin-codegen"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
