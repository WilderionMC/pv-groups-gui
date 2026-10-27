import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.security.MessageDigest

plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

val clientBundle = configurations.create("clientBundle")

dependencies {
    clientBundle(project(":client")) { isTransitive = false }
    clientBundle(project(":protocol")) { isTransitive = false }
    clientBundle("org.jetbrains.kotlin:kotlin-stdlib:${rootProject.property("kotlin_version")}") { isTransitive = false }
    minecraft("com.mojang:minecraft:26.2")
    compileOnly("maven.modrinth:plasmo-voice:fabric-26.2-2.1.17") { isTransitive = false }
    implementation(project(":client"))
    compileOnly("net.fabricmc:fabric-loader:0.19.5")
    compileOnly("net.minecraftforge:forge:1.16.5-36.2.42:universal") { isTransitive = false }
    compileOnly("net.minecraftforge:mergetool:1.0.9:api") { isTransitive = false }
    compileOnly("net.minecraftforge:eventbus:4.0.0") { isTransitive = false }
    compileOnly("net.neoforged.fancymodloader:loader:4.0.42") { isTransitive = false }
    compileOnly("net.neoforged:mergetool:2.0.0:api") { isTransitive = false }
    compileOnly("net.neoforged:bus:8.0.2") { isTransitive = false }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// Loader entrypoints are compiled with JDK 25 and removed from other loaders' JARs.
// The shared UI itself stays Java 8 and never calls Minecraft classes directly.
configurations.compileClasspath {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

@Suppress("UNCHECKED_CAST")
val targets = JsonSlurper().parse(rootProject.file("client-targets.json")) as List<Map<String, Any>>
val clientJars = targets.map { target ->
    val targetName = target.getValue("name").toString()
    val loader = target.getValue("loader").toString()
    @Suppress("UNCHECKED_CAST")
    val versions = target.getValue("minecraft") as List<String>
    val javaVersion = when {
        versions.first().startsWith("26.") -> 25
        versions.first().startsWith("1.21") -> 21
        versions.first().startsWith("1.16") -> 8
        versions.first().startsWith("1.17") -> 16
        else -> 17
    }
    val generated = layout.buildDirectory.dir("generated/targets/$targetName")
    val metadata = tasks.register("metadata-$targetName") {
        inputs.file(rootProject.file("client-targets.json"))
        inputs.property("version", project.version.toString())
        outputs.dir(generated)
        doLast {
            val output = generated.get().asFile.apply { mkdirs() }
            output.resolve("groups-gui-target.properties").writeText("openKey=${target.getValue("openKey")}\n")
            if (loader == "fabric") {
                val json = mapOf(
                    "schemaVersion" to 1,
                    "id" to "pv-addon-groups-gui",
                    "version" to project.version.toString(),
                    "name" to "PV Groups GUI",
                    "description" to "Graphical interface for pv-addon-groups",
                    "authors" to listOf("PV Groups GUI contributors"),
                    "license" to "MIT",
                    "icon" to "assets/pv-addon-groups-gui/icon.png",
                    "environment" to "client",
                    "entrypoints" to mapOf("client" to listOf("su.plo.voice.groups.gui.ui.FabricGroupsGuiMod")),
                    "depends" to mapOf(
                        "fabricloader" to "*", "minecraft" to versions,
                        "java" to ">=$javaVersion", "plasmovoice" to ">=2.1.17 <2.2",
                    ),
                )
                output.resolve("fabric.mod.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(json)))
            } else {
                val neo = loader == "neoforge"
                val iconKey = if (versions.first() == "26.3") "iconFile" else "logoFile"
                val dependencyType = if (neo) "type=\"required\"" else "mandatory=true"
                val minecraftRange = if (versions.size == 1) "[${versions.first()}]" else "[${versions.first()},${versions.last()}]"
                val toml = """
                    modLoader="javafml"
                    loaderVersion="[${if (neo) 4 else 36},)"
                    license="MIT"
                    [[mods]]
                    modId="pv_groups_gui"
                    version="${project.version}"
                    displayName="PV Groups GUI"
                    authors="PV Groups GUI contributors"
                    description="Graphical interface for pv-addon-groups"
                    $iconKey="assets/pv-addon-groups-gui/icon.png"
                    displayTest="IGNORE_SERVER_VERSION"
                    [[dependencies.pv_groups_gui]]
                    modId="plasmovoice"
                    $dependencyType
                    versionRange="[2.1.17,2.2)"
                    ordering="AFTER"
                    side="CLIENT"
                    [[dependencies.pv_groups_gui]]
                    modId="minecraft"
                    $dependencyType
                    versionRange="$minecraftRange"
                    ordering="NONE"
                    side="CLIENT"
                """.trimIndent()
                output.resolve("META-INF").mkdirs()
                output.resolve("META-INF/${if (neo) "neoforge.mods.toml" else "mods.toml"}").writeText(toml)
            }
        }
    }
    tasks.register<Jar>("jar-$targetName") {
        dependsOn(metadata, tasks.classes)
        archiveBaseName.set("pv-addon-groups-gui-$targetName")
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("artifacts/plasmo-ui"))
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(sourceSets.main.get().output)
        from(metadata)
        from({ clientBundle.map { zipTree(it) } })
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**")
        if (loader != "fabric") exclude("**/FabricGroupsGuiMod*")
        if (loader != "forge") exclude("**/ForgeGroupsGuiMod*")
        if (loader != "neoforge") exclude("**/NeoForgeGroupsGuiMod*")
    }
}

tasks.register("buildAllClients") { dependsOn(clientJars) }
tasks.build { dependsOn("buildAllClients") }

val pinnedPlasmoClients = targets.map { target ->
    val path = rootProject.layout.buildDirectory.file("upstream/${target.getValue("plasmoVersion")}.jar")
    val download = tasks.register("downloadPlasmo-${target.getValue("name")}") {
        val url = target.getValue("plasmoUrl").toString()
        val expected = target.getValue("plasmoSha512").toString()
        inputs.property("url", url)
        inputs.property("sha512", expected)
        outputs.file(path)
        doLast {
            val file = path.get().asFile
            fun checksum() = MessageDigest.getInstance("SHA-512").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            if (file.exists() && checksum() == expected) return@doLast
            file.parentFile.mkdirs()
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 120_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "PV-Groups-GUI compatibility-check")
            }
            connection.getInputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            check(checksum() == expected) { "Plasmo checksum mismatch: $file" }
        }
    }
    download to path
}

val verifyClientCompatibility = tasks.register<Exec>("verifyClientCompatibility") {
    dependsOn("buildAllClients", pinnedPlasmoClients.map { it.first })
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    workingDir(rootProject.projectDir)
    doFirst {
        commandLine(listOf(
            launcher.get().executablePath.asFile.absolutePath,
            rootProject.file("tools/VerifyClientCompatibility.java").absolutePath,
            rootProject.layout.buildDirectory.dir("artifacts/plasmo-ui").get().asFile.absolutePath,
            project.version.toString(),
        ) + pinnedPlasmoClients.map { it.second.get().asFile.absolutePath })
    }
}
tasks.check { dependsOn(verifyClientCompatibility) }

val verifyHotkey = tasks.register<Exec>("verifyHotkey") {
    dependsOn(tasks.classes, "jar-fabric-26.2")
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    workingDir(rootProject.projectDir)
    doFirst {
        val classpath = files(
            sourceSets.main.get().output,
            configurations.compileClasspath,
            configurations.runtimeClasspath,
            layout.buildDirectory.dir("generated/targets/fabric-26.2"),
        ).asPath
        commandLine(launcher.get().executablePath.asFile.absolutePath, "-cp", classpath,
            rootProject.file("tools/VerifyHotkey.java").absolutePath)
    }
}
tasks.check { dependsOn(verifyHotkey) }

val verifyCompactLayout = tasks.register<Exec>("verifyCompactLayout") {
    dependsOn(tasks.classes)
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    workingDir(rootProject.projectDir)
    doFirst {
        commandLine(launcher.get().executablePath.asFile.absolutePath, "-cp",
            files(sourceSets.main.get().output, configurations.compileClasspath, configurations.runtimeClasspath).asPath,
            rootProject.file("tools/VerifyCompactLayout.java").absolutePath)
    }
}
tasks.check { dependsOn(verifyCompactLayout) }

val uiTest = sourceSets.create("uiTest") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
    runtimeClasspath += output + compileClasspath
}

// Real Minecraft/Plasmo UI tests, isolated from distributable JARs and local profiles.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "pv-groups-gui-tests"
        enableGameTests = false
        enableClientGameTests = true
    }
}

dependencies {
    "gametestImplementation"(uiTest.output)
    "gametestImplementation"("net.fabricmc.fabric-api:fabric-api:${rootProject.property("fabric_version")}")
    "gametestImplementation"("maven.modrinth:plasmo-voice:fabric-26.2-2.1.17") { isTransitive = false }
    "gametestRuntimeOnly"("net.fabricmc:fabric-loader:0.19.5")
}

tasks.register<Jar>("matrixTestAgent") {
    dependsOn(tasks.named("uiTestClasses"))
    archiveFileName.set(providers.gradleProperty("matrixAgentName").orElse("ui-matrix-agent.jar"))
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("ui-matrix"))
    from(uiTest.output)
    manifest.attributes("Premain-Class" to "su.plo.voice.groups.gui.ui.MatrixTestAgent")
}

tasks.named<JavaCompile>("compileGametestJava") { options.release.set(25) }

tasks.named<ProcessResources>("processGametestResources") {
    from(layout.projectDirectory.dir("src/main/resources/assets")) {
        into("assets")
    }
}
