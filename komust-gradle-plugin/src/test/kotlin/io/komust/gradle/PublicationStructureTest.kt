package io.komust.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Verifies the consumer-visible publication seam in the isolated repository. */
class PublicationStructureTest {
    private val repository = Path.of(
        System.getProperty("komust.testMavenRepo") ?: error("komust.testMavenRepo not set"),
    )
    private val version = System.getProperty("komust.version") ?: error("komust.version not set")
    private val group = System.getProperty("komust.group") ?: error("komust.group not set")
    private val pluginId = System.getProperty("komust.pluginId") ?: error("komust.pluginId not set")

    @Test
    fun `staged repository contains one marker and four implementation publications in lockstep`() {
        implementationArtifacts.forEach { artifact ->
            val directory = publicationDirectory(artifact)
            assertTrue(directory.exists(), "missing $group:$artifact:$version")
            val pom = singleFile(directory, ".pom")
            assertEquals(group, pom.elementText("groupId"))
            assertEquals(artifact, pom.elementText("artifactId"))
            assertEquals(version, pom.elementText("version"))
        }
        val marker = markerPom()
        assertEquals(pluginId, marker.elementText("groupId"))
        assertEquals("$pluginId.gradle.plugin", marker.elementText("artifactId"))
        assertEquals(version, marker.elementText("version"))
    }

    @Test
    fun `implementation publications expose metadata and documentation artifacts`() {
        (implementationArtifacts.map(::publicationDirectory) + listOf(markerPublicationDirectory())).forEach { directory ->
            val pom = singleFile(directory, ".pom")
            assertEquals("komust", pom.elementText("name"))
            assertTrue(pom.elementText("description").isNotBlank())
            assertEquals("https://github.com/MihaFriskovec/komust", pom.elementText("url"))
            assertEquals("Apache License, Version 2.0", pom.elementText("license/name"))
            assertEquals("Miha Friskovec", pom.elementText("developer/name"))
            assertEquals("scm:git:git://github.com/MihaFriskovec/komust.git", pom.elementText("scm/connection"))
        }
        implementationArtifacts.forEach { artifact ->
            val directory = publicationDirectory(artifact)
            listOf(".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module").forEach { suffix ->
                assertTrue(files(directory).any { it.name.endsWith(suffix) }, "$artifact is missing *$suffix")
            }
        }
    }

    @Test
    fun `published first party dependency topology is exact and compile only dependencies stay private`() {
        assertEquals(mapOf("komust-gradle-plugin" to "compile"), firstPartyDependencies(markerPom()))
        assertEquals(mapOf("komust-scope" to "runtime"), firstPartyDependencies(pom("komust-gradle-plugin")))
        assertEquals(mapOf("komust-scope" to "runtime"), firstPartyDependencies(pom("komust-compiler-plugin")))
        assertEquals(emptyMap<String, String>(), firstPartyDependencies(pom("komust-engine")))
        assertEquals(emptyMap<String, String>(), firstPartyDependencies(pom("komust-scope")))

        implementationArtifacts.forEach { artifact ->
            val directory = publicationDirectory(artifact)
            val text = pom(artifact).readText() + singleFile(directory, ".module").readText()
            assertFalse(text.contains("kotlin-compiler-embeddable"), "$artifact leaked the Kotlin compiler")
            assertFalse(text.contains("kotlin-gradle-plugin"), "$artifact leaked the Kotlin Gradle plugin")
        }
    }

    @Test
    fun `plugin marker references the selected same version implementation`() {
        assertEquals(
            setOf(Dependency(group, "komust-gradle-plugin", version, "compile")),
            dependencies(markerPom()).toSet(),
        )
    }

    private val implementationArtifacts = listOf(
        "komust-gradle-plugin",
        "komust-compiler-plugin",
        "komust-engine",
        "komust-scope",
    )

    private fun markerPom(): Path = singleFile(markerPublicationDirectory(), ".pom")
    private fun pom(artifact: String): Path = singleFile(publicationDirectory(artifact), ".pom")

    private fun markerPublicationDirectory(): Path =
        repository.resolve(pluginId.replace('.', '/')).resolve("$pluginId.gradle.plugin").resolve(version)

    private fun publicationDirectory(artifact: String): Path =
        repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)

    private fun files(directory: Path): List<Path> =
        if (!directory.exists()) emptyList() else Files.list(directory).use { it.toList() }

    private fun singleFile(directory: Path, suffix: String): Path =
        files(directory)
            .filter { it.name.endsWith(suffix) && !it.name.endsWith(".sha1") && !it.name.endsWith(".md5") }
            .maxBy { Files.getLastModifiedTime(it).toMillis() }

    private fun firstPartyDependencies(pom: Path): Map<String, String> {
        return dependencies(pom)
            .filter { it.group == group }
            .associate { it.artifact to it.scope }
    }

    private fun dependencies(pom: Path): List<Dependency> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
        val dependencies = document.getElementsByTagName("dependency")
        return (0 until dependencies.length).map { dependencies.item(it) as Element }
            .map {
                Dependency(
                    group = it.childText("groupId"),
                    artifact = it.childText("artifactId"),
                    version = it.childTextOrNull("version"),
                    scope = it.childTextOrNull("scope").orEmpty().ifBlank { "compile" },
                )
            }
    }

    private data class Dependency(
        val group: String,
        val artifact: String,
        val version: String?,
        val scope: String,
    )

    private fun Path.elementText(path: String): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(toFile())
        var element = document.documentElement
        path.split('/').forEach { name -> element = element.getElementsByTagName(name).item(0) as Element }
        return element.textContent.trim()
    }

    private fun Element.childText(name: String): String = getElementsByTagName(name).item(0).textContent.trim()
    private fun Element.childTextOrNull(name: String): String? = getElementsByTagName(name).item(0)?.textContent?.trim()
}
