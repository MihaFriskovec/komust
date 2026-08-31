import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * Shared configuration for every komust JVM module: the pinned Kotlin
 * toolchain, JUnit Platform test execution, JaCoCo wired to the catalog-pinned
 * tool version, shared `io.komust:*` coordinates, and a `testMaven` publication
 * the Gradle plugin's TestKit smoke test resolves the compiler plugin + engine
 * from.
 *
 * Library dependencies stay in each module's own build script (where the
 * `libs` catalog accessors are available); this plugin only carries the
 * cross-module policy.
 */

plugins {
    kotlin("jvm")
    jacoco
    `maven-publish`
}

private val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

group = providers.gradleProperty("komustGroup").getOrElse("io.komust")
version = providers.gradleProperty("komustVersion").getOrElse("0.1.0-SNAPSHOT")

kotlin {
    jvmToolchain(21)
}

publishing {
    repositories {
        maven {
            name = "testMaven"
            url = uri(rootProject.layout.buildDirectory.dir("test-maven"))
        }
    }
}

// `java-gradle-plugin` (komust-gradle-plugin) registers its own publications from
// the java component; every other module publishes its library jar explicitly.
afterEvaluate {
    if (!plugins.hasPlugin("java-gradle-plugin")) {
        publishing.publications.register<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<JacocoReport>().configureEach {
    dependsOn(tasks.withType<Test>())
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
