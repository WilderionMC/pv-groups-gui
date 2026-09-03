plugins {
    kotlin("jvm")
    id("fabric-loom") version "1.15.5"
}

val mcVersion = property("minecraft_version").toString()
val javaVer = property("java_version").toString().toInt()
val loaderVer = property("loader_version").toString()
val fabricApi = property("fabric_version").toString()
val owoVer = property("owo_version").toString()

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVer")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")

    implementation(project(":client"))
    include(project(":client"))
    implementation(project(":protocol"))
    include(project(":protocol"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${rootProject.property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-stdlib:${rootProject.property("kotlin_version")}")

    compileOnly("su.plo.voice.api:client:${rootProject.property("pv_version")}")
    compileOnly("su.plo.voice:protocol:${rootProject.property("pv_version")}")

    modImplementation("maven.modrinth:owo-lib:$owoVer") { isTransitive = false }
    include("maven.modrinth:owo-lib:$owoVer")
}

tasks.processResources {
    val modVersion = project.version.toString()
    val pvVersion = rootProject.property("pv_version").toString()
    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to modVersion,
                "pv_version" to pvVersion,
                "minecraft_version" to mcVersion,
                "java_version" to javaVer.toString(),
            ),
        )
    }
}

tasks.jar {
    archiveBaseName.set("pv-addon-groups-gui-fabric-$mcVersion")
}

tasks.remapJar {
    archiveBaseName.set("pv-addon-groups-gui-fabric-$mcVersion")
}

// CLI `:jar` should produce the remapped distributable (Loom default puts it on remapJar).
tasks.named("jar") {
    finalizedBy(tasks.remapJar)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVer))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-jvm-default=enable")
    }
}
