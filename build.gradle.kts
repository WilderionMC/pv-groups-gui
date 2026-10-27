import java.security.MessageDigest
import groovy.json.JsonSlurper

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
        maven("https://jitpack.io")
        maven("https://api.modrinth.com/maven")
        maven("https://maven.minecraftforge.net") {
            content { includeGroup("net.minecraftforge") }
        }
        maven("https://maven.neoforged.net/releases") {
            content { includeGroupByRegex("net\\.neoforged(\\..*)?") }
        }
    }
}

subprojects {
    // Shared UI uses Plasmo's version-independent classes, so all output stays Java 8.
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(8)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
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

if (findProject(":client-ui") != null) {
    val collectServerArtifact = tasks.register<Copy>("collectServerArtifact") {
        dependsOn(":dist-server:shadowJar")
        from(layout.projectDirectory.file("dist-server/build/libs/pv-addon-groups-gui-$version.jar"))
        into(layout.buildDirectory.dir("artifacts/plasmo-ui"))
    }
    tasks.register("assembleArtifacts") {
        dependsOn(":client-ui:check", collectServerArtifact)
        val artifacts = layout.buildDirectory.dir("artifacts/plasmo-ui")
        inputs.files(fileTree(artifacts) { include("*.jar") })
        outputs.file(artifacts.map { it.file("SHA256SUMS.txt") })
        // A local installation can be added between builds, so rescan when local sync is configured.
        outputs.upToDateWhen { !file(".local-test.json").exists() }
        doLast {
            val directory = artifacts.get().asFile
            val hashes = directory.listFiles().orEmpty().filter { it.extension == "jar" }.sortedBy { it.name }.map { jar ->
                val hash = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
                "$hash  ${jar.name}"
            }
            directory.resolve("SHA256SUMS.txt").writeText(hashes.joinToString("\n", postfix = "\n"))
            val localConfig = file(".local-test.json")
            if (localConfig.exists() && providers.gradleProperty("syncLocalTest").orNull != "false") {
                val config = JsonSlurper().parse(localConfig) as Map<*, *>
                val installation = providers.exec {
                    commandLine(requireNotNull(config["python"]).toString(), "-X", "utf8", "tools/sync_local_test.py")
                    workingDir(rootProject.projectDir)
                    isIgnoreExitValue = true
                }
                println(installation.standardOutput.asText.get())
                val errors = installation.standardError.asText.get()
                if (errors.isNotBlank()) logger.error(errors)
                installation.result.get().assertNormalExitValue()
            }
        }
    }
}
