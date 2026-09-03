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
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
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
kotlin {
    jvmToolchain(8)
}