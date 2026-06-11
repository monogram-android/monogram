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
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        val localProperties: Properties by rootProject.extra

        val apiId = localProperties.getProperty("API_ID", "0")
        val apiHash = localProperties.getProperty("API_HASH", "")
        buildConfigField("int", "API_ID", apiId)
        buildConfigField("String", "API_HASH", "\"$apiHash\"")
        buildConfigField("boolean", "ENABLE_TDLIB_DEBUG", "false")
    }

    flavorDimensions += listOf("tdlib", "runtime")

    productFlavors {
        create("official") {
            dimension = "tdlib"
        }
        create("telemt") {
            dimension = "tdlib"
        }
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
        getByName("official") {
            jniLibs.directories.add("src/official/jniLibs")
        }
        getByName("telemt") {
            jniLibs.directories.add("src/telemt/jniLibs")
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

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.unifiedpush.connector)
    add("firebaseImplementation", platform(libs.firebase.bom))
    add("firebaseImplementation", libs.firebase.messaging)
    add("firebaseImplementation", libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
