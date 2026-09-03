plugins {
    kotlin("jvm")
    kotlin("plugin.lombok")
}

dependencies {
    api(project(":protocol"))

    compileOnly("su.plo.voice.api:client:${property("pv_version")}")
    compileOnly("su.plo.voice:protocol:${property("pv_version")}")
    compileOnly("com.google.guava:guava:32.1.3-jre")
    testImplementation(kotlin("test"))
}

java {
}
repositories {
    mavenCentral()
}