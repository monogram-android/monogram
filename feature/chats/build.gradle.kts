plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.monogram.feature.chats"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:common"))
    implementation(project(":core:models"))
    implementation(project(":core:database"))
    implementation(libs.mvikotlin)
    implementation(libs.mvikotlin.coroutines)
    implementation(project(":core:ui"))
    implementation(project(":network:bridge"))
    implementation(project(":network:http"))
    implementation(libs.coil.compose)
    implementation(libs.androidx.activity.compose)

    api(libs.decompose)
    implementation(libs.decompose.compose)
    implementation(libs.essenty.lifecycle)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.animation)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mvikotlin.main)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.runtime)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.mvikotlin.main)
}
