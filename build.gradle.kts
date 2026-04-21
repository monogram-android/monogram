import java.util.Properties

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

tasks.register("assembleOfficialReleaseTdlibApks") {
    group = "build"
    description = "Assembles release APKs with the official TDLib prebuilts."
    dependsOn(":app:assembleOfficialRelease")
}

tasks.register("assembleTelemtReleaseTdlibApks") {
    group = "build"
    description = "Assembles release APKs with the Telemt TDLib prebuilts."
    dependsOn(":app:assembleTelemtRelease")
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
    dependsOn(":app:assembleOfficialDebug")
}

tasks.register("assembleTelemtDebugTdlibApks") {
    group = "build"
    description = "Assembles debug APKs with the Telemt TDLib prebuilts."
    dependsOn(":app:assembleTelemtDebug")
}

tasks.register("assembleAllDebugTdlibApks") {
    group = "build"
    description = "Assembles debug APKs for both official and Telemt TDLib variants."
    dependsOn(
        "assembleOfficialDebugTdlibApks",
        "assembleTelemtDebugTdlibApks"
    )
}
