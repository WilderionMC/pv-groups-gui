pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://jitpack.io")
        maven("https://repo.plasmoverse.com/releases")
        maven("https://repo.plasmoverse.com/snapshots")
    }
    plugins {
        kotlin("plugin.lombok") version "2.4.10"
        kotlin("kapt") version "2.4.10"
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("su.plo.voice.plugin")) {
                useModule("su.plo.voice.plugin:pv-gradle-plugin:${requested.version}")
            }
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

rootProject.name = "pv-groups-gui"

include(
    "protocol",
    "bridge",
    "paper",
    "velocity",
    "client",
    "dist-server",
)

// Client loaders are opt-in for CLI/CI so server jars build without Loom (Java 25).
// - IntelliJ: fabric is included automatically (idea.active) for navigation & run configs.
// - CLI: ./gradlew :fabric:jar :fabric-26.1.2:jar :fabric-1.21.11:jar :fabric-1.20.6:jar -PwithClient=true
// - Force off in IDE: -PwithClient=false
if (shouldIncludeFabricClient(settings)) {
    include("fabric")
    include("fabric-26.1.2")
    include("fabric-1.21.11")
    include("fabric-1.20.6")
}

fun shouldIncludeFabricClient(settings: Settings): Boolean {
    val prop = settings.providers.gradleProperty("withClient")
    if (prop.isPresent) {
        return prop.get().equals("true", ignoreCase = true)
    }
    return System.getProperty("idea.active") == "true"
        || System.getProperty("idea.sync.active") == "true"
}
