import com.android.build.VariantOutput
import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import com.google.android.gms.oss.licenses.plugin.DependencyTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.oss.licenses)
    alias(libs.plugins.google.services)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "org.monogram.app"

    compileSdk = 36
    
    buildFeatures {
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        applicationId = "org.monogram"
        minSdk = 25
        targetSdk = 36
        versionCode = 5
        versionName = "1.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as ApkVariantOutputImpl
            val abi = output.getFilter(VariantOutput.FilterType.ABI) ?: "universal"
            output.outputFileName = "monogram-$abi-${variant.versionName}(${variant.versionCode})-${variant.buildType.name}.apk"
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
    implementation(libs.bundles.androidx.compose)
    
    implementation(libs.bundles.decompose)
    implementation(libs.bundles.koin)
    
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    
    implementation(libs.androidx.biometric)
    implementation(libs.play.services.oss.licenses)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.maplibre.compose)

    implementation(project(":domain"))
    implementation(project(":presentation"))
    implementation(project(":data"))
    implementation(project(":core"))

    baselineProfile(project(":baselineprofile"))
}

tasks.withType(DependencyTask::class.java).configureEach {
    if (name == "debugOssDependencyTask") {
        dependsOn("releaseOssDependencyTask")
        doLast {
            val releaseJson = layout.buildDirectory
                .file("generated/third_party_licenses/release/dependencies.json")
                .get()
                .asFile
            val debugJson = dependenciesJson.get().asFile
            if (releaseJson.exists()) {
                releaseJson.copyTo(debugJson, overwrite = true)
            }
        }
    }
}
