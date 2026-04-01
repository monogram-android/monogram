plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "org.monogram.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.test.ext.junit)
}