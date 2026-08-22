plugins {
    id("fabric-loom")
    id("org.jetbrains.kotlin.jvm")
}

val mcVersion: String = stonecutter.current.version
val loaderVersion: String = property("deps.fabric_loader") as String
val fabricApiVersion: String = property("deps.fabric_api") as String
val flkVersion: String = property("deps.flk") as String
val loaderMin: String = property("deps.fabric_loader_min") as String
val flkMin: String = property("deps.flk_min") as String
val mcCompat: String = property("mod.mc_compat") as String

val is26x: Boolean = mcVersion.substringBefore('.').toInt() >= 26
val javaVer: Int = if (is26x) 25 else 21

base {
    archivesName.set("MC-Inspector-mc$mcVersion")
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    mavenLocal() // panel-lib (../panel-lib: ./gradlew :fabric:<ver>:publishToMavenLocal)
}

dependencies {
    // MC 26.x is mapping-less in Loom 1.16: plain implementation/runtimeOnly.
    val modImpl = if (is26x) "implementation" else "modImplementation"

    minecraft("com.mojang:minecraft:$mcVersion")
    if (!is26x) {
        add("mappings", loom.officialMojangMappings())
    }
    add(modImpl, "net.fabricmc:fabric-loader:$loaderVersion")
    add(modImpl, "net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    add(modImpl, "net.fabricmc:fabric-language-kotlin:$flkVersion")

    // Shared ImGui overlay. Bundled so users only install MC-Inspector.
    val panelLibVersion: String = property("panellib_version") as String
    add(modImpl, "dev.harrison:panel-lib-mc$mcVersion:$panelLibVersion")
    include("dev.harrison:panel-lib-mc$mcVersion:$panelLibVersion")

    // JavaScript engine for the exec_script tool (pure Java, interpreted mode).
    include(implementation("org.mozilla:rhino:1.7.15")!!)

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
    splitEnvironmentSourceSets()
    mods {
        register("mcinspector") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
    runs {
        named("client") {
            client()
            configName = "Fabric Client ($mcVersion)"
            ideConfigGenerated(stonecutter.current.isActive)
            runDir("../../run")
        }
        named("server") {
            server()
            configName = "Fabric Server ($mcVersion)"
            ideConfigGenerated(stonecutter.current.isActive)
            runDir("../../run")
        }
    }
}

tasks.withType<ProcessResources>().configureEach {
    inputs.property("version", project.version)
    inputs.property("minecraft_compat", mcCompat)
    inputs.property("loader_min", loaderMin)
    inputs.property("flk_min", flkMin)
    inputs.property("java_version", javaVer)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_compat" to mcCompat,
            "loader_min" to loaderMin,
            "flk_min" to flkMin,
            "java_version" to javaVer.toString()
        )
    }
    filesMatching("*.mixins.json") {
        expand("mixin_java" to "JAVA_$javaVer")
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.toVersion(javaVer)
    targetCompatibility = JavaVersion.toVersion(javaVer)
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVer)
    }
}

kotlin {
    jvmToolchain(javaVer)
}

tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(tasks.named(if ("remapJar" in tasks.names) "remapJar" else "jar"))
    into(rootProject.layout.buildDirectory.dir("libs/${project.version}"))
    dependsOn("build")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("mcinspector.docsDir", rootProject.file("docs").absolutePath)
}

// Regenerate docs/mcp-tools.md from the tool registry (ToolDocsParityTest enforces it stays in sync).
tasks.register<JavaExec>("generateToolDocs") {
    group = "documentation"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.harrison.mcinspector.docs.ToolDocsGeneratorKt")
    args(rootProject.file("docs").absolutePath)
}
