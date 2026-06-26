plugins {
    id("com.android.test")
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "org.monogram.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 25
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy("tdlib", "official")
        missingDimensionStrategy("runtime", "libre")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaToolchain.get().toIntOrNull()?: 25)
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaToolchain.get().toIntOrNull()?: 25)
    }

    kotlin {
        jvmToolchain(libs.versions.javaToolchain.get().toIntOrNull()?: 25)
    }

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.test.ext.junit)
}