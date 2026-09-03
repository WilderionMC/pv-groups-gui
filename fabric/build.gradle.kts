plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

    implementation(project(":client"))
    include(project(":client"))
    implementation(project(":protocol"))
    include(project(":protocol"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib:${property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-stdlib:${property("kotlin_version")}")

    compileOnly("su.plo.voice.api:client:${property("pv_version")}")
    compileOnly("su.plo.voice:protocol:${property("pv_version")}")

    // Modrinth published jar already nests endec/kdl — skip broken Maven transitives
    implementation("maven.modrinth:owo-lib:${property("owo_version")}") {
        isTransitive = false
    }
    include("maven.modrinth:owo-lib:${property("owo_version")}")
}

tasks.processResources {
    val modVersion = project.version.toString()
    val pvVersion = project.property("pv_version").toString()
    val mcVersion = project.property("minecraft_version").toString()
    val javaVersion = project.property("java_version").toString()
    inputs.property("version", modVersion)
    inputs.property("pv_version", pvVersion)
    inputs.property("minecraft_version", mcVersion)
    inputs.property("java_version", javaVersion)
    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to modVersion,
                "pv_version" to pvVersion,
                "minecraft_version" to mcVersion,
                "java_version" to javaVersion,
            )
        )
    }
}

tasks.jar {
    archiveBaseName.set("pv-addon-groups-gui-fabric-26.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}
