package io.komust.conventions

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseDocumentationVerifierTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `accepts documentation bound to the fixed alpha candidate and extracts exact release notes`() {
        fixture()

        val notes = ReleaseDocumentationVerifier.verify(root, version, PublicIdentity.named("preferred"))

        assertEquals(
            """
            ## [$version] - 2026-09-19

            Alpha evaluation prerelease. Its public compatibility surface may change.

            ### Added

            - First candidate.
            """.trimIndent(),
            notes,
        )
    }

    @Test
    fun `rejects snapshot and inconsistent documented versions`() {
        fixture(readmeVersion = "0.1.0-SNAPSHOT")
        assertFailureContains("SNAPSHOT")

        fixture(readmeVersion = "0.1.0-alpha.2")
        assertFailureContains("must apply io.komust version $version")
    }

    @Test
    fun `rejects a missing matching dated changelog section`() {
        fixture(changelog = "# Changelog\n\n## [Unreleased]\n\n- Pending.\n")

        assertFailureContains("matching dated section")
    }

    @Test
    fun `rejects missing Apache license or README and POM disagreement`() {
        fixture(license = "All rights reserved.\n")
        assertFailureContains("Apache License, Version 2.0")

        fixture(description = "A different product description.")
        assertFailureContains(PublicDocumentation.description)
    }

    @Test
    fun `rejects broken local links and unsafe public links`() {
        fixture(extraReadme = "\n[Missing](docs/missing.md)\n")
        assertFailureContains("does not exist")

        fixture(extraReadme = "\n[Project](http://github.com/MihaFriskovec/komust)\n")
        assertFailureContains("must use https")
    }

    @Test
    fun `requires prominent alpha and compatibility breaking language`() {
        fixture(alphaNotice = "A preview release.")
        assertFailureContains("Alpha evaluation prerelease")

        fixture(change = "### Changed\n\n- Renamed the public DSL.")
        assertFailureContains("Compatibility-breaking:")
    }

    private fun fixture(
        readmeVersion: String = version,
        description: String = PublicDocumentation.description,
        license: String = "Apache License, Version 2.0\nhttp://www.apache.org/licenses/LICENSE-2.0\n",
        changelog: String? = null,
        alphaNotice: String = "Alpha evaluation prerelease. Its public compatibility surface may change.",
        change: String = "### Added\n\n- First candidate.",
        extraReadme: String = "",
    ) {
        root.createDirectories()
        root.resolve("LICENSE").writeText(license)
        root.resolve("docs").createDirectories()
        root.resolve("docs/guide.md").writeText("# Guide\n")
        root.resolve("komust-example").createDirectories()
        root.resolve("komust-example/README.md").writeText(
            "# Example\n\n`./gradlew -p komust-example mutationTest --all`\n",
        )
        root.resolve("README.md").writeText(
            """
            # komust

            $description

            > **Alpha evaluation prerelease:** the public compatibility surface may change.

            ```kotlin
            plugins { id("io.komust") version "$readmeVersion" }
            ```

            ```console
            ./gradlew mutationTest --all
            ```

            Licensed under the [Apache License, Version 2.0](LICENSE).
            See the [guide](docs/guide.md) and [project](https://github.com/MihaFriskovec/komust).
            $extraReadme
            """.trimIndent(),
        )
        root.resolve("CHANGELOG.md").writeText(
            changelog ?: listOf(
                "# Changelog",
                "",
                "## [Unreleased]",
                "",
                "## [$version] - 2026-09-19",
                "",
                alphaNotice,
                "",
                change,
            ).joinToString("\n"),
        )
    }

    private fun assertFailureContains(message: String) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ReleaseDocumentationVerifier.verify(root, version, PublicIdentity.named("preferred"))
        }
        assertTrue(failure.message.orEmpty().contains(message), failure.message)
    }

    private companion object {
        const val version = "0.1.0-alpha.1"
    }
}
