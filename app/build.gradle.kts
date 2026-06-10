import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl
import com.google.android.gms.oss.licenses.plugin.DependencyTask
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.oss.licenses)
    alias(libs.plugins.androidx.baselineprofile)
}

val localProperties = rootProject.extra["localProperties"] as Properties
val googleServicesFile = layout.projectDirectory.file("google-services.json").asFile
val requestedTasks = gradle.startParameter.taskNames
val requestsFirebaseVariant = requestedTasks.any { it.contains("Firebase", ignoreCase = true) }

if (googleServicesFile.exists()) {
    pluginManager.apply("com.google.gms.google-services")
} else if (requestsFirebaseVariant) {
    throw GradleException(
        "Firebase build requested, but app/google-services.json is missing."
    )
}

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
    compileSdk = 37

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
        targetSdk = 37
        versionCode = 11
        versionName = "0.1.1"
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

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
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
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaToolchain.get().toIntOrNull()?: 25)
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaToolchain.get().toIntOrNull()?: 25)
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val tdlibFlavor =
            variant.productFlavors.firstOrNull { it.first == "tdlib" }?.second ?: "default"
        val runtimeFlavor =
            variant.productFlavors.firstOrNull { it.first == "runtime" }?.second ?: "default"
        val apkNamePrefix = buildString {
            append(if (tdlibFlavor == "telemt") "monogram-telemt" else "monogram")
            if (runtimeFlavor == "libre") {
                append("-libre")
            }
        }

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
            description = "Task for copy apk into releases folder"
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
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.bundles.decompose)
    implementation(libs.bundles.koin)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    implementation(libs.androidx.biometric)

    implementation(libs.unifiedpush.connector)
    add("firebaseImplementation", platform(libs.firebase.bom))
    add("firebaseImplementation", libs.firebase.messaging)
    add("firebaseImplementation", libs.play.services.oss.licenses)

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
