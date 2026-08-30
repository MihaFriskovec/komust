package io.komust.engine.cache

import io.komust.engine.coverage.TestId
import io.komust.engine.sweep.Mutant
import io.komust.engine.sweep.MutantResult
import io.komust.engine.sweep.MutantStatus
import io.komust.engine.sweep.SweepResult

/**
 * Splits this run's in-scope mutants into the ones a prior outcome can be
 * **reused** for and the ones that must be **executed** (ADR-0003 §5), and
 * splices the two halves back together afterwards.
 *
 * The controller drives it:
 *
 * ```
 * val plan = IncrementalSweep.plan(candidates, cache, useCache = !noCache)
 * val executed = MutantSweep(coveragePass, ...).sweep(plan.execute)
 * val sweep = plan.combine(executed)                     // reuse ∪ executed
 * val fresh = IncrementalSweep.freshEntries(executed, candidates)
 * store.write(cache.merge(fresh, prune))                 // one atomic run-end write
 * ```
 *
 * Every mutant in the combined [SweepResult] — reused or freshly executed — is
 * indistinguishable to the report layer: the reused ones carry *this run's*
 * [Mutant] (hence this run's location/summary once
 * [io.komust.engine.report.ReportBuilder] joins them to the fresh descriptors),
 * and only the execution-derived status/covering-outcomes come from the cache
 * (ADR-0003 §2). The one imprecision: a reused fail-fast `KILLED` replays the
 * *cached* run's covering order and killer-prefix `testsExecuted`, since test
 * timings are re-measured every run and are not a fingerprint determinant.
 */
public object IncrementalSweep {

    /**
     * Partition [candidates] against [cache].
     *
     * @param candidates every in-scope mutant this run compiled, each with its
     *   [ValidityFingerprint]. The plan never contains a mutant not in this list
     *   — the cache cannot widen the report beyond the current scope (ADR-0003
     *   §2).
     * @param useCache `false` for `--no-cache`: forces every candidate into
     *   [IncrementalSweepPlan.execute] regardless of what the cache holds.
     */
    public fun plan(
        candidates: List<CacheCandidate>,
        cache: MutantResultCache,
        useCache: Boolean = true,
    ): IncrementalSweepPlan {
        if (!useCache) {
            return IncrementalSweepPlan(reuse = emptyList(), execute = candidates.map { it.mutant })
        }
        val reuse = ArrayList<MutantResult>(candidates.size)
        val execute = ArrayList<Mutant>()
        for (candidate in candidates) {
            val reused = cache.lookup(candidate.mutant.id, candidate.fingerprint)
                ?.toResultOrNull(candidate.mutant)
            if (reused != null) reuse += reused else execute += candidate.mutant
        }
        return IncrementalSweepPlan(reuse, execute)
    }

    /**
     * The [CacheEntry] list to hand [MutantResultCache.merge] — one per freshly
     * executed mutant in [executed], stamped with its fingerprint from
     * [candidates]. A result with no matching candidate fingerprint is skipped
     * (it could not be validated on a later run, so caching it would be unsafe).
     *
     * Reused mutants are deliberately absent: their entry is already in the prior
     * cache with a still-matching fingerprint and is retained by the merge.
     */
    public fun freshEntries(
        executed: SweepResult,
        candidates: List<CacheCandidate>,
    ): List<CacheEntry> {
        val fingerprintById = candidates.associate { it.mutant.id to it.fingerprint }
        return executed.results.mapNotNull { result ->
            val fingerprint = fingerprintById[result.mutant.id] ?: return@mapNotNull null
            result.toCacheEntry(fingerprint)
        }
    }
}

/** One in-scope mutant to weigh against the cache: the mutant plus its [ValidityFingerprint]. */
public data class CacheCandidate(
    val mutant: Mutant,
    val fingerprint: ValidityFingerprint,
)

/**
 * The outcome of [IncrementalSweep.plan]: [reuse] outcomes served from the cache
 * (already reconstructed as [MutantResult]s) and [execute] mutants the sweep
 * must still run.
 */
public class IncrementalSweepPlan internal constructor(
    public val reuse: List<MutantResult>,
    public val execute: List<Mutant>,
) {
    /**
     * Splice the reused outcomes with the sweep's fresh [executed] results into
     * one [SweepResult] covering every in-scope mutant. Reused entries lead, then
     * the executed ones in the sweep's order; both halves follow the candidate
     * order, so the result is deterministic (the report layer re-sorts by
     * `(path, line, id)` regardless).
     */
    public fun combine(executed: SweepResult): SweepResult =
        SweepResult(reuse + executed.results)
}

private fun CacheEntry.toResultOrNull(mutant: Mutant): MutantResult? =
    when (status) {
        CachedStatus.NO_COVERAGE -> MutantResult.noCoverage(mutant)

        CachedStatus.SURVIVED ->
            MutantResult.survived(mutant, coveringTests.map { TestId(it.uniqueId) })

        CachedStatus.KILLED -> {
            val killerIndex = killerIndex
            if (killerIndex < 0) {
                null // guarded by CacheEntry.isConsistent; defensive against a hand-corrupted file
            } else {
                val covering = coveringTests.map { TestId(it.uniqueId) }
                MutantResult.killed(
                    mutant,
                    coveringTests = covering,
                    killedBy = covering[killerIndex],
                    testsExecuted = killerIndex + 1, // fail-fast: only a prefix ran
                )
            }
        }
    }

private fun MutantResult.toCacheEntry(fingerprint: ValidityFingerprint): CacheEntry =
    CacheEntry(
        id = mutant.id,
        fingerprint = fingerprint.hex,
        status = status.toCached(),
        coveringTests = coveringTests.map { test ->
            CachedCoveringTest(test.uniqueId, killed = test == killedBy)
        },
    )

private fun MutantStatus.toCached(): CachedStatus = when (this) {
    MutantStatus.KILLED -> CachedStatus.KILLED
    MutantStatus.SURVIVED -> CachedStatus.SURVIVED
    MutantStatus.NO_COVERAGE -> CachedStatus.NO_COVERAGE
}
