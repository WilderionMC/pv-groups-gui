plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

val mcVersion = property("minecraft_version").toString()
val javaVer = property("java_version").toString()
val loaderVer = property("loader_version").toString()
val fabricApi = property("fabric_version").toString()
val owoVer = property("owo_version").toString()

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVer")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")

    implementation(project(":client"))
    include(project(":client"))
    implementation(project(":protocol"))
    include(project(":protocol"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${rootProject.property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-stdlib:${rootProject.property("kotlin_version")}")

    compileOnly("su.plo.voice.api:client:${rootProject.property("pv_version")}")
    compileOnly("su.plo.voice:protocol:${rootProject.property("pv_version")}")

    implementation("maven.modrinth:owo-lib:$owoVer") { isTransitive = false }
    include("maven.modrinth:owo-lib:$owoVer")
}

tasks.processResources {
    val modVersion = project.version.toString()
    val pvVersion = rootProject.property("pv_version").toString()
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to modVersion,
                "pv_version" to pvVersion,
                "minecraft_version" to mcVersion,
                "java_version" to javaVer,
            ),
        )
    }
}

tasks.jar {
    archiveBaseName.set("pv-addon-groups-gui-fabric-26.1.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVer.toInt()))
    withSourcesJar()
}
