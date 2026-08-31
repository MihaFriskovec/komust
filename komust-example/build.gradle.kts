plugins {
    // Pinned to the same Kotlin the komust compiler plugin is built against
    // (gradle/libs.versions.toml) — the K2 compiler-plugin ABI is version-specific.
    kotlin("jvm") version "2.2.0"

    // Apply by id with NO version: the composite build in settings.gradle.kts
    // substitutes it from source, so a version string would be meaningless and
    // could only rot (decision: issue #55).
    id("io.komust")
}

dependencies {
    // `@SuppressMutations` lives in `io.komust.runtime`, shipped inside
    // `komust-compiler-plugin`. The plugin puts that artifact on the dedicated
    // *mutation* compile classpath automatically, but the ordinary `main` compile
    // needs it too to resolve the annotation import in PriceCalculator.checkout.
    // It is BINARY-retained, so `compileOnly` is enough. The version is a
    // formality: a dependency coordinate must carry one, but the composite build
    // substitutes this module from source by group:name and ignores the requested
    // version — so it resolves to current source and cannot rot on a version bump.
    compileOnly("io.komust:komust-compiler-plugin:0.1.0-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
