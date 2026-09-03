plugins {
    kotlin("jvm")
    kotlin("plugin.lombok")
}

dependencies {
    api(project(":protocol"))

    compileOnly("su.plo.voice.api:server-proxy-common:${property("pv_version")}")
    compileOnly("su.plo.voice.api:server:${property("pv_version")}")
    compileOnly("su.plo.voice.api:proxy:${property("pv_version")}")
    compileOnly("su.plo.voice:protocol:${property("pv_version")}")
    compileOnly("com.google.guava:guava:32.1.3-jre")
    compileOnly("maven.modrinth:pv-addon-groups:${property("groups_version")}")
    testImplementation(kotlin("test"))
}

java {
}
repositories {
    mavenCentral()
}