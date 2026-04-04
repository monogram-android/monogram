import java.util.Properties

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
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