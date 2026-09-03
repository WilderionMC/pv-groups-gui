plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly("su.plo.voice:protocol:${property("pv_version")}")
    compileOnly("com.google.guava:guava:32.1.3-jre")
}

java {
    withSourcesJar()
}
