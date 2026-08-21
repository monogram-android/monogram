import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.monogram.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 25
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        val localProperties = rootProject.extra["localProperties"] as Properties

        val apiId = localProperties.getProperty("API_ID", "0")
        val apiHash = localProperties.getProperty("API_HASH", "")
        buildConfigField("int", "API_ID", apiId)
        buildConfigField("String", "API_HASH", "\"$apiHash\"")
    }

    flavorDimensions += listOf("runtime")

    productFlavors {
        create("firebase") {
            dimension = "runtime"
        }
        create("libre") {
            dimension = "runtime"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.clear()
        }
        getByName("androidTest") {
            assets.srcDir(file("schemas"))
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaToolchain.get().toIntOrNull()?: 25)
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaToolchain.get().toIntOrNull()?: 25)
    }
    kotlin {
        jvmToolchain(libs.versions.javaToolchain.get().toIntOrNull()?: 25)
    }
    buildFeatures {
        buildConfig = true
    }
    
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", file("schemas").path)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))
    implementation(project(":mtproto"))
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.unifiedpush.connector)
    add("firebaseImplementation", platform(libs.firebase.bom))
    add("firebaseImplementation", libs.firebase.messaging)
    add("firebaseImplementation", libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
