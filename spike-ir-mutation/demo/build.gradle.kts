plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":runtime"))
    // Load our compiler plugin into demo's Kotlin compilation.
    kotlinCompilerPluginClasspath(project(":plugin"))

    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
