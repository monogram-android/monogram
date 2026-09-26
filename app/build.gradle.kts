import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun credential(name: String): String? =
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv("TELEGRAM_$name")?.takeIf { it.isNotBlank() }

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val telegramApiId: Int = credential("API_ID")?.toIntOrNull() ?: 0
val telegramApiHash: String = credential("API_HASH").orEmpty()
val unsignedBuild =
    providers.gradleProperty("unsigned").orNull.equals("true", ignoreCase = true)
val startupPrewarm = !providers.gradleProperty("startupPrewarm").orNull.equals("false", ignoreCase = true)
val targetAbiProp = providers.gradleProperty("targetAbi").orNull
val selectedAbis = if (!targetAbiProp.isNullOrBlank()) {
    listOf(targetAbiProp)
} else {
    listOf("armeabi-v7a", "arm64-v8a", "x86_64")
}
val appVersionCode = 17
val appVersionName = "0.4.0"

/** Short git SHA of the checked-out commit, shown next to the build type in settings. */
val gitCommit: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "unknown" }

android {
    namespace = "org.monogram"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.monogram"
        minSdk = 24
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", buildConfigString(telegramApiHash))
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("boolean", "STARTUP_PREWARM", startupPrewarm.toString())

        ndk {
            abiFilters += selectedAbis
        }
    }

    if (targetAbiProp.isNullOrBlank()) {
        splits {
            abi {
                isEnable = true
                reset()
                include("armeabi-v7a", "arm64-v8a", "x86_64")
                isUniversalApk = true
            }
        }
    }

    val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE")
    if (!releaseStoreFile.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD").orEmpty()
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS").orEmpty()
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD").orEmpty()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
                ?: if (unsignedBuild) null else signingConfigs.getByName("debug")
            optimization {
                enable = true
                keepRules {
                    includeDefault = true
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("beta") {
            initWith(getByName("release"))
            // Release behavior without R8: Kotlin/Java stays unminified so stack traces
            // and debugger frames keep their real names.
            optimization {
                enable = false
            }
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("androidTest").assets.srcDir("../core/database/schemas")
}

androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType ?: variant.name.substringAfterLast("-")
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType.name == "ABI" }
                ?.identifier
                ?: targetAbiProp?.takeIf { it.isNotBlank() }
                ?: "universal"
            output.outputFileName.set("monogram-$abi-$appVersionName-$buildType.apk")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:common"))
    implementation(project(":core:models"))
    implementation(project(":core:markup"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(project(":core:database"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:chats"))
    implementation(project(":feature:dialog"))
    implementation(project(":feature:folders"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:settings"))
    implementation(project(":network:http"))
    implementation(project(":network:bridge"))
    implementation(project(":native:mtproto"))

    implementation(libs.decompose)
    implementation(libs.decompose.compose)
    implementation(libs.essenty.lifecycle)
    implementation(libs.mvikotlin)
    implementation(libs.mvikotlin.main)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.base)
    implementation(libs.unifiedpush.connector)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.media3.exoplayer)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
