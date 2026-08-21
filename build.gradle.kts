plugins {
    // Kotlin >= 2.3.0 is required for the MC 26.x targets (Java 25 class files).
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
    // Loom 1.16 handles the 26.x mojmap-only scheme.
    id("fabric-loom") version "1.16-SNAPSHOT" apply false
}

val versionMajor: String by project
val versionMinor: String by project
val versionPatch: String by project

allprojects {
    group = "dev.harrison"
    version = "$versionMajor.$versionMinor.$versionPatch"
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "fabric" }
    }
}
