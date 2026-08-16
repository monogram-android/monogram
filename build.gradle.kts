import java.util.Properties
import org.gradle.api.tasks.Exec

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.google.oss.licenses) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

val localProperties by lazy {
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().buffered().use(::load)
    }
}
extra.set("localProperties", localProperties)

val telegramTlSnapshotScript = layout.projectDirectory.file("protocol/schema/snapshot.py")
val refreshTelegramTlSchemas = tasks.register<Exec>("refreshTelegramTlSchemas") {
    group = "telegram schema"
    description = "Explicitly refreshes reviewed TL JSON snapshots from a pinned LF-preserving tellers-tl checkout."
    outputs.upToDateWhen { false }

    val toolDirectory = providers.gradleProperty("tellersTlDir")
    val generatedAt = providers.gradleProperty("telegramTlGeneratedAt")
    doFirst {
        require(toolDirectory.isPresent) {
            "refreshTelegramTlSchemas requires -PtellersTlDir=<pinned LF-preserving checkout>"
        }
        require(generatedAt.isPresent) {
            "refreshTelegramTlSchemas requires -PtelegramTlGeneratedAt=<RFC3339 UTC timestamp>"
        }
        commandLine(
            "python",
            telegramTlSnapshotScript.asFile.absolutePath,
            "export",
            "--tool-dir",
            toolDirectory.get(),
            "--generated-at",
            generatedAt.get(),
        )
    }
}

tasks.register("refreshTelegramTlSchema") {
    group = "telegram schema"
    description = "Alias for refreshTelegramTlSchemas."
    dependsOn(refreshTelegramTlSchemas)
}

val tdlibFlavors = listOf("Official", "Telemt")
val runtimeFlavors = listOf("Firebase", "Libre")
val buildTypes = listOf("Debug", "Release")

data class AppAssemblyVariant(
    val tdlib: String,
    val runtime: String,
    val buildType: String,
) {
    val name: String get() = "$tdlib$runtime$buildType"
    val unitTestBuildType: String get() = "Debug"
    val unitTestVariantName: String get() = "$tdlib$runtime$unitTestBuildType"
}

val appAssemblyVariants =
    tdlibFlavors.flatMap { tdlib ->
        runtimeFlavors.flatMap { runtime ->
            buildTypes.map { buildType ->
                AppAssemblyVariant(
                    tdlib = tdlib,
                    runtime = runtime,
                    buildType = buildType,
                )
            }
        }
    }

val verifyBeforeAssemble =
    providers.gradleProperty("verifyBeforeAssemble")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(false)
        .get()

appAssemblyVariants.forEach { variant ->
    val verifyTask = tasks.register("verify${variant.name}BeforeAssemble") {
        group = "verification"
        description = "Runs module unit tests before assembling the ${variant.name} APK."
        dependsOn(
            ":app:test${variant.unitTestVariantName}UnitTest",
            ":data:test${variant.unitTestVariantName}UnitTest",
            ":presentation:test${variant.unitTestVariantName}UnitTest",
            ":core:test",
            ":domain:test",
        )
    }

    if (verifyBeforeAssemble) {
        project(":app").tasks.matching { it.name == "assemble${variant.name}" }.configureEach {
            dependsOn(verifyTask)
        }
    }
}

tasks.register("assembleOfficialReleaseTdlibApks") {
    group = "build"
    description = "Assembles release APKs with the official TDLib prebuilts."
    dependsOn(":app:assembleOfficialFirebaseRelease")
}

tasks.register("assembleTelemtReleaseTdlibApks") {
    group = "build"
    description = "Assembles release APKs with the Telemt TDLib prebuilts."
    dependsOn(":app:assembleTelemtFirebaseRelease")
}

tasks.register("assembleAllReleaseTdlibApks") {
    group = "build"
    description = "Assembles release APKs for both official and Telemt TDLib variants."
    dependsOn(
        "assembleOfficialReleaseTdlibApks",
        "assembleTelemtReleaseTdlibApks"
    )
}

tasks.register("assembleOfficialDebugTdlibApks") {
    group = "build"
    description = "Assembles debug APKs with the official TDLib prebuilts."
    dependsOn(":app:assembleOfficialFirebaseDebug")
}

tasks.register("assembleTelemtDebugTdlibApks") {
    group = "build"
    description = "Assembles debug APKs with the Telemt TDLib prebuilts."
    dependsOn(":app:assembleTelemtFirebaseDebug")
}

tasks.register("assembleAllDebugTdlibApks") {
    group = "build"
    description = "Assembles debug APKs for both official and Telemt TDLib variants."
    dependsOn(
        "assembleOfficialDebugTdlibApks",
        "assembleTelemtDebugTdlibApks"
    )
}
