package io.komust.conventions

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class QualificationCoordinate(val group: String, val artifact: String, val version: String)

data class QualificationFile(val name: String, val size: Long, val sha256: String)

data class QualifiedCoordinate(
    val coordinate: QualificationCoordinate,
    val files: List<QualificationFile>,
)

data class QualificationManifestDocument(
    val schemaVersion: String,
    val candidateCommit: String,
    val candidateVersion: String,
    val coordinates: List<QualifiedCoordinate>,
)

/** Generates and verifies the immutable, schema-versioned release-candidate inventory. */
object QualificationManifest {
    const val SCHEMA_VERSION = "1.0.0"
    private val derivedSuffixes = setOf("asc", "md5", "sha1", "sha256", "sha512")

    fun generate(
        repository: Path,
        candidateCommit: String,
        candidateVersion: String,
        expectedCoordinates: Set<QualificationCoordinate>,
    ): QualificationManifestDocument {
        requireCandidateIdentity(candidateCommit, candidateVersion)
        require(expectedCoordinates.isNotEmpty()) { "Qualification requires public coordinates" }
        require(expectedCoordinates.all { it.version == candidateVersion }) {
            "Every qualified coordinate must use candidate version $candidateVersion"
        }
        val actualCoordinates = discoverCoordinates(repository)
        require(actualCoordinates == expectedCoordinates) {
            "Staged coordinate set differs: expected $expectedCoordinates, found $actualCoordinates"
        }
        val qualified = expectedCoordinates.sortedWith(compareBy({ it.group }, { it.artifact }, { it.version }))
            .map { coordinate ->
                val directory = coordinate.directory(repository)
                val files = Files.list(directory).use { paths ->
                    paths.filter(Files::isRegularFile)
                        .filter { isCandidateFile(it.name) }
                        .map { path -> QualificationFile(path.name, Files.size(path), sha256(path)) }
                        .sorted(compareBy(QualificationFile::name))
                        .toList()
                }
                require(files.isNotEmpty()) { "$coordinate contains no immutable publication files" }
                QualifiedCoordinate(coordinate, files)
            }
        return QualificationManifestDocument(SCHEMA_VERSION, candidateCommit, candidateVersion, qualified)
    }

    fun verify(
        manifest: QualificationManifestDocument,
        repository: Path,
        expectedCoordinates: Set<QualificationCoordinate>,
    ) {
        require(manifest.schemaVersion == SCHEMA_VERSION) {
            "Unsupported qualification manifest schema ${manifest.schemaVersion}"
        }
        requireCandidateIdentity(manifest.candidateCommit, manifest.candidateVersion)
        require(manifest.coordinates.all { it.coordinate.version == manifest.candidateVersion }) {
            "Manifest coordinates do not all use candidate version ${manifest.candidateVersion}"
        }
        val manifestCoordinates = manifest.coordinates.map { it.coordinate }.toSet()
        require(manifestCoordinates.size == manifest.coordinates.size) { "Manifest contains duplicate coordinates" }
        require(manifestCoordinates == expectedCoordinates) {
            "Manifest coordinate set differs: expected $expectedCoordinates, found $manifestCoordinates"
        }
        val actualCoordinates = discoverCoordinates(repository)
        require(actualCoordinates == manifestCoordinates) {
            "Candidate coordinate set differs: expected $manifestCoordinates, found $actualCoordinates"
        }
        manifest.coordinates.forEach { qualified ->
            val directory = qualified.coordinate.directory(repository)
            val actualFiles = Files.list(directory).use { paths ->
                paths.iterator().asSequence()
                    .filter(Files::isRegularFile)
                    .filter { isCandidateFile(it.name) }
                    .associateBy { it.name }
            }
            val expectedFiles = qualified.files.associateBy(QualificationFile::name)
            require(expectedFiles.size == qualified.files.size) {
                "${qualified.coordinate} contains duplicate manifest filenames"
            }
            require(actualFiles.keys == expectedFiles.keys) {
                "${qualified.coordinate} files differ: expected ${expectedFiles.keys}, found ${actualFiles.keys}"
            }
            actualFiles.forEach { (name, path) ->
                val expected = expectedFiles.getValue(name)
                require(Files.size(path) == expected.size && sha256(path) == expected.sha256) {
                    "${qualified.coordinate}:$name differs from the qualified candidate"
                }
            }
        }
    }

    fun write(manifest: QualificationManifestDocument, path: Path) {
        val json = buildJsonObject {
            put("schemaVersion", manifest.schemaVersion)
            put("candidateCommit", manifest.candidateCommit)
            put("candidateVersion", manifest.candidateVersion)
            put("coordinates", buildJsonArray {
                manifest.coordinates.forEach { qualified ->
                    add(buildJsonObject {
                        put("group", qualified.coordinate.group)
                        put("artifact", qualified.coordinate.artifact)
                        put("version", qualified.coordinate.version)
                        put("files", buildJsonArray {
                            qualified.files.forEach { file ->
                                add(buildJsonObject {
                                    put("name", file.name)
                                    put("size", file.size)
                                    put("sha256", file.sha256)
                                })
                            }
                        })
                    })
                }
            })
        }
        Files.createDirectories(path.parent)
        Files.writeString(path, Json { prettyPrint = true }.encodeToString(json) + "\n")
    }

    fun read(path: Path): QualificationManifestDocument {
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        return QualificationManifestDocument(
            root.getValue("schemaVersion").jsonPrimitive.content,
            root.getValue("candidateCommit").jsonPrimitive.content,
            root.getValue("candidateVersion").jsonPrimitive.content,
            root.getValue("coordinates").jsonArray.map { element ->
                val coordinate = element.jsonObject
                QualifiedCoordinate(
                    QualificationCoordinate(
                        coordinate.getValue("group").jsonPrimitive.content,
                        coordinate.getValue("artifact").jsonPrimitive.content,
                        coordinate.getValue("version").jsonPrimitive.content,
                    ),
                    coordinate.getValue("files").jsonArray.map { fileElement ->
                        val file = fileElement.jsonObject
                        QualificationFile(
                            file.getValue("name").jsonPrimitive.content,
                            file.getValue("size").jsonPrimitive.content.toLong(),
                            file.getValue("sha256").jsonPrimitive.content,
                        )
                    },
                )
            },
        )
    }

    private fun discoverCoordinates(repository: Path): Set<QualificationCoordinate> =
        Files.walk(repository).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isRegularFile)
                .filter { isCandidateFile(it.name) }
                .mapNotNull { path ->
                    val relative = repository.relativize(path)
                    if (relative.nameCount < 4) {
                        null
                    } else {
                        val artifact = relative.getName(relative.nameCount - 3).toString()
                        val version = relative.getName(relative.nameCount - 2).toString()
                        val group = (0 until relative.nameCount - 3)
                            .joinToString(".") { relative.getName(it).toString() }
                        QualificationCoordinate(group, artifact, version)
                    }
                }
                .toSet()
        }

    private fun requireCandidateIdentity(commit: String, version: String) {
        require(Regex("[0-9a-f]{40}").matches(commit)) {
            "Candidate commit must be a full lowercase Git object id"
        }
        val semver = Regex(
            "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)" +
                "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
        )
        require(semver.matches(version) && !version.contains("SNAPSHOT", ignoreCase = true)) {
            "Candidate version must be fixed SemVer, got $version"
        }
    }

    private fun QualificationCoordinate.directory(repository: Path): Path =
        repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)

    private fun isDerived(name: String): Boolean = name.substringAfterLast('.', "") in derivedSuffixes

    private fun isCandidateFile(name: String): Boolean = !isDerived(name) && name != "maven-metadata.xml"

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
