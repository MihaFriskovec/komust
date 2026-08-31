package io.komust.compiler

import java.io.File
import java.nio.file.Paths

/**
 * Writes the **mutation manifest** — the list of mutants the IR pass wove into
 * this compilation with the compile-time facts `report.json` needs (#5, #38):
 * identity, source span, operator, `original → mutated`, enclosing symbol, and
 * binary class name.
 *
 * The engine reads it back as its slice of the **engine input contract**
 * (ADR-0005). It is emitted as plain JSON — kotlinx.serialization is not on the
 * compiler-plugin classpath (the module ships only what the compiler loads) —
 * with a semver [SCHEMA_VERSION] that is additive-only within a major, mirroring
 * the `report.json` contract.
 *
 * Records are sorted `(path, startLine, id)` so a manifest diffs cleanly between
 * compiles, matching the deterministic order the engine's `report.json` uses.
 */
internal object MutationManifestWriter {

    const val SCHEMA_VERSION: String = "1.0.0"

    /**
     * Write the manifest for [mutants] to [path], creating parent directories.
     * Source paths are made [projectDir]-relative and `/`-separated so a
     * `report.json` reader can open the file without guessing a working
     * directory (matching `scope.json`); a mutant outside [projectDir], or a
     * null [projectDir], keeps the compiler's raw path.
     */
    fun write(path: String, mutants: List<WovenMutant>, projectDir: String? = null) {
        val file = File(path)
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(render(mutants, projectDir))
    }

    /** The manifest JSON text (trailing newline, like `scope.json` / `report.json`). */
    fun render(mutants: List<WovenMutant>, projectDir: String? = null): String {
        val root = projectDir?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }
        val sorted = mutants
            .map { it to relativize(it.filePath, root) }
            .sortedWith(compareBy({ it.second }, { it.first.line }, { it.first.id }))
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": ").append(jsonString(SCHEMA_VERSION)).append(",\n")
            append("  \"mutants\": [")
            sorted.forEachIndexed { index, (mutant, relPath) ->
                append(if (index == 0) "\n" else ",\n")
                append("    ").append(entry(mutant, relPath))
            }
            if (sorted.isNotEmpty()) append("\n  ")
            append("]\n")
            append("}\n")
        }
    }

    /** [rawPath] made relative to [root] and `/`-separated, or `/`-normalised as-is. */
    private fun relativize(rawPath: String, root: java.nio.file.Path?): String {
        val normalized = rawPath.replace('\\', '/')
        if (root == null) return normalized
        return runCatching {
            val abs = Paths.get(rawPath).toAbsolutePath().normalize()
            if (abs.startsWith(root)) root.relativize(abs).toString().replace('\\', '/') else normalized
        }.getOrDefault(normalized)
    }

    private fun entry(mutant: WovenMutant, relPath: String): String {
        val (original, mutated) = splitChange(mutant.description)
        return buildString {
            append('{')
            field("id", jsonString(mutant.id)); append(", ")
            field("path", jsonString(relPath)); append(", ")
            field("startLine", mutant.line.toString()); append(", ")
            field("endLine", mutant.endLine.toString()); append(", ")
            field("column", mutant.column.toString()); append(", ")
            field("operator", jsonString(mutant.operator.slug)); append(", ")
            field("original", jsonString(original)); append(", ")
            field("mutated", jsonString(mutated)); append(", ")
            field("enclosingSymbol", jsonString(mutant.enclosingSymbol)); append(", ")
            field("binaryClassName", jsonString(mutant.binaryClassName))
            append('}')
        }
    }

    private fun StringBuilder.field(name: String, rawValue: String) {
        append(jsonString(name)).append(": ").append(rawValue)
    }

    /**
     * Split a catalog operator's human description into `original` / `mutated`.
     * Every arrow description uses `" → "` (`+ → -`, `return … → return true`);
     * the arrow-less ones (`remove call foo()`) have no distinct "before"
     * token, so the whole phrase is the change and `mutated` is `removed`.
     */
    private fun splitChange(description: String): Pair<String, String> {
        val arrow = " → "
        return if (description.contains(arrow)) {
            description.substringBefore(arrow).trim() to description.substringAfter(arrow).trim()
        } else {
            description.trim() to "removed"
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
        append('"')
    }
}
