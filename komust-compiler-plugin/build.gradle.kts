plugins {
    id("komust.kotlin-module")
}

kotlin {
    compilerOptions {
        // The unstable K2 surface this module rides on. Every symbol reached
        // through these opt-ins can change shape per Kotlin release — which is
        // why the Kotlin version is exact-pinned (gradle/libs.versions.toml)
        // and every touch of it is funnelled through
        // io.komust.compiler.ir.KotlinIrCompat.
        optIn.addAll(
            "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
            "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
        )
    }
}

dependencies {
    // Provided by the Kotlin compiler at plugin-load time; never shipped.
    compileOnly(libs.kotlin.compiler.embeddable)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlin.compiler.embeddable)
}
