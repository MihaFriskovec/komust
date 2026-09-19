package io.komust.conventions

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QualificationManifestTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `generation binds commit version coordinates filenames sizes and digests`() {
        val repository = stagedRepository()

        val manifest = QualificationManifest.generate(repository, commit, version, coordinates)

        assertEquals("1.0.0", manifest.schemaVersion)
        assertEquals(commit, manifest.candidateCommit)
        assertEquals(version, manifest.candidateVersion)
        assertEquals(coordinates, manifest.coordinates.map { it.coordinate }.toSet())
        assertEquals(
            listOf(
                QualificationFile("komust-engine-$version.jar", 6, sha256("engine")),
                QualificationFile("komust-engine-$version.module", 6, sha256("module")),
                QualificationFile("komust-engine-$version.pom", 3, sha256("pom")),
            ),
            manifest.coordinates.single { it.coordinate.artifact == "komust-engine" }.files,
        )
    }

    @Test
    fun `verification rejects missing extra renamed and changed candidate files`() {
        val repository = stagedRepository()
        val manifest = QualificationManifest.generate(repository, commit, version, coordinates)
        val engine = repository.resolve("io/komust/komust-engine/$version")

        engine.resolve("komust-engine-$version.jar").toFile().delete()
        assertThrows(IllegalArgumentException::class.java) { QualificationManifest.verify(manifest, repository, coordinates) }

        engine.resolve("komust-engine-$version.jar").writeBytes("engine".toByteArray())
        engine.resolve("renamed.bin").writeBytes("engine".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { QualificationManifest.verify(manifest, repository, coordinates) }

        engine.resolve("renamed.bin").toFile().delete()
        engine.resolve("komust-engine-$version.jar").writeBytes("change".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { QualificationManifest.verify(manifest, repository, coordinates) }
    }

    @Test
    fun `verification allows regenerated signatures and checksums but not changed primary bytes`() {
        val repository = stagedRepository()
        val manifest = QualificationManifest.generate(repository, commit, version, coordinates)
        val jar = repository.resolve("io/komust/komust-engine/$version/komust-engine-$version.jar")

        jar.resolveSibling("${jar.fileName}.asc").writeBytes("production-signature".toByteArray())
        jar.resolveSibling("${jar.fileName}.sha256").writeBytes("production-checksum".toByteArray())
        QualificationManifest.verify(manifest, repository, coordinates)

        jar.writeBytes("changed-primary".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { QualificationManifest.verify(manifest, repository, coordinates) }
    }

    @Test
    fun `json round trip preserves the schema versioned contract`() {
        val manifest = QualificationManifest.generate(stagedRepository(), commit, version, coordinates)
        val path = directory.resolve("qualification-manifest.json")

        QualificationManifest.write(manifest, path)

        assertEquals(manifest, QualificationManifest.read(path))
    }

    @Test
    fun `generation refuses a mutable candidate version`() {
        val mutableVersion = "0.1.0-SNAPSHOT"
        val mutableCoordinates = coordinates.map { it.copy(version = mutableVersion) }.toSet()
        assertThrows(IllegalArgumentException::class.java) {
            QualificationManifest.generate(
                stagedRepository(mutableCoordinates),
                commit,
                mutableVersion,
                mutableCoordinates,
            )
        }
    }

    private fun stagedRepository(selectedCoordinates: Set<QualificationCoordinate> = coordinates): Path {
        val repository = directory.resolve("repository")
        selectedCoordinates.forEach { coordinate ->
            val base = repository.resolve(coordinate.group.replace('.', '/'))
                .resolve(coordinate.artifact).resolve(coordinate.version)
            base.createDirectories()
            val prefix = "${coordinate.artifact}-${coordinate.version}"
            base.resolve("$prefix.pom").writeBytes("pom".toByteArray())
            if (!coordinate.artifact.endsWith(".gradle.plugin")) {
                base.resolve("$prefix.jar").writeBytes(
                    if (coordinate.artifact == "komust-engine") "engine".toByteArray() else "jar".toByteArray(),
                )
                base.resolve("$prefix.module").writeBytes("module".toByteArray())
            }
        }
        return repository
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val commit = "0123456789abcdef0123456789abcdef01234567"
        const val version = "0.1.0-alpha.1"
        val coordinates = setOf(
            QualificationCoordinate("io.komust", "io.komust.gradle.plugin", version),
            QualificationCoordinate("io.komust", "komust-gradle-plugin", version),
            QualificationCoordinate("io.komust", "komust-compiler-plugin", version),
            QualificationCoordinate("io.komust", "komust-engine", version),
            QualificationCoordinate("io.komust", "komust-scope", version),
        )
    }
}
