plugins {
    id("komust.kotlin-module")
    `java-gradle-plugin`
}

dependencies {
    // The thin adapter invokes the scope resolver in-process (ADR-0005 §1) to
    // produce scope.json before the mutation compilation runs.
    implementation(project(":komust-scope"))

    // The Kotlin Gradle plugin — `KotlinCompilerPluginSupportPlugin`,
    // `SubpluginOption`, `KotlinCompilation`, `KotlinCompile`. Provided by the
    // consumer build's own Kotlin plugin at apply time; never bundled
    // (ADR-0005 §3), so `compileOnly`.
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(gradleTestKit())
    // The wiring unit tests reference KGP types (SubpluginArtifact, …) directly.
    testImplementation(libs.kotlin.gradle.plugin)
}

// The compiler-plugin artifact coordinates (`getPluginArtifact`) and the engine
// coordinates the fork resolves both track the plugin's own version — exposed to
// the code through a generated properties resource.
val komustVersionResource: Provider<Directory> = layout.buildDirectory.dir("generated/komust-version")
val generateKomustVersion by tasks.registering {
    val outDir = komustVersionResource
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        outDir.get().file("io/komust/gradle/komust-version.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=$version\n")
        }
    }
}
sourceSets.main {
    resources.srcDir(generateKomustVersion)
}

gradlePlugin {
    plugins {
        create("komust") {
            id = "io.komust"
            implementationClass = "io.komust.gradle.KomustGradlePlugin"
            displayName = "komust"
            description = "Kotlin-native mutation testing — the mutationTest task and komust {} DSL."
        }
    }
}

// The functional (TestKit) smoke test publishes the four komust modules to a
// local file repository the fixture build resolves the compiler plugin + engine
// from; wire the dependency and hand the test the coordinates.
val testMavenRepo: Provider<Directory> = rootProject.layout.buildDirectory.dir("test-maven")
val publishTasks = listOf(
    ":komust-compiler-plugin", ":komust-scope", ":komust-engine", ":komust-gradle-plugin",
).map { "$it:publishAllPublicationsToTestMavenRepository" }

tasks.named<Test>("test") {
    dependsOn(publishTasks)
    // maven-metadata.xml always points at the freshest SNAPSHOT; the smoke test
    // passes --refresh-dependencies so a stale one is never resolved.
    systemProperty("komust.testMavenRepo", testMavenRepo.get().asFile.absolutePath)
    systemProperty("komust.version", project.version.toString())
    systemProperty("komust.kotlinVersion", libs.versions.kotlin.get())
    // TestKit forks a Gradle build that itself forks compiler + engine JVMs — give it room.
    maxHeapSize = "1g"
}
