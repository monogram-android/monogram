plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
}

// Compose compiler stability/skipping reports for the recomposition work, off unless asked for:
//   ./gradlew.bat :core:ui:compileDebugKotlin :feature:chats:compileDebugKotlin -PcomposeReports
// Writes <module>/build/compose-reports/*-composables.txt and *-classes.txt.
subprojects {
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        if (providers.gradleProperty("composeReports").isPresent) {
            extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
                reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
                metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
            }
        }
    }
}
