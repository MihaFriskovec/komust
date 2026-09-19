package io.komust.conventions

import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CentralBundleVerifierTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `accepts exactly the three Central modules with signatures and four checksums`() {
        val bundle = bundle(expectedArtifacts)

        CentralBundleVerifier.verify(bundle, "io.komust", version) { _, _ -> }
    }

    @Test
    fun `rejects the Gradle plugin implementation in the Central bundle`() {
        val bundle = bundle(expectedArtifacts + "komust-gradle-plugin")

        assertThrows(IllegalArgumentException::class.java) {
            CentralBundleVerifier.verify(bundle, "io.komust", version) { _, _ -> }
        }
    }

    @Test
    fun `rejects a missing or incorrect sidecar`() {
        val bundle = bundle(expectedArtifacts, corrupt = "komust-engine/$version/komust-engine-$version.jar.sha256")

        assertThrows(IllegalArgumentException::class.java) {
            CentralBundleVerifier.verify(bundle, "io.komust", version) { _, _ -> }
        }
    }

    private fun bundle(artifacts: Set<String>, corrupt: String? = null): Path {
        val target = directory.resolve("aggregation.zip")
        ZipOutputStream(target.outputStream()).use { zip ->
            artifacts.forEach { artifact ->
                val directory = "io/komust/$artifact/$version"
                listOf(
                    "$artifact-$version.jar",
                    "$artifact-$version-sources.jar",
                    "$artifact-$version-javadoc.jar",
                    "$artifact-$version.pom",
                    "$artifact-$version.module",
                ).forEach { name ->
                    val path = "$directory/$name"
                    val content = "content:$path".toByteArray()
                    zip.entry(path, content)
                    zip.entry("$path.asc", "signature:$path".toByteArray())
                    algorithms.forEach { (suffix, algorithm) ->
                        val checksumPath = "$path.$suffix"
                        val checksum = MessageDigest.getInstance(algorithm).digest(content).toHex()
                        val bytes = if (corrupt != null && checksumPath.endsWith(corrupt)) {
                            "bad".toByteArray()
                        } else {
                            checksum.toByteArray()
                        }
                        zip.entry(checksumPath, bytes)
                    }
                }
            }
        }
        return target
    }

    private fun ZipOutputStream.entry(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val version = "0.1.0-alpha.1"
        val expectedArtifacts = setOf("komust-compiler-plugin", "komust-engine", "komust-scope")
        val algorithms = mapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")
    }
}
