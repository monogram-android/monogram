pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "monogram"

include(":app")

include(":core:common")
include(":core:models")
include(":core:ui")
include(":core:database")
include(":core:markup")

include(":feature:auth")
include(":feature:chats")
include(":feature:dialog")
include(":feature:folders")
include(":feature:profile")
include(":feature:settings")

include(":network:http")
include(":network:bridge")

include(":native:mtproto")
include(":native:markup")
