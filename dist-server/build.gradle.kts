plugins {
    kotlin("jvm")
    id("su.plo.voice.plugin.entrypoints") version "1.0.9"
}

dependencies {
    compileOnly(project(":protocol"))
    compileOnly(project(":bridge"))
    compileOnly(project(":paper"))
    compileOnly(project(":velocity"))

    compileOnly("su.plo.voice.api:server-proxy-common:${property("pv_version")}")
    compileOnly("su.plo.voice.api:server:${property("pv_version")}")
    compileOnly("su.plo.voice.api:proxy:${property("pv_version")}")
    compileOnly("su.plo.voice:protocol:${property("pv_version")}")
    compileOnly("maven.modrinth:pv-addon-groups:${property("groups_version")}")
    compileOnly("com.google.guava:guava:32.1.3-jre")
}

val platforms = listOf(
    project(":protocol"),
    project(":bridge"),
    project(":paper"),
    project(":velocity"),
)

platforms.forEach { evaluationDependsOn(it.path) }

sourceSets {
    main {
        java {
            srcDir(platforms.map { it.file("src/main/kotlin") })
        }
        resources {
            srcDir(platforms.map { it.file("src/main/resources") }.filter { it.exists() })
        }
    }
}

tasks {
    jar {
        archiveClassifier.set("dev")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    shadowJar {
        configurations = listOf(project.configurations.shadow.get())
        archiveBaseName.set("pv-addon-groups-gui")
        archiveClassifier.set("")
        archiveAppendix.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }
}
