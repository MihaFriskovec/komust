import org.gradle.api.GradleException
import org.gradle.api.tasks.Delete
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import io.komust.conventions.PublicIdentity

/**
 * Public-publication convention: one identity/version authority, complete Maven
 * metadata and documentation artifacts, and isolated repositories for both
 * development consumers and release qualification.
 */
plugins {
    java
    `maven-publish`
}

val identityName = providers.gradleProperty("komustPublicIdentity").orNull ?: "preferred"
val publicIdentity = PublicIdentity.named(identityName)
val publicGroup = publicIdentity.group
val publicPluginId = publicIdentity.pluginId

group = publicGroup
version = providers.gradleProperty("komustVersion").orNull ?: "unspecified"
extensions.extraProperties["komustPublicGroup"] = publicGroup
extensions.extraProperties["komustPublicPluginId"] = publicPluginId

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    repositories {
        maven {
            name = "testMaven"
            url = uri(rootProject.layout.buildDirectory.dir("test-maven"))
        }
        maven {
            name = "qualification"
            url = uri(rootProject.layout.buildDirectory.dir("qualification-repository"))
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("komust")
            description.set("Kotlin-native mutation testing for fast, actionable test-quality feedback.")
            url.set("https://github.com/MihaFriskovec/komust")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("MihaFriskovec")
                    name.set("Miha Friskovec")
                    url.set("https://github.com/MihaFriskovec")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/MihaFriskovec/komust.git")
                developerConnection.set("scm:git:ssh://github.com/MihaFriskovec/komust.git")
                url.set("https://github.com/MihaFriskovec/komust")
            }
        }
    }
}

val cleanQualificationRepository = rootProject.tasks.findByName("cleanQualificationRepository")
    ?.let { rootProject.tasks.named(it.name) }
    ?: rootProject.tasks.register("cleanQualificationRepository", Delete::class.java) {
        delete(rootProject.layout.buildDirectory.dir("qualification-repository"))
    }

fun checkedInProperty(name: String): String? =
    rootProject.file("gradle.properties").takeIf { it.isFile }?.useLines { lines ->
        lines.map(String::trim)
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.trim()
    }

fun validateReleaseAuthority() {
    val checkedInVersion = checkedInProperty("komustVersion")
        ?: throw GradleException("Release publication requires checked-in komustVersion")
    val selectedVersion = providers.gradleProperty("komustVersion").orNull
        ?: throw GradleException("Release publication requires komustVersion")
    val semver = Regex(
        "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)" +
            "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
    )
    if (!semver.matches(selectedVersion)) {
        throw GradleException("Release komustVersion '$selectedVersion' is not valid SemVer")
    }
    if (selectedVersion.substringBefore('+').endsWith("-SNAPSHOT")) {
        throw GradleException("Release komustVersion must be fixed, not SNAPSHOT: $selectedVersion")
    }
    val commandLineOverride = gradle.startParameter.projectProperties.containsKey("komustVersion")
    val environmentOverride = System.getenv("ORG_GRADLE_PROJECT_komustVersion") != null
    if (commandLineOverride || environmentOverride || selectedVersion != checkedInVersion) {
        throw GradleException("Release komustVersion must come unchanged from gradle.properties")
    }
    val checkedInIdentity = checkedInProperty("komustPublicIdentity")
        ?: throw GradleException("Release publication requires checked-in komustPublicIdentity")
    if (providers.gradleProperty("komustPublicIdentity").orNull != checkedInIdentity ||
        gradle.startParameter.projectProperties.containsKey("komustPublicIdentity") ||
        System.getenv("ORG_GRADLE_PROJECT_komustPublicIdentity") != null
    ) {
        throw GradleException("Release identity must come unchanged from gradle.properties")
    }
}

val validateReleaseAuthorityTask = tasks.register("validateReleaseAuthority") {
    group = "publishing"
    description = "Validates the checked-in version and public identity for a release-capable task."
    doLast { validateReleaseAuthority() }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (name.endsWith("ToQualificationRepository")) {
        dependsOn(cleanQualificationRepository)
        dependsOn(validateReleaseAuthorityTask)
    }
}
