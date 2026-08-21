pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.5"
}

rootProject.name = "MC-Inspector"

// Multi-version Fabric mod via Stonecutter: one subproject per Minecraft version.
// Per-version dependency pins live in fabric/versions/<version>/gradle.properties.
stonecutter {
    create(":fabric") {
        versions("1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.2")
        vcsVersion = "1.21.11"
    }
}
