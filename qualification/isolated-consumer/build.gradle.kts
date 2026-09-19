plugins {
    kotlin("jvm") version "2.4.0"
    alias(libs.plugins.komust)
}

val candidateVersion = providers.gradleProperty("komustCandidateVersion").get()
val candidateGroup = providers.gradleProperty("komustCandidateGroup").get()
val candidatePluginId = providers.gradleProperty("komustCandidatePluginId").get()
val schemaDirectory = providers.gradleProperty("komustSchemaDirectory").map(::file)

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val reportVerification = sourceSets.create("reportVerification")

dependencies {
    "reportVerificationImplementation"(platform("org.junit:junit-bom:6.1.3"))
    "reportVerificationImplementation"("org.junit.jupiter:junit-jupiter")
    "reportVerificationImplementation"("com.networknt:json-schema-validator:1.5.6")
    "reportVerificationRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }

val candidateArtifacts = configurations.create("candidateArtifacts") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val expectedCandidateCoordinates = setOf(
    candidatePluginId to "$candidatePluginId.gradle.plugin",
    candidateGroup to "komust-gradle-plugin",
    candidateGroup to "komust-compiler-plugin",
    candidateGroup to "komust-engine",
    candidateGroup to "komust-scope",
)

dependencies {
    expectedCandidateCoordinates.forEach { (group, artifact) ->
        candidateArtifacts("$group:$artifact:$candidateVersion")
    }
}

tasks.register("verifyCandidateResolution") {
    group = "verification"
    description = "Proves all five staged candidate coordinates and the automatic compile-only annotation artifact resolve."
    inputs.files(candidateArtifacts, configurations.named("compileClasspath"))
    doLast {
        val resolved = candidateArtifacts.incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { it.version == candidateVersion }
            .map { it.group to it.name }
            .toSet()
        check(resolved.containsAll(expectedCandidateCoordinates)) {
            "Staged candidate graph was incomplete: expected $expectedCandidateCoordinates, resolved $resolved"
        }

        val compilerPlugin = configurations.getByName("compileClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .any {
                it.group == candidateGroup &&
                    it.name == "komust-compiler-plugin" &&
                    it.version == candidateVersion
            }
        check(compilerPlugin) {
            "Applying io.komust did not supply $candidateGroup:komust-compiler-plugin:$candidateVersion to compileClasspath"
        }
    }
}

tasks.register<Test>("verifyKomustReports") {
    group = "verification"
    description = "Validates the isolated consumer's reports against the public schemas and reconciles their contents."
    dependsOn("mutationTest")
    testClassesDirs = reportVerification.output.classesDirs
    classpath = reportVerification.runtimeClasspath
    useJUnitPlatform()
    systemProperty("komust.candidateVersion", candidateVersion)
    systemProperty("komust.schemaDirectory", schemaDirectory.get().absolutePath)
    systemProperty("komust.reportDirectory", layout.buildDirectory.dir("komust").get().asFile.absolutePath)
}
