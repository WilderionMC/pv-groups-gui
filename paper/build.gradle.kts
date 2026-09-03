plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":bridge"))
    compileOnly("su.plo.voice.api:server:${property("pv_version")}")
    compileOnly("su.plo.voice:protocol:${property("pv_version")}")
}
