import java.time.Duration

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.javaToolchain.get().toIntOrNull() ?: 25)

    sourceSets {
        test {
            kotlin.srcDir(rootProject.layout.projectDirectory.dir("mtproto/src/main/java/org/monogram/mtproto/tl/runtime"))
        }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(kotlin("compiler-embeddable"))
}

tasks.test {
    maxHeapSize = "1g"
    maxParallelForks = 1
    forkEvery = 1
    failFast = true
    timeout.set(Duration.ofMinutes(45))
}
