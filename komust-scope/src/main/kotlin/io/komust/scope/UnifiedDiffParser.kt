package io.komust.scope

/** One file's changed line ranges, as read off a unified diff. */
internal data class DiffFile(
    /** Post-image repo-relative path (`+++ b/<path>`). */
    val path: String,
    /** True when the pre-image was `/dev/null` — a newly added file. */
    val isNewFile: Boolean,
    /** Changed line ranges on the post-image side. Empty for a pure rename. */
    val ranges: List<LineRange>,
)

/**
 * Parses `git diff --unified=0` output into per-file changed line ranges on the
 * **post-image** side (the "b" side) — the lines that exist after the change,
 * which is what maps onto the current source the compiler plugin will mutate.
 *
 * Only the hunk headers are needed: with `--unified=0` there is no context, so
 * `@@ -a,b +c,d @@` says lines `c … c+d-1` changed. A `d` of `0` is a pure
 * deletion at that point; we still surface the adjacent line so the enclosing
 * symbol is pulled into scope.
 */
internal object UnifiedDiffParser {

    private val HUNK_HEADER =
        Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")

    fun parse(diff: String): List<DiffFile> {
        val files = mutableListOf<DiffFile>()

        var path: String? = null
        var isNewFile = false
        var ranges = mutableListOf<LineRange>()

        fun flush() {
            val p = path ?: return
            files += DiffFile(p, isNewFile, ranges.toList())
        }

        for (line in diff.lineSequence()) {
            when {
                line.startsWith("diff --git ") -> {
                    flush()
                    path = null
                    isNewFile = false
                    ranges = mutableListOf()
                }

                line.startsWith("--- ") -> {
                    isNewFile = line.substring(4).trim() == "/dev/null"
                }

                line.startsWith("+++ ") -> {
                    val raw = line.substring(4).trim()
                    path = if (raw == "/dev/null") null else stripPrefix(raw)
                }

                line.startsWith("@@ ") -> {
                    val match = HUNK_HEADER.find(line) ?: continue
                    val start = match.groupValues[1].toInt()
                    val count = match.groupValues[2].let { if (it.isEmpty()) 1 else it.toInt() }
                    ranges += if (count == 0) {
                        LineRange.single(maxOf(start, 1))
                    } else {
                        LineRange(start, start + count - 1)
                    }
                }
            }
        }
        flush()

        return files.filter { it.path.isNotEmpty() }
    }

    /**
     * Strip the `a/` or `b/` diff prefix and unquote a C-quoted path (git quotes
     * paths containing unusual bytes when `core.quotePath` is on).
     */
    private fun stripPrefix(raw: String): String {
        val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
            unquoteCString(raw.substring(1, raw.length - 1))
        } else {
            raw
        }
        return unquoted.removePrefix("a/").removePrefix("b/")
    }

    private fun unquoteCString(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    't' -> { out.append('\t'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    '"' -> { out.append('"'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    in '0'..'7' -> {
                        val octal = s.substring(i + 1).takeWhile { it in '0'..'7' }.take(3)
                        out.append(octal.toInt(8).toChar())
                        i += 1 + octal.length
                    }
                    else -> { out.append(next); i += 2 }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
