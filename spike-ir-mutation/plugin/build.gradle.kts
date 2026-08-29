plugins { kotlin("jvm") }
kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
            "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
        )
    }
}

dependencies {
    // Provided by the Kotlin compiler at plugin-load time; never shipped.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.0")
}
