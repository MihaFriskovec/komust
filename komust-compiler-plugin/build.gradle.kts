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

    // kotlin-compile-testing drives an in-process K2 compile of fixture sources;
    // it needs the compiler on the test classpath (pinned to the catalog Kotlin).
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kctfork.core)
}

tasks.withType<Test>().configureEach {
    // kotlin-compile-testing runs the compiler in-process; on JDK 16+ it needs
    // these javac internals opened to it.
    jvmArgs(
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    )
}
