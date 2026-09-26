import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

plugins {
    alias(libs.plugins.android.library)
}

val targetAbiProp = providers.gradleProperty("targetAbi").orNull
val selectedAbis = if (!targetAbiProp.isNullOrBlank()) {
    listOf(targetAbiProp)
} else {
    listOf("armeabi-v7a", "arm64-v8a", "x86_64")
}

android {
    namespace = "org.monogram.markup"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += selectedAbis
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    api("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}

val rustCrate = rootProject.layout.projectDirectory.dir("native/markup-rs")
val jniLibs = layout.projectDirectory.dir("src/main/jniLibs")
val skipNativeBuild =
    providers.gradleProperty("skipNativeBuild").map { it.toBoolean() }.orElse(false)

fun sdkHome(): File {
    System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { return file(it) }
    val local = rootProject.file("local.properties")
    if (local.isFile) {
        val props = Properties()
        local.reader().use { props.load(it) }
        val sdk = props.getProperty("sdk.dir")
        if (!sdk.isNullOrBlank()) {
            return file(sdk)
        }
    }
    error("Set sdk.dir in local.properties or ANDROID_SDK_ROOT.")
}

fun ndkHome(): File {
    System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let { return file(it) }
    val ndkRoot = sdkHome().resolve("ndk")
    return ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        ?: error("No Android NDK under ${ndkRoot.absolutePath}. Set ANDROID_NDK_HOME.")
}

val ndkPath = ndkHome().absolutePath
val sdkPath = sdkHome().absolutePath
val cargoHome =
    System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}${File.separator}.cargo"
val cargoPath = "${File(cargoHome, "bin").absolutePath}${File.pathSeparator}${System.getenv("PATH").orEmpty()}"
val windows =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

fun cargoCommand(args: List<String>): List<String> =
    if (windows) listOf("cmd", "/c") + args else args

fun hostCdylibName(libName: String): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("windows") -> "$libName.dll"
        os.contains("mac") -> "lib$libName.dylib"
        else -> "lib$libName.so"
    }
}

val hostUniffiLib = rustCrate.file("target/debug/${hostCdylibName("monogram_markup")}")
val uniffiKotlin = layout.projectDirectory.file(
    "src/main/java/uniffi/monogram_markup/monogram_markup.kt",
)

val buildNativeMarkup =
    tasks.register("buildNativeMarkup") {
        group = "build"
        description =
            "cargo ndk ${selectedAbis.joinToString(" + ")} in parallel into src/main/jniLibs (skip with -PskipNativeBuild=true)"
        val crateDir = rustCrate.asFile
        val outDir = jniLibs.asFile
        val ndk = ndkPath
        val sdk = sdkPath
        val pathEnv = cargoPath
        val abis = selectedAbis
        val win = windows
        val jobs = maxOf(2, Runtime.getRuntime().availableProcessors() / maxOf(1, abis.size))
        inputs.files(
            rustCrate.file("Cargo.toml"),
            rustCrate.file("Cargo.lock"),
            rustCrate.file("build.rs"),
        )
        inputs.dir(rustCrate.dir("src"))
        inputs.property("abis", abis)
        outputs.files(
            abis.map { jniLibs.file("$it/libmonogram_markup.so") },
        )
        enabled = !skipNativeBuild.get()
        doLast {
            val pool = Executors.newFixedThreadPool(abis.size)
            val errors = ConcurrentLinkedQueue<String>()
            val latch = CountDownLatch(abis.size)
            abis.forEach { abi ->
                pool.execute {
                    try {
                        val args = mutableListOf("cargo", "ndk", "-t", abi, "-o", outDir.absolutePath, "build", "--release")
                        val cmd = if (win) listOf("cmd", "/c") + args else args
                        val pb = ProcessBuilder(cmd)
                        pb.directory(crateDir)
                        pb.redirectErrorStream(true)
                        pb.environment()["ANDROID_NDK_HOME"] = ndk
                        pb.environment()["ANDROID_SDK_ROOT"] = sdk
                        pb.environment()["PATH"] = pathEnv
                        pb.environment()["CARGO_BUILD_JOBS"] = jobs.toString()
                        val proc = pb.start()
                        proc.inputStream.bufferedReader().forEachLine { line ->
                            logger.lifecycle("[$abi] $line")
                        }
                        val code = proc.waitFor()
                        if (code != 0) errors.add("$abi failed with $code")
                    } catch (e: Exception) {
                        errors.add("$abi: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            pool.shutdown()
            if (errors.isNotEmpty()) {
                throw GradleException(errors.joinToString("\n"))
            }
        }
    }

val buildHostUniffiMarkup =
    tasks.register<Exec>("buildHostUniffiMarkup") {
        group = "build"
        description =
            "Host debug cdylib for UniFFI (release Android .so is stripped)"
        workingDir = rustCrate.asFile
        environment("PATH", cargoPath)
        commandLine(cargoCommand(listOf("cargo", "build", "--lib", "--features", "bindgen-cli")))
        inputs.files(
            rustCrate.file("Cargo.toml"),
            rustCrate.file("Cargo.lock"),
            rustCrate.file("build.rs"),
        )
        inputs.dir(rustCrate.dir("src"))
        outputs.file(hostUniffiLib)
        enabled = !skipNativeBuild.get()
    }

val generateUniffiMarkup =
    tasks.register<Exec>("generateUniffiMarkup") {
        group = "build"
        description =
            "Regenerate UniFFI Kotlin bindings from the host debug library (skip with -PskipNativeBuild=true)"
        dependsOn(buildHostUniffiMarkup)
        workingDir = rustCrate.asFile
        environment("PATH", cargoPath)
        commandLine(
            cargoCommand(
                listOf(
                    "cargo", "run", "--features", "bindgen-cli", "--bin", "uniffi-bindgen", "--",
                    "generate",
                    "--library", hostUniffiLib.asFile.absolutePath,
                    "--language", "kotlin",
                    "--out-dir", layout.projectDirectory.dir("src/main/java").asFile.absolutePath,
                    "--no-format",
                ),
            ),
        )
        inputs.files(
            rustCrate.file("Cargo.toml"),
            rustCrate.file("Cargo.lock"),
            rustCrate.file("build.rs"),
            rustCrate.file("uniffi-bindgen.rs"),
            hostUniffiLib,
        )
        inputs.dir(rustCrate.dir("src"))
        outputs.file(uniffiKotlin)
        enabled = !skipNativeBuild.get()
    }

buildNativeMarkup.configure {
    finalizedBy(generateUniffiMarkup)
}

tasks.named("preBuild").configure {
    dependsOn(buildNativeMarkup, generateUniffiMarkup)
}
