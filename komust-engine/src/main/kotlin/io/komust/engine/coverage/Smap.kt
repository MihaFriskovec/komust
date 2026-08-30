package io.komust.engine.coverage

/**
 * The slice of a class file's `SourceDebugExtension` (SMAP / JSR-045) that
 * inline-line normalisation needs: for each *output* line in the compiled class,
 * which *input* source file + line the Kotlin compiler copied it from.
 *
 * Kotlin writes an SMAP whenever a compilation unit contains inlined code. The
 * `*F` (file) section numbers the source files involved — the unit itself plus
 * every inline callee — and the `*L` (line) section maps input line ranges onto
 * the synthetic output line ranges the inlined bytecode occupies. See
 * <https://jcp.org/en/jsr/detail?id=045>.
 */
internal data class SmapLineMapping(
    /** First output line of this run (1-based, in the compiled class). */
    val outputStart: Int,
    /** Last output line of this run, inclusive. */
    val outputEnd: Int,
    /** Source file this run was copied from — an [SmapFile]. */
    val fileId: Int,
    /** Input line in [fileId] that [outputStart] came from. */
    val inputStart: Int,
    /** Output lines consumed per input line (`*L` "output line increment"). */
    val outputStep: Int,
) {
    /** The `(fileId, inputLine)` an [outputLine] within this run maps back to. */
    fun resolve(outputLine: Int): Pair<Int, Int>? {
        if (outputLine < outputStart || outputLine > outputEnd) return null
        val step = outputStep.coerceAtLeast(1)
        val inputOffset = (outputLine - outputStart) / step
        return fileId to (inputStart + inputOffset)
    }
}

/** One `*F` entry: the source file's short name and, when Kotlin emits it, its JVM path. */
internal data class SmapFile(val id: Int, val name: String, val path: String?) {
    /**
     * The JVM/internal class name (`com/pkg/ClassKt`) this file resolves to, or
     * `null` when it is the compilation unit's own source (Kotlin repeats the
     * bare `*.kt` name) rather than an inline callee (Kotlin writes the callee's
     * JVM path).
     */
    val vmClassName: String?
        get() {
            val p = path ?: return null
            if (p.endsWith(".kt") || p.endsWith(".java")) return null
            return p
        }

    /** [vmClassName] as a binary (dotted) class name. */
    val binaryClassName: String?
        get() = vmClassName?.replace('/', '.')
}

/** A parsed SMAP for one class: its file table and every line run. */
internal class ParsedSmap(
    val files: Map<Int, SmapFile>,
    val lineMappings: List<SmapLineMapping>,
) {
    companion object {
        val EMPTY = ParsedSmap(emptyMap(), emptyList())
    }
}

/**
 * Parses a Kotlin `SourceDebugExtension` string into a [ParsedSmap].
 *
 * Handles the `SMAP` header, one or more `*S <stratum>` sections (Kotlin emits
 * `Kotlin` and sometimes `KotlinDebug`; both are merged — mappings are additive
 * and de-duplicated downstream), the `*F` file section in both the plain
 * `<id> <name>` and the extended `+ <id> <name>` / path-on-next-line forms, and
 * the `*L` line section `input#file,repeat:output,increment`.
 *
 * Anything it does not recognise is skipped rather than throwing: a malformed or
 * unexpected SMAP degrades to "no inline normalisation for this class", which
 * ADR-0004 §Consequences accepts as a documented v1 risk.
 */
internal object SmapParser {

    fun parse(smap: String?): ParsedSmap {
        if (smap.isNullOrBlank()) return ParsedSmap.EMPTY
        val lines = smap.split('\n').map { it.trimEnd('\r') }
        if (lines.firstOrNull()?.trim() != "SMAP") return ParsedSmap.EMPTY

        val files = LinkedHashMap<Int, SmapFile>()
        val mappings = ArrayList<SmapLineMapping>()

        var section: Section = Section.NONE
        var strataSeen = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                // Only the first stratum (Kotlin's `*S Kotlin`) drives line
                // mapping; a trailing `*S KotlinDebug` re-numbers files for the
                // debugger and must not be merged in.
                line.startsWith("*S") -> {
                    strataSeen++
                    section = if (strataSeen == 1) Section.STRATUM else Section.DONE
                }
                section == Section.DONE -> Unit
                line.startsWith("*F") -> section = Section.FILES
                line.startsWith("*L") -> section = Section.LINES
                line.startsWith("*E") -> section = Section.NONE
                line.startsWith("*") -> section = Section.NONE // any other *X section: ignore its body
                section == Section.FILES && line.isNotBlank() -> {
                    i = parseFileEntry(lines, i, files)
                    continue
                }
                section == Section.LINES && line.isNotBlank() -> {
                    parseLineEntry(line)?.let(mappings::add)
                }
            }
            i++
        }
        return ParsedSmap(files, mappings)
    }

    private enum class Section { NONE, STRATUM, FILES, LINES, DONE }

    /** Returns the index to continue from (consumes the optional path line). */
    private fun parseFileEntry(lines: List<String>, index: Int, into: MutableMap<Int, SmapFile>): Int {
        var raw = lines[index].trim()
        val extended = raw.startsWith("+")
        if (extended) raw = raw.removePrefix("+").trim()

        val sep = raw.indexOf(' ')
        if (sep <= 0) return index + 1
        val id = raw.substring(0, sep).trim().toIntOrNull() ?: return index + 1
        val name = raw.substring(sep + 1).trim()

        var path: String? = null
        var next = index + 1
        if (extended && next < lines.size) {
            val candidate = lines[next].trim()
            // The path line never starts a new marker or entry.
            if (candidate.isNotEmpty() && !candidate.startsWith("*") && !candidate.startsWith("+") &&
                candidate.firstOrNull()?.isDigit() != true
            ) {
                path = candidate
                next++
            }
        }
        into.putIfAbsent(id, SmapFile(id, name, path))
        return next
    }

    private val LINE_ENTRY =
        Regex("""^(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?$""")

    private fun parseLineEntry(line: String): SmapLineMapping? {
        val m = LINE_ENTRY.matchEntire(line.trim()) ?: return null
        val inputStart = m.groupValues[1].toInt()
        val fileId = m.groupValues[2].ifEmpty { "1" }.toInt()
        val repeat = m.groupValues[3].ifEmpty { "1" }.toInt().coerceAtLeast(1)
        val outputStart = m.groupValues[4].toInt()
        val outputStep = m.groupValues[5].ifEmpty { "1" }.toInt().coerceAtLeast(1)
        val outputEnd = outputStart + repeat * outputStep - 1
        return SmapLineMapping(outputStart, outputEnd, fileId, inputStart, outputStep)
    }
}
