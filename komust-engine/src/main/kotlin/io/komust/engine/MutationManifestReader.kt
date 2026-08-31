package io.komust.engine

import io.komust.engine.report.MutantDescriptor
import io.komust.engine.report.SourceLocation
import io.komust.engine.sweep.Mutant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Reads the compiler plugin's `mutants.json` **mutation manifest**(s) (#38) into
 * the engine's two per-mutant slices: the report layer's [MutantDescriptor]
 * (identity, location, operator, `original → mutated`, enclosing symbol) and the
 * sweep's [Mutant] (runtime-switch id + coverage-index key + source file).
 *
 * Several manifests (one per mutated source set) merge by mutant `id`; a
 * duplicate id must carry identical facts or the compile is internally
 * inconsistent and this throws.
 */
public object MutationManifestReader {

    private val json = Json { ignoreUnknownKeys = true }

    /** The current manifest schema major this build understands. */
    private const val SUPPORTED_MAJOR = 1

    public data class Mutants(
        val descriptors: List<MutantDescriptor>,
        val mutants: List<Mutant>,
    )

    public fun read(manifests: List<Path>): Mutants {
        val byId = LinkedHashMap<String, MutantDescriptor>()
        for (path in manifests) {
            val file = parse(path)
            val major = file.schemaVersion.substringBefore('.').toIntOrNull()
            require(major == SUPPORTED_MAJOR) {
                "mutation manifest $path has schemaVersion ${file.schemaVersion}; this komust understands major $SUPPORTED_MAJOR"
            }
            for (entry in file.mutants) {
                val descriptor = entry.toDescriptor()
                val existing = byId.putIfAbsent(entry.id, descriptor)
                require(existing == null || existing == descriptor) {
                    "mutant '${entry.id}' appears in more than one manifest with different facts"
                }
            }
        }
        val descriptors = byId.values.toList()
        return Mutants(
            descriptors = descriptors,
            mutants = descriptors.map { it.toMutantWithSourceFile() },
        )
    }

    private fun parse(path: Path): ManifestFile = try {
        json.decodeFromString(ManifestFile.serializer(), path.readText())
    } catch (e: SerializationException) {
        throw IllegalArgumentException("mutation manifest $path is malformed: ${e.message}", e)
    }

    private fun ManifestMutant.toDescriptor(): MutantDescriptor {
        val start = startLine.coerceAtLeast(1)
        return MutantDescriptor(
            id = id,
            location = SourceLocation(
                path = path,
                startLine = start,
                endLine = endLine.coerceAtLeast(start),
                startColumn = column,
            ),
            operator = operator,
            original = original,
            mutated = mutated,
            enclosingSymbol = enclosingSymbol,
            binaryClassName = binaryClassName,
        )
    }

    /** Like [MutantDescriptor.toMutant] but keeps the source file the per-file `--tests` override needs. */
    private fun MutantDescriptor.toMutantWithSourceFile(): Mutant =
        Mutant(id = id, coverageKey = coverageKey, sourceFile = location.path)

    @Serializable
    private data class ManifestFile(
        val schemaVersion: String,
        val mutants: List<ManifestMutant> = emptyList(),
    )

    @Serializable
    private data class ManifestMutant(
        val id: String,
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val column: Int? = null,
        val operator: String,
        val original: String,
        val mutated: String,
        val enclosingSymbol: String,
        val binaryClassName: String,
    )
}
