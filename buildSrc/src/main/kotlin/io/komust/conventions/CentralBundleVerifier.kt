package io.komust.conventions

import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Verifies the complete, inspectable file contract of one nmcp Central bundle. */
object CentralBundleVerifier {
    private val centralArtifacts = setOf("komust-compiler-plugin", "komust-engine", "komust-scope")
    private val checksums = mapOf(
        "md5" to "MD5",
        "sha1" to "SHA-1",
        "sha256" to "SHA-256",
        "sha512" to "SHA-512",
    )

    fun verify(
        bundle: Path,
        group: String,
        version: String,
        verifySignature: (primary: ByteArray, signature: ByteArray) -> Unit,
    ) {
        ZipFile(bundle.toFile()).use { zip ->
            val files = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .associate { it.name to zip.getInputStream(it).use { stream -> stream.readBytes() } }
            val prefix = "${group.replace('.', '/')}/"
            require(files.keys.all { it.startsWith(prefix) }) { "Central bundle contains coordinates outside $group" }

            val coordinates = files.keys.mapNotNull { path ->
                path.removePrefix(prefix).split('/').takeIf { it.size >= 3 }?.let { it[0] to it[1] }
            }.toSet()
            require(coordinates.map { it.first }.toSet() == centralArtifacts) {
                "Central bundle must contain exactly $centralArtifacts, found ${coordinates.map { it.first }.toSet()}"
            }
            require(coordinates.all { it.second == version }) {
                "Central bundle versions must all be $version, found ${coordinates.map { it.second }.toSet()}"
            }

            centralArtifacts.forEach { artifact ->
                val directory = "$prefix$artifact/$version/"
                val primary = files.keys.filter { path ->
                    path.startsWith(directory) &&
                        (path.endsWith(".jar") || path.endsWith(".pom") || path.endsWith(".module"))
                }
                require(primary.count { it.endsWith(".pom") } == 1) { "$artifact must contain one POM" }
                require(primary.count { it.endsWith("-sources.jar") } == 1) { "$artifact must contain one sources JAR" }
                require(primary.count { it.endsWith("-javadoc.jar") } == 1) { "$artifact must contain one Javadoc JAR" }
                require(primary.count { it.endsWith(".jar") && !it.endsWith("-sources.jar") && !it.endsWith("-javadoc.jar") } == 1) {
                    "$artifact must contain one main JAR"
                }

                primary.forEach { path ->
                    val content = files.getValue(path)
                    val signature = files["$path.asc"] ?: error("$path is missing its detached signature")
                    verifySignature(content, signature)
                    checksums.forEach { (suffix, algorithm) ->
                        val actual = files["$path.$suffix"]?.toString(Charsets.US_ASCII)?.trim()
                            ?: error("$path is missing .$suffix")
                        val expected = MessageDigest.getInstance(algorithm).digest(content).toHex()
                        require(actual.equals(expected, ignoreCase = true)) { "$path has an invalid .$suffix checksum" }
                    }
                }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
