plugins {
    kotlin("jvm") version "2.4.10"
}

allprojects {
    group = "su.plo.voice.groups.gui"
    version = "1.0.0"

    repositories {
        mavenCentral()
        mavenLocal()
        maven("https://libraries.minecraft.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://repo.plasmoverse.com/releases") {
            content {
                includeGroupByRegex("su\\.plo(\\..*)?")
            }
        }
        maven("https://repo.plasmoverse.com/snapshots") {
            content {
                includeGroupByRegex("su\\.plo(\\..*)?")
            }
        }
        maven("https://maven.wispforest.io")
        maven("https://jitpack.io")
        maven("https://api.modrinth.com/maven")
    }
}

subprojects {
    // Fabric client (MC 26.2) needs Java 25; server/bridge stay on 8 for 1.16.5+.
    val clientLoader = name.startsWith("fabric")
    val release = when {
        name == "fabric" || name == "fabric-26.1.2" -> 25
        clientLoader -> 21
        else -> 8
    }
    val target = when (release) {
        25 -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
        21 -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        else -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(release)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(target)
            freeCompilerArgs.add("-jvm-default=enable")
        }
    }
}

repositories {
    mavenCentral()
}
dependencies {
    testImplementation(kotlin("test"))
}
