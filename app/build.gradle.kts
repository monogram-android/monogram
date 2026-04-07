import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.FilterConfiguration
import com.google.android.gms.oss.licenses.plugin.DependencyTask
import com.google.gms.googleservices.GoogleServicesPlugin

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

    defaultConfig {
        applicationId = "org.monogram"
        minSdk = 25
        targetSdk = 36
        versionCode = 6
        versionName = "0.0.6"
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val apkDirProvider = variant.artifacts.get(SingleArtifact.APK)
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()

        val renameTask = tasks.register("rename${variant.name.capitalize()}Apk") {
            inputs.dir(apkDirProvider)

            doLast {
                val builtArtifacts = artifactsLoader.load(apkDirProvider.get())!!
                val targetDir = apkDirProvider.get().asFile

                builtArtifacts.elements.forEach { artifact ->
                    val abi = artifact.filters.find {
                        it.filterType == FilterConfiguration.FilterType.ABI
                    }?.identifier ?: "universal"
                    val versionName = artifact.versionName
                    val versionCode = artifact.versionCode
                    val buildType = variant.buildType

                    val originalApk = File(artifact.outputFile)
                    val targetFile = File(
                        targetDir,
                        "monogram-$abi-${versionName}(${versionCode})-${buildType}.apk"
                    )

                    originalApk.copyTo(targetFile, overwrite = true)
                }
            }
        }

        project.tasks.matching { it.name == "assemble${variant.name.capitalize()}" }.configureEach {
            finalizedBy(renameTask)
        }
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
        val releaseTaskProvider = project.tasks.named<DependencyTask>("releaseOssDependencyTask")

        dependsOn(releaseTaskProvider)

        doLast {
            val releaseJson = releaseTaskProvider.get().dependenciesJson.get().asFile
            val debugJson = dependenciesJson.get().asFile
            if (releaseJson.exists()) releaseJson.copyTo(debugJson, overwrite = true)
        }
    }
}

googleServices {
    missingGoogleServicesStrategy = GoogleServicesPlugin.MissingGoogleServicesStrategy.WARN
}