import com.android.build.gradle.internal.tasks.factory.dependsOn
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import java.net.URL

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

android {
    namespace = "org.monogram.presentation"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a, armeabi-v7a, x86_64")
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
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

val downloadFolder = "src/main/cpp"
var vpxFolder = file("$downloadFolder/libvpx_build")
val vpxZip = file("$downloadFolder/libvpx_build.zip")

tasks.apply {
    register("downloadVpx") {
        onlyIf { !vpxFolder.isDirectory }
        doLast {
                println("Downloading VPX libs...")
                vpxFolder.ensureParentDirsCreated()
                URL("https://github.com/aliveoutside/prebuilt-vpx/releases/download/v.1/libvpx_build.zip")
                    .openStream().use { input ->
                        vpxZip.outputStream()
                            .use { output -> input.copyTo(output) }
                    }
            }
    }
    register<Copy>("unzipVpx") {
        dependsOn(this@apply.getByName("downloadVpx"))
        onlyIf { !vpxFolder.isDirectory }
        from(zipTree(vpxZip))
        into(downloadFolder)
    }
    register<Delete>("downloadAndUnzipVpx") {
        dependsOn("unzipVpx")
        delete(vpxZip)
    }
    preBuild.dependsOn("downloadAndUnzipVpx")
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
    implementation(libs.play.services.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.maplibre.compose)
    implementation(libs.play.services.oss.licenses)
    implementation(libs.play.services.location)

    implementation(libs.libphonenumber)

    testImplementation(libs.junit)
}