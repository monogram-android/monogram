import com.android.build.VariantOutput
import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.oss.licenses)
    alias(libs.plugins.google.services)
}

android {
    namespace = "org.monogram.app"

    compileSdk = 36
    
    buildFeatures {
        compose = true
    }
    defaultConfig {
        applicationId = "org.monogram"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as ApkVariantOutputImpl
            val abi = output.getFilter(VariantOutput.FilterType.ABI) ?: "universal"
            output.outputFileName = "monogram-$abi-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "MonoGram")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            resValue("string", "app_name", "MonoGram Debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    implementation(libs.androidx.material.icons.extended.android)
    implementation(libs.decompose)
    implementation(libs.decompose.compose)
    implementation(project(":domain"))
    implementation(project(":presentation"))
    implementation(project(":data"))
    implementation(project(":core"))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.biometric)
    implementation(libs.play.services.oss.licenses)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}