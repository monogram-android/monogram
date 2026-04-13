import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl
import com.google.android.gms.oss.licenses.plugin.DependencyTask
import com.google.gms.googleservices.GoogleServicesPlugin

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.oss.licenses)
    alias(libs.plugins.google.services)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "org.monogram.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.monogram"
        minSdk = 25
        targetSdk = 36
        versionCode = 7
        versionName = "0.0.7"
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
            signingConfig = signingConfigs.getByName("debug")
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
        if (variant.buildType != "release") return@onVariants

        variant.outputs.forEach { output ->
            val variantOutput = output as? VariantOutputImpl ?: return@forEach
            val abi = variantOutput.filters.find {
                it.filterType == FilterConfiguration.FilterType.ABI
            }?.identifier ?: "universal"
            val versionName = variantOutput.versionName.orNull ?: "unknown"

            variantOutput.outputFileName.set(
                "monogram-$abi-$versionName-${variant.buildType}.apk"
            )
        }

        val apkDirProvider = variant.artifacts.get(SingleArtifact.APK)

        val capitalizedVariantName = variant.name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        val copyTask = tasks.register<Sync>("copy${capitalizedVariantName}Apk") {
            from(apkDirProvider)
            include("*.apk")
            into(layout.projectDirectory.dir("releases"))

            doFirst {
                destinationDir.mkdirs()
                destinationDir.listFiles()
                    ?.filter { it.isFile && it.extension == "apk" && it.name.startsWith("monogram-") }
                    ?.forEach { it.delete() }
            }
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