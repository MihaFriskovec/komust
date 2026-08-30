package io.komust.scope

/**
 * An inclusive, 1-based range of source lines.
 *
 * A whole file is the range covering all its lines — [WHOLE_FILE], which uses
 * [Int.MAX_VALUE] as an open upper bound so a new or untracked file can enter
 * scope without reading it to count lines. Downstream consumers (the compiler
 * plugin's enclosing-symbol expansion) only ever *intersect* ranges, so the
 * sentinel end is safe: it overlaps every real declaration span.
 */
data class LineRange(val start: Int, val end: Int) : Comparable<LineRange> {

    init {
        require(start >= 1) { "line range start must be >= 1, was $start" }
        require(end >= start) { "line range end ($end) must be >= start ($start)" }
    }

    val isWholeFile: Boolean
        get() = this == WHOLE_FILE

    /** True when [line] falls within this range (inclusive). */
    operator fun contains(line: Int): Boolean = line in start..end

    override fun compareTo(other: LineRange): Int =
        compareValuesBy(this, other, LineRange::start, LineRange::end)

    companion object {
        /** The range that covers an entire file of any length. */
        val WHOLE_FILE = LineRange(1, Int.MAX_VALUE)

        fun single(line: Int) = LineRange(line, line)
    }
}

/**
 * The canonical **Mutation Scope**: production Kotlin source file → the line
 * ranges a run considers for mutation.
 *
 * Invariants, all established by [of]:
 *  - paths are repo-root-relative, `/`-separated, and unique
 *  - [files] is sorted by [ScopeEntry.path]
 *  - each entry's ranges are sorted, non-overlapping and merged; a whole-file
 *    entry collapses to the single [LineRange.WHOLE_FILE] range
 *
 * An [EMPTY] scope means "nothing changed" — a clean, zero-mutant run. The
 * on-disk form is `scope.json`; its contract lives in `docs/scope-json.md`.
 */
data class MutationScope(val files: List<ScopeEntry>) {

    val isEmpty: Boolean
        get() = files.isEmpty()

    fun ranges(path: String): List<LineRange>? = files.firstOrNull { it.path == path }?.ranges

    companion object {
        val EMPTY = MutationScope(emptyList())

        /**
         * Build a normalised scope from raw `path → ranges` fragments. Fragments
         * for the same path are unioned; ranges are merged; entries are sorted.
         */
        fun of(fragments: Map<String, List<LineRange>>): MutationScope {
            val entries = fragments
                .filterValues { it.isNotEmpty() }
                .map { (path, ranges) -> ScopeEntry(path, mergeRanges(ranges)) }
                .sortedBy { it.path }
            return MutationScope(entries)
        }
    }
}

/** One file's contribution to a [MutationScope]. */
data class ScopeEntry(val path: String, val ranges: List<LineRange>) {

    init {
        require(ranges.isNotEmpty()) { "scope entry for '$path' has no ranges" }
    }

    val isWholeFile: Boolean
        get() = ranges.size == 1 && ranges[0].isWholeFile
}

/**
 * Sort, merge overlapping/adjacent ranges, and collapse to a single
 * [LineRange.WHOLE_FILE] if any input range is whole-file.
 */
internal fun mergeRanges(ranges: List<LineRange>): List<LineRange> {
    if (ranges.any { it.isWholeFile }) return listOf(LineRange.WHOLE_FILE)

    val sorted = ranges.sorted()
    val merged = ArrayList<LineRange>(sorted.size)
    for (range in sorted) {
        val last = merged.lastOrNull()
        if (last != null && range.start <= last.end + 1) {
            merged[merged.lastIndex] = LineRange(last.start, maxOf(last.end, range.end))
        } else {
            merged += range
        }
    }
    return merged
}
