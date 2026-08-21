plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun Project.resolveBuildInfoProperty(propertyName: String, envName: String): String? {
    return providers.gradleProperty(propertyName).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }
}

fun Project.runCommandAndCaptureOutput(vararg command: String): String? {
    return runCatching {
        providers.exec {
            commandLine(*command)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().takeIf { it.isNotBlank() }
    }.getOrNull()
}

val buildCommitHash =
    resolveBuildInfoProperty("buildCommitHash", "BUILD_COMMIT_HASH")
        ?: runCommandAndCaptureOutput("git", "rev-parse", "--short=7", "HEAD")
        ?: "unknown"

val buildBranch =
    resolveBuildInfoProperty("buildBranch", "BUILD_BRANCH")
        ?: runCommandAndCaptureOutput("git", "branch", "--show-current")?.takeIf { it.isNotBlank() }
        ?: "detached"

val requestsReleaseVariant = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}

val buildTimeMillis =
    resolveBuildInfoProperty("buildTimeMillis", "BUILD_TIME_MILLIS")
        ?.toLongOrNull()
        ?: if (requestsReleaseVariant) {
            System.currentTimeMillis()
        } else {
            runCommandAndCaptureOutput("git", "show", "-s", "--format=%ct", "HEAD")
                ?.toLongOrNull()
                ?.times(1000)
                ?: 0L
        }

android {
    namespace = "org.monogram.presentation"
    compileSdk = 37

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "BUILD_BRANCH", buildBranch.asBuildConfigString())
        buildConfigField("String", "BUILD_COMMIT_HASH", buildCommitHash.asBuildConfigString())
        buildConfigField("long", "BUILD_TIME_MILLIS", "${buildTimeMillis}L")
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    flavorDimensions += listOf("runtime")

    productFlavors {
        create("firebase") {
            dimension = "runtime"
            buildConfigField("boolean", "HAS_OSS_LICENSES", "true")
            buildConfigField("boolean", "IS_LIBRE_RUNTIME", "false")
        }
        create("libre") {
            dimension = "runtime"
            buildConfigField("boolean", "HAS_OSS_LICENSES", "false")
            buildConfigField("boolean", "IS_LIBRE_RUNTIME", "true")
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.androidx.compose)
    implementation(libs.bundles.androidx.camera)
    implementation(libs.bundles.androidx.media3)

    implementation(libs.bundles.coil)
    implementation(libs.bundles.decompose)
    implementation(libs.bundles.mvikotlin)
    implementation(libs.bundles.koin)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.security.crypto)
    implementation(libs.maplibre.compose)
    add("firebaseImplementation", libs.play.services.oss.licenses)
    implementation(libs.unifiedpush.connector)

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.libphonenumber)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
