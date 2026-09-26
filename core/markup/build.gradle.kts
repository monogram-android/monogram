plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.monogram.core.markup"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:models"))
    implementation(project(":native:markup"))

    testImplementation(libs.junit)
}
