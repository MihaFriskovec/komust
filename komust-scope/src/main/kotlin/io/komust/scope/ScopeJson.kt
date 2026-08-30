package io.komust.scope

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads and writes `scope.json` — the stable handoff from `komust-scope` to the
 * compiler plugin (via the Gradle plugin, ADR-0002/0005).
 *
 * The format contract (field rules, ordering, whole-file marker, versioning) is
 * specified once in **`docs/scope-json.md`**; this object is its only
 * implementation. [VERSION] is the sole `version` value it will emit or accept.
 */
object ScopeJson {

    const val VERSION = 1

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
    }

    fun encode(scope: MutationScope): String =
        json.encodeToString(ScopeDocument.serializer(), scope.toDocument())

    fun decode(text: String): MutationScope {
        val document = try {
            json.decodeFromString(ScopeDocument.serializer(), text)
        } catch (e: SerializationException) {
            throw ScopeResolutionException("malformed scope.json: ${e.message}", e)
        }
        if (document.version != VERSION) {
            throw ScopeResolutionException(
                "unsupported scope.json version ${document.version} (expected $VERSION)",
            )
        }
        return document.toMutationScope()
    }

    /** Write [scope] to [target], creating parent directories. */
    fun write(scope: MutationScope, target: Path) {
        target.toAbsolutePath().parent?.createDirectories()
        target.writeText(encode(scope) + "\n")
    }

    fun read(source: Path): MutationScope = decode(source.readText())

    @Serializable
    private data class ScopeDocument(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val version: Int = VERSION,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val files: List<FileEntry> = emptyList(),
    )

    @Serializable
    private data class FileEntry(
        val path: String,
        val wholeFile: Boolean = false,
        val ranges: List<Range> = emptyList(),
    )

    @Serializable
    private data class Range(val start: Int, val end: Int)

    private fun MutationScope.toDocument() = ScopeDocument(
        version = VERSION,
        files = files.map { entry ->
            if (entry.isWholeFile) {
                FileEntry(path = entry.path, wholeFile = true)
            } else {
                FileEntry(path = entry.path, ranges = entry.ranges.map { Range(it.start, it.end) })
            }
        },
    )

    private fun ScopeDocument.toMutationScope(): MutationScope {
        val fragments = files.associate { entry ->
            val ranges = when {
                entry.wholeFile -> listOf(LineRange.WHOLE_FILE)
                entry.ranges.isNotEmpty() -> entry.ranges.map { LineRange(it.start, it.end) }
                else -> throw ScopeResolutionException(
                    "scope.json entry '${entry.path}' has neither 'wholeFile' nor 'ranges'",
                )
            }
            entry.path to ranges
        }
        return MutationScope.of(fragments)
    }
}
