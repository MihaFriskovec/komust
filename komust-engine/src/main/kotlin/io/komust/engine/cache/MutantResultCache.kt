package io.komust.engine.cache

import kotlinx.serialization.Serializable

/**
 * The **best-effort** cross-run store of prior mutant execution outcomes
 * (ADR-0003, CONTEXT.md — **Mutant-result cache**).
 *
 * It holds only the **execution-derived** half of a mutant's result — its
 * [CacheEntry.status] and per-covering-test kill outcomes — keyed by the
 * line-independent mutant `id`. Everything a compile can rederive (location,
 * operator, `original → mutated`, the rendered summary) is regenerated fresh
 * each run and is deliberately *not* stored, so a reused survivor's line number
 * is always this run's (ADR-0003 §2).
 *
 * ## Best-effort
 *
 * A miss is always safe. A cold, wiped, version-skewed, or corrupt cache costs
 * time, never correctness — the answer is always defined by *actually executing
 * the mutant*. This is why the store lives under `build/komust/` with no
 * correctness consequence, and why [MutantResultCacheJson] degrades a malformed
 * or wrong-major file to [EMPTY] rather than throwing.
 *
 * ## Scope decides membership; the cache decides freshness
 *
 * This type never widens a run's report. The **Mutation Scope** (#6) is the sole
 * authority on *which* mutants appear; the cache only decides, of the in-scope
 * mutants, which are served from a prior outcome versus re-executed
 * ([IncrementalSweep]). An out-of-scope `id` the cache still remembers is
 * carried across [merge]s but never surfaced.
 */
@Serializable
public data class MutantResultCache(
    val schemaVersion: String = SCHEMA_VERSION,
    val entries: List<CacheEntry> = emptyList(),
) {
    private val byId: Map<String, CacheEntry> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        entries.associateBy { it.id }
    }

    /** The number of remembered outcomes. */
    public val size: Int get() = entries.size

    /**
     * The cached outcome for [id] **iff** it is present *and* its stored
     * [CacheEntry.fingerprint] matches [fingerprint] — the reuse gate of
     * ADR-0003 §3. A present-but-stale entry returns `null` (miss → re-execute);
     * so does an entry whose stored shape is internally inconsistent.
     */
    public fun lookup(id: String, fingerprint: ValidityFingerprint): CacheEntry? =
        byId[id]?.takeIf { it.fingerprint == fingerprint.hex && it.isConsistent }

    /**
     * The run-end **read-modify-write merge** (ADR-0003 §4), producing the cache
     * to persist. Only the controller calls this, once, at run-end; workers
     * never touch the cache.
     *
     *  - **Retain** every prior entry not in this run's scope — still valid until
     *    its own fingerprint changes. A scoped run only produces outcomes for
     *    in-scope symbols; overwriting would let a `--since HEAD` run erase the
     *    `diff-vs-main` cache.
     *  - **Refresh** every entry in [fresh] (the in-scope mutants executed this
     *    run), replacing any prior entry for the same `id`.
     *  - **Prune** dead entries **only** when [prune] is [CachePrune.FullRun] — a
     *    full (unscoped) run, which sees every live `id`. A scoped run cannot
     *    tell "deleted" from "out-of-scope", so it passes [CachePrune.Scoped] and
     *    never prunes.
     *
     * Reused (fingerprint-matched, not re-executed) entries need no mention: they
     * are already in `this` and are retained.
     *
     * The result's [entries] are sorted by `id` so the persisted file is
     * byte-deterministic for a given logical cache state.
     */
    public fun merge(fresh: List<CacheEntry>, prune: CachePrune): MutantResultCache {
        val merged = LinkedHashMap<String, CacheEntry>(entries.size + fresh.size)
        for (e in entries) merged[e.id] = e
        for (e in fresh) merged[e.id] = e

        val kept = when (prune) {
            is CachePrune.Scoped -> merged.values
            is CachePrune.FullRun -> merged.values.filter { it.id in prune.liveIds }
        }

        return MutantResultCache(
            schemaVersion = SCHEMA_VERSION,
            entries = kept.sortedBy { it.id },
        )
    }

    public companion object {
        /**
         * The `cache.json` schema version. Semver; a **different major** on a
         * read is treated as a total miss (the whole file is discarded), a minor
         * difference is tolerated — the same best-effort stance as the rest of
         * the cache. Not an agent-facing contract (that is `report.json`), so it
         * carries no in-repo JSON Schema.
         */
        public const val SCHEMA_VERSION: String = "1.0.0"

        /** The empty cache — every lookup misses. */
        public val EMPTY: MutantResultCache = MutantResultCache()
    }
}

/**
 * One remembered outcome — the minimal execution-derived record of ADR-0003 §4.
 *
 * @property id the line-independent content-hash mutant `id` (#5) — the join key
 *   with this run's freshly-compiled mutants.
 * @property fingerprint the [ValidityFingerprint.hex] captured when this outcome
 *   was produced; reuse requires it to still match.
 * @property status the execution outcome.
 * @property coveringTests the mutant's covering test set in the sweep's
 *   fastest-first visit order, each flagged whether it was the killer. Exactly
 *   one entry is `killed = true` for a [CachedStatus.KILLED] outcome (the sweep
 *   is fail-fast); none for [CachedStatus.SURVIVED]; the list is empty for
 *   [CachedStatus.NO_COVERAGE]. `testsExecuted` is *not* stored — it is
 *   rederivable (the killer's position for a kill, the set size for a survivor).
 */
@Serializable
public data class CacheEntry(
    val id: String,
    val fingerprint: String,
    val status: CachedStatus,
    val coveringTests: List<CachedCoveringTest> = emptyList(),
) {
    /** The killer's 0-based index in [coveringTests], or `-1` if none is flagged. */
    internal val killerIndex: Int get() = coveringTests.indexOfFirst { it.killed }

    /**
     * Whether the stored shape is self-consistent — the invariants the status
     * implies actually hold. An inconsistent entry (a hand-corrupted file, a
     * future-schema record decoded leniently) is not reused.
     */
    internal val isConsistent: Boolean
        get() = when (status) {
            CachedStatus.KILLED -> coveringTests.count { it.killed } == 1
            CachedStatus.SURVIVED -> coveringTests.isNotEmpty() && coveringTests.none { it.killed }
            CachedStatus.NO_COVERAGE -> coveringTests.isEmpty()
        }
}

/** One covering test in a [CacheEntry]: its JUnit Platform `uniqueId` and whether it killed the mutant. */
@Serializable
public data class CachedCoveringTest(
    val uniqueId: String,
    val killed: Boolean = false,
)

/**
 * The cacheable execution outcomes — the subset of the `report.json` status enum
 * the cache stores. Mirrors [io.komust.engine.sweep.MutantStatus]; kept separate
 * so the on-disk form is insulated from that internal enum. `TIMEOUT` joins both
 * when the forked worker pool (#34) can produce it.
 */
@Serializable
public enum class CachedStatus {
    KILLED,
    SURVIVED,
    NO_COVERAGE,
}

/**
 * The pruning policy for [MutantResultCache.merge], set by the run's scope
 * (ADR-0003 §4).
 */
public sealed interface CachePrune {
    /** A scoped run — keep every merged entry; a scoped run cannot prove an entry is dead. */
    public data object Scoped : CachePrune

    /**
     * A full (unscoped) run — keep only entries whose `id` is in [liveIds] (every
     * mutant this run compiled), dropping outcomes for mutants that no longer
     * exist.
     */
    public data class FullRun(val liveIds: Set<String>) : CachePrune
}
