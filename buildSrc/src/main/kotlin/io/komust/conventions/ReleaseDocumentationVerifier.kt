package io.komust.conventions

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** Public metadata shared by publication and documentation qualification. */
object PublicDocumentation {
    const val description = "Kotlin-native mutation testing for fast, actionable test-quality feedback."
    const val projectUrl = "https://github.com/MihaFriskovec/komust"
    const val licenseName = "Apache License, Version 2.0"
}

/** Qualifies public documentation against one immutable release candidate. */
object ReleaseDocumentationVerifier {
    private val fixedSemver = Regex(
        "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)" +
            "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
    )
    private val releaseHeading = Regex("(?m)^## \\[([^]]+)] - (\\d{4}-\\d{2}-\\d{2})$")
    private val markdownLink = Regex("(?<!!)\\[[^]]+]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")

    /** Returns the exact Markdown body that the GitHub Release must reproduce. */
    fun verify(root: Path, version: String, identity: PublicIdentity): String {
        require(fixedSemver.matches(version)) { "Release documentation version '$version' is not valid SemVer" }
        require(!version.substringBefore('+').endsWith("-SNAPSHOT")) {
            "Release documentation requires a fixed version, not SNAPSHOT: $version"
        }

        val readmePath = root.resolve("README.md")
        val exampleReadmePath = root.resolve("komust-example/README.md")
        val changelogPath = root.resolve("CHANGELOG.md")
        val licensePath = root.resolve("LICENSE")
        val readme = requiredText(readmePath)
        val exampleReadme = requiredText(exampleReadmePath)
        val changelog = requiredText(changelogPath)
        val license = requiredText(licensePath)

        require("SNAPSHOT" !in readme && "SNAPSHOT" !in exampleReadme && "SNAPSHOT" !in changelog) {
            "Release documentation must not contain SNAPSHOT versions"
        }
        require(PublicDocumentation.description in readme) {
            "README must reproduce the published POM description: ${PublicDocumentation.description}"
        }
        require(PublicDocumentation.projectUrl in readme) {
            "README must reproduce the published POM project URL: ${PublicDocumentation.projectUrl}"
        }
        require(PublicDocumentation.licenseName in license && "apache.org/licenses/LICENSE-2.0" in license) {
            "LICENSE must contain ${PublicDocumentation.licenseName} and its canonical URL"
        }
        require(PublicDocumentation.licenseName in readme) {
            "README must identify the ${PublicDocumentation.licenseName} used by the POM"
        }

        val pluginDeclaration = Regex(
            "id\\(\"${Regex.escape(identity.pluginId)}\"\\)\\s+version\\s+\"${Regex.escape(version)}\"",
        )
        require(pluginDeclaration.containsMatchIn(readme)) {
            "README installation must apply ${identity.pluginId} version $version"
        }
        require("./gradlew mutationTest --all" in readme) {
            "README must document the qualified './gradlew mutationTest --all' example command"
        }
        require("./gradlew -p komust-example mutationTest --all" in exampleReadme) {
            "komust-example/README.md must document its executable mutationTest --all command"
        }
        val documentedKomustVersions = Regex(
            "(?:${Regex.escape(identity.group)}:[A-Za-z0-9_.-]+:|" +
                "id\\(\"${Regex.escape(identity.pluginId)}\"\\)\\s+version\\s+\")($fixedSemver)",
        ).findAll(readme).map { it.groupValues[1] }.toSet()
        require(documentedKomustVersions == setOf(version)) {
            "README komust versions must all be $version, found $documentedKomustVersions"
        }

        validateLinks(root, readmePath, readme)
        validateLinks(root, exampleReadmePath, exampleReadme)
        validateLinks(root, changelogPath, changelog)

        val matching = releaseHeading.findAll(changelog).singleOrNull { it.groupValues[1] == version }
            ?: throw IllegalArgumentException("CHANGELOG.md must contain one matching dated section for $version")
        LocalDate.parse(matching.groupValues[2])
        val nextSection = Regex("(?m)^## ").find(changelog, matching.range.last + 1)?.range?.first ?: changelog.length
        val notes = changelog.substring(matching.range.first, nextSection).trim()

        if (version.contains("alpha", ignoreCase = true)) {
            require("Alpha evaluation prerelease" in readme && "public compatibility surface may change" in readme) {
                "README must prominently state: Alpha evaluation prerelease; the public compatibility surface may change"
            }
            require("Alpha evaluation prerelease" in notes && "public compatibility surface may change" in notes) {
                "Alpha release notes must prominently state: Alpha evaluation prerelease; the public compatibility surface may change"
            }
        }
        if (Regex("(?m)^### (Changed|Removed)$").containsMatchIn(notes)) {
            require("Compatibility-breaking:" in notes) {
                "Release notes with Changed or Removed entries must include a prominent 'Compatibility-breaking:' statement"
            }
        }
        return notes
    }

    private fun requiredText(path: Path): String {
        require(Files.isRegularFile(path)) { "Required release document ${path.fileName} is missing" }
        return Files.readString(path)
    }

    private fun validateLinks(root: Path, document: Path, content: String) {
        markdownLink.findAll(content).forEach { match ->
            val target = match.groupValues[1]
            when {
                target.startsWith("#") -> Unit
                target.startsWith("https://") -> {
                    val uri = URI(target)
                    require(!uri.host.isNullOrBlank()) { "$document contains invalid link '$target'" }
                }
                target.startsWith("http://") -> throw IllegalArgumentException(
                    "$document public link '$target' must use https",
                )
                URI(target).isAbsolute -> throw IllegalArgumentException(
                    "$document contains unsupported public link '$target'",
                )
                else -> {
                    val relative = target.substringBefore('#')
                    if (relative.isNotEmpty()) {
                        val resolved = document.parent.resolve(relative).normalize()
                        require(resolved.startsWith(root.normalize()) && Files.exists(resolved)) {
                            "$document link '$target' does not exist within the repository"
                        }
                    }
                }
            }
        }
    }
}
