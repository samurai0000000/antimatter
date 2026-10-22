/*
 * settings.gradle.kts
 *
 * Copyright (C) 2026, Charles Chiou
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Antimatter"
include(":app")
include(":core:data")
include(":core:network")
include(":core:ui")
include(":feature:connect")
include(":feature:chat")
include(":feature:files")
include(":selfupdate")
project(":selfupdate").projectDir = file("../../../../selfupdate/android")
