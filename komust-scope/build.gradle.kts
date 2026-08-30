plugins {
    id("komust.kotlin-module")
    // Version comes from the build classpath (buildSrc pins it to the catalog
    // Kotlin version); scope.json is (de)serialised with kotlinx.serialization.
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
