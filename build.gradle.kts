import io.komust.conventions.GenerateQualificationManifestTask
import io.komust.conventions.VerifyCentralBundleTask
import io.komust.conventions.VerifyReleaseDocumentationTask
import io.komust.conventions.VerifyQualificationManifestTask
import org.gradle.api.GradleException
plugins {
    id("com.gradle.plugin-publish") version "2.2.1" apply false
    id("com.gradleup.nmcp.aggregation") version "1.6.2"
}

// Root build. Per-module configuration — coordinates, publishing, the Kotlin
// toolchain — lives in the `komust.kotlin-module` convention plugin (buildSrc/),
// which reads the atomic public identity / komustVersion from gradle.properties.
dependencies {
    nmcpAggregation(project(":komust-compiler-plugin"))
    nmcpAggregation(project(":komust-engine"))
    nmcpAggregation(project(":komust-scope"))
}

nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("MAVEN_CENTRAL_USERNAME")
        password = providers.environmentVariable("MAVEN_CENTRAL_PASSWORD")
        publishingType = "USER_MANAGED"
        publicationName = providers.gradleProperty("komustVersion").map { "komust:$it" }
        publishAllChecksums.set(true)
    }
}

val verifyCentralBundle = tasks.register<VerifyCentralBundleTask>("verifyCentralBundle") {
    group = "verification"
    description = "Verifies the exact coordinates, signatures, and checksums in the nmcp candidate bundle."
    dependsOn("nmcpZipAggregation")
    bundle.set(layout.buildDirectory.file("nmcp/zip/aggregation.zip"))
    publicKey.set(
        providers.gradleProperty("qualificationSigningPublicKey")
            .map { layout.projectDirectory.file(it) },
    )
    publicGroup.set(providers.gradleProperty("komustPublicIdentity").map { identity ->
        when (identity) {
            "preferred" -> "io.komust"
            "fallback" -> "io.github.mihafriskovec"
            else -> throw GradleException("Unknown komustPublicIdentity '$identity'")
        }
    })
    publicVersion.set(providers.gradleProperty("komustVersion"))
    verificationReport.set(layout.buildDirectory.file("nmcp/verification.txt"))
}

val validateCentralQualificationAuthority = tasks.register("validateCentralQualificationAuthority") {
    group = "verification"
    description = "Rejects mutable or overridden Central candidate coordinates before bundle construction."
    doFirst {
        val checkedInProperties = java.util.Properties().apply {
            rootProject.file("gradle.properties").inputStream().use(::load)
        }
        val checkedInVersion = checkedInProperties.getProperty("komustVersion")
            ?: throw GradleException("Central qualification requires checked-in komustVersion")
        val selectedVersion = providers.gradleProperty("komustVersion").orNull
            ?: throw GradleException("Central qualification requires komustVersion")
        val semver = Regex(
            "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)" +
                "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
        )
        if (!semver.matches(selectedVersion)) {
            throw GradleException("Central qualification version '$selectedVersion' is not valid SemVer")
        }
        if (selectedVersion.endsWith("-SNAPSHOT")) {
            throw GradleException("Central qualification requires a fixed version, not $selectedVersion")
        }
        if (selectedVersion != checkedInVersion ||
            gradle.startParameter.projectProperties.containsKey("komustVersion") ||
            System.getenv("ORG_GRADLE_PROJECT_komustVersion") != null
        ) {
            throw GradleException("Central qualification version must come unchanged from gradle.properties")
        }
        val checkedInIdentity = checkedInProperties.getProperty("komustPublicIdentity")
            ?: throw GradleException("Central qualification requires checked-in komustPublicIdentity")
        if (providers.gradleProperty("komustPublicIdentity").orNull != checkedInIdentity ||
            gradle.startParameter.projectProperties.containsKey("komustPublicIdentity") ||
            System.getenv("ORG_GRADLE_PROJECT_komustPublicIdentity") != null
        ) {
            throw GradleException("Central qualification identity must come unchanged from gradle.properties")
        }
    }
}

tasks.named("nmcpZipAggregation") {
    dependsOn(validateCentralQualificationAuthority)
}

tasks.register("qualificationCentralBundle") {
    group = "publishing"
    description = "Builds and verifies one signed, inspectable Maven Central candidate bundle."
    dependsOn(verifyCentralBundle)
}

tasks.register("stagePublications") {
    group = "publishing"
    description = "Stages the marker and all four implementation publications for release inspection."
    dependsOn(
        ":komust-compiler-plugin:publishAllPublicationsToQualificationRepository",
        ":komust-scope:publishAllPublicationsToQualificationRepository",
        ":komust-engine:publishAllPublicationsToQualificationRepository",
        ":komust-gradle-plugin:publishAllPublicationsToQualificationRepository",
    )
}

val qualificationIdentity = providers.gradleProperty("komustPublicIdentity").map {
    io.komust.conventions.PublicIdentity.named(it)
}
val qualificationRepository = layout.buildDirectory.dir("qualification-repository")
val qualificationManifest = layout.buildDirectory.file("qualification/qualification-manifest.json")

val generateQualificationManifest = tasks.register<GenerateQualificationManifestTask>("generateQualificationManifest") {
    group = "publishing"
    description = "Records the immutable commit, coordinates, names, sizes, and SHA-256s of the staged candidate."
    dependsOn("stagePublications")
    repository.set(qualificationRepository)
    candidateCommit.set(providers.exec {
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.map(String::trim))
    publicGroup.set(qualificationIdentity.map { it.group })
    publicPluginId.set(qualificationIdentity.map { it.pluginId })
    publicVersion.set(providers.gradleProperty("komustVersion"))
    manifest.set(qualificationManifest)
}

tasks.register<VerifyQualificationManifestTask>("verifyQualificationManifest") {
    group = "verification"
    description = "Verifies staged or production publication bytes against the Qualification manifest."
    dependsOn(generateQualificationManifest)
    manifest.set(qualificationManifest)
    repository.set(
        providers.gradleProperty("qualificationRepository")
            .map { layout.projectDirectory.dir(it) }
            .orElse(qualificationRepository),
    )
    publicGroup.set(qualificationIdentity.map { it.group })
    publicPluginId.set(qualificationIdentity.map { it.pluginId })
    publicVersion.set(providers.gradleProperty("komustVersion"))
}

val isolatedConsumerDirectory = layout.projectDirectory.dir("qualification/isolated-consumer")
val isolatedConsumerGradleHome = layout.buildDirectory.dir("isolated-consumer-gradle-home")

val cleanIsolatedConsumer = tasks.register<Delete>("cleanIsolatedConsumer") {
    delete(isolatedConsumerDirectory.dir("build"), isolatedConsumerGradleHome)
}

val qualifyIsolatedConsumer = tasks.register<Exec>("qualifyIsolatedConsumer") {
    group = "verification"
    description = "Qualifies the staged fixed-version publications through a fresh external Gradle consumer."
    dependsOn("stagePublications", cleanIsolatedConsumer)

    val candidateRepository = layout.buildDirectory.dir("qualification-repository")
    val candidateVersion = providers.gradleProperty("komustVersion")
    val candidateGroup = providers.gradleProperty("komustPublicIdentity").map {
        io.komust.conventions.PublicIdentity.named(it).group
    }
    val candidatePluginId = providers.gradleProperty("komustPublicIdentity").map {
        io.komust.conventions.PublicIdentity.named(it).pluginId
    }

    doFirst {
        commandLine(
            layout.projectDirectory.file("gradlew").asFile.absolutePath,
            "-p", isolatedConsumerDirectory.asFile.absolutePath,
            "--gradle-user-home", isolatedConsumerGradleHome.get().asFile.absolutePath,
            "--no-daemon",
            "--stacktrace",
            "-PkomustCandidateRepository=${candidateRepository.get().asFile.absolutePath}",
            "-PkomustCandidateVersion=${candidateVersion.get()}",
            "-PkomustCandidateGroup=${candidateGroup.get()}",
            "-PkomustCandidatePluginId=${candidatePluginId.get()}",
            "-PkomustSchemaDirectory=${layout.projectDirectory.dir("schema").asFile.absolutePath}",
            "clean",
            "test",
            "verifyCandidateResolution",
            "mutationTest",
            "--all",
            "verifyKomustReports",
        )
    }
}

val verifyReleaseDocumentation = tasks.register<VerifyReleaseDocumentationTask>("verifyReleaseDocumentation") {
    group = "verification"
    description = "Verifies that public documentation and release notes describe the fixed candidate exactly."
    repositoryRoot.set(layout.projectDirectory)
    documents.from("README.md", "CHANGELOG.md", "LICENSE", "komust-example/README.md")
    publicVersion.set(providers.gradleProperty("komustVersion"))
    publicIdentityName.set(providers.gradleProperty("komustPublicIdentity"))
    githubReleaseNotes.set(layout.buildDirectory.file("release/notes.md"))
}

qualifyIsolatedConsumer.configure {
    dependsOn(verifyReleaseDocumentation)
}
tasks.named("stagePublications") {
    mustRunAfter(verifyReleaseDocumentation)
}

tasks.register("qualifyReleaseDocumentation") {
    group = "verification"
    description = "Qualifies release notes and executes documented installation and example behavior against the staged candidate."
    dependsOn(qualifyIsolatedConsumer)
}
