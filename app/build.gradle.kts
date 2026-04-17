import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl
import com.google.android.gms.oss.licenses.plugin.DependencyTask
import com.google.gms.googleservices.GoogleServicesPlugin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.oss.licenses)
    alias(libs.plugins.google.services)
    alias(libs.plugins.androidx.baselineprofile)
}

val localProperties = rootProject.extra["localProperties"] as Properties

val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword =
    localProperties.getProperty("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword =
    localProperties.getProperty("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all {
        !it.isNullOrBlank()
    }

android {
    namespace = "org.monogram.app"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "org.monogram"
        minSdk = 25
        targetSdk = 36
        versionCode = 8
        versionName = "0.0.8"
    }

    flavorDimensions += "tdlib"

    productFlavors {
        create("official") {
            dimension = "tdlib"
        }
        create("telemt") {
            dimension = "tdlib"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildFeatures {
                resValues = true
            }
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
            resValue("string", "app_name", "MonoGram")
        }
        debug {
            buildFeatures {
                resValues = true
            }
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            resValue("string", "app_name", "MonoGram Debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val flavorName = variant.productFlavors
            .map { it.second }
            .joinToString("-")
            .ifEmpty { "default" }
        val apkNamePrefix = if (flavorName == "telemt") "monogram-telemt" else "monogram"

        variant.outputs.forEach { output ->
            val variantOutput = output as? VariantOutputImpl ?: return@forEach
            val abi = variantOutput.filters.find {
                it.filterType == FilterConfiguration.FilterType.ABI
            }?.identifier ?: "universal"
            val versionName = variantOutput.versionName.orNull ?: "unknown"

            variantOutput.outputFileName.set(
                "$apkNamePrefix-$abi-$versionName-${variant.buildType}.apk"
            )
        }

        if (variant.buildType != "release") return@onVariants

        val apkDirProvider = variant.artifacts.get(SingleArtifact.APK)

        val capitalizedVariantName = variant.name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        val copyTask = tasks.register<Copy>("copy${capitalizedVariantName}Apk") {
            from(apkDirProvider)
            include("*.apk")
            into(layout.projectDirectory.dir("releases"))
        }

        project.tasks.matching { it.name == "assemble${capitalizedVariantName}" }.configureEach {
            finalizedBy(copyTask)
        }
    }
}

configurations.configureEach {
    val tink = "com.google.crypto.tink:tink-android:1.21.0"
    resolutionStrategy {
        force(tink)
        dependencySubstitution {
            substitute(module("com.google.crypto.tink:tink")).using(module(tink))
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.androidx.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.bundles.decompose)
    implementation(libs.bundles.koin)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    implementation(libs.androidx.biometric)
    implementation(libs.play.services.oss.licenses)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.unifiedpush.connector)

    implementation(libs.maplibre.compose)

    implementation(project(":domain"))
    implementation(project(":presentation"))
    implementation(project(":data"))
    implementation(project(":core"))

    baselineProfile(project(":baselineprofile"))
}

tasks.withType(DependencyTask::class.java).configureEach {
    if (name == "debugOssDependencyTask") {
        val releaseJsonProvider =
            layout.buildDirectory.file("generated/third_party_licenses/release/dependencies.json")
        val debugJsonProvider =
            layout.buildDirectory.file("generated/third_party_licenses/debug/dependencies.json")

        dependsOn("releaseOssDependencyTask")

        doLast {
            val releaseJson = releaseJsonProvider.get().asFile
            val debugJson = debugJsonProvider.get().asFile
            if (releaseJson.exists()) {
                debugJson.parentFile?.mkdirs()
                releaseJson.copyTo(debugJson, overwrite = true)
            }
        }
    }
}

googleServices {
    missingGoogleServicesStrategy = GoogleServicesPlugin.MissingGoogleServicesStrategy.WARN
}