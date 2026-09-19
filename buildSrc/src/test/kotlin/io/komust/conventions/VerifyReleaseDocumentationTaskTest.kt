package io.komust.conventions

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VerifyReleaseDocumentationTaskTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `task emits the changelog section used verbatim for GitHub Release notes`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"docs-fixture\"\n")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import io.komust.conventions.VerifyReleaseDocumentationTask

            plugins { id("komust.publication") }

            tasks.register<VerifyReleaseDocumentationTask>("verifyReleaseDocumentation") {
                repositoryRoot.set(layout.projectDirectory)
                documents.from("README.md", "CHANGELOG.md", "LICENSE", "docs/guide.md", "komust-example/README.md")
                publicVersion.set("0.1.0-alpha.1")
                publicIdentityName.set("preferred")
                githubReleaseNotes.set(layout.buildDirectory.file("release/notes.md"))
            }
            """.trimIndent(),
        )
        projectDir.resolve("docs").createDirectories()
        projectDir.resolve("docs/guide.md").writeText("# Guide\n")
        projectDir.resolve("komust-example").createDirectories()
        projectDir.resolve("komust-example/README.md").writeText(
            "# Example\n\n`./gradlew -p komust-example mutationTest --all`\n",
        )
        projectDir.resolve("LICENSE").writeText(
            "Apache License, Version 2.0 — https://www.apache.org/licenses/LICENSE-2.0\n",
        )
        projectDir.resolve("README.md").writeText(
            """
            # komust
            ${PublicDocumentation.description}
            > **Alpha evaluation prerelease:** the public compatibility surface may change.
            `plugins { id("io.komust") version "0.1.0-alpha.1" }`
            `./gradlew mutationTest --all`
            [${PublicDocumentation.licenseName}](LICENSE)
            [Guide](docs/guide.md) · [Project](${PublicDocumentation.projectUrl})
            """.trimIndent(),
        )
        val expected = """
            ## [0.1.0-alpha.1] - 2026-09-19

            Alpha evaluation prerelease. Its public compatibility surface may change.

            ### Added

            - Candidate documentation.
        """.trimIndent()
        projectDir.resolve("CHANGELOG.md").writeText("# Changelog\n\n## [Unreleased]\n\n$expected\n")

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("verifyReleaseDocumentation", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyReleaseDocumentation")?.outcome)
        assertEquals("$expected\n", projectDir.resolve("build/release/notes.md").readText())
    }
}
