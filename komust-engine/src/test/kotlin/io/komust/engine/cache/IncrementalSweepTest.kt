package io.komust.engine.cache

import io.komust.engine.coverage.TestId
import io.komust.engine.sweep.Mutant
import io.komust.engine.sweep.MutantResult
import io.komust.engine.sweep.MutantStatus
import io.komust.engine.sweep.SweepResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reuse-vs-execute partitioning and lossless outcome round-tripping (ADR-0003 §5). */
class IncrementalSweepTest {

    private val fp = ValidityFingerprint("fp-1")
    private val t1 = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:a()]")
    private val t2 = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:b()]")
    private val t3 = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:c()]")

    private fun mutant(id: String, line: Int = 4) = Mutant(id, "fixture.Calc", line)

    private fun cacheOf(vararg results: MutantResult) =
        MutantResultCache.EMPTY.merge(
            IncrementalSweep.freshEntries(
                SweepResult(results.toList()),
                results.map { CacheCandidate(it.mutant, fp) },
            ),
            CachePrune.Scoped,
        )

    @Test
    fun `a fingerprint-matched mutant is reused, an unmatched one is executed`() {
        val cache = cacheOf(MutantResult(mutant("hit"), MutantStatus.SURVIVED, listOf(t1), null, 1))
        val candidates = listOf(
            CacheCandidate(mutant("hit"), fp),
            CacheCandidate(mutant("stale"), ValidityFingerprint("other")),
            CacheCandidate(mutant("cold"), fp),
        )

        val plan = IncrementalSweep.plan(candidates, cache)

        assertEquals(listOf("hit"), plan.reuse.map { it.mutant.id })
        assertEquals(setOf("stale", "cold"), plan.execute.map { it.id }.toSet())
    }

    @Test
    fun `--no-cache forces every candidate into execute`() {
        val cache = cacheOf(MutantResult(mutant("hit"), MutantStatus.SURVIVED, listOf(t1), null, 1))

        val plan = IncrementalSweep.plan(listOf(CacheCandidate(mutant("hit"), fp)), cache, useCache = false)

        assertTrue(plan.reuse.isEmpty())
        assertEquals(listOf("hit"), plan.execute.map { it.id })
    }

    @Test
    fun `the plan never contains a mutant outside the candidate set`() {
        val cache = cacheOf(
            MutantResult(mutant("remembered-but-out-of-scope"), MutantStatus.SURVIVED, listOf(t1), null, 1),
        )

        val plan = IncrementalSweep.plan(listOf(CacheCandidate(mutant("in-scope"), fp)), cache)

        assertEquals(emptyList<String>(), plan.reuse.map { it.mutant.id })
        assertEquals(listOf("in-scope"), plan.execute.map { it.id })
    }

    @Test
    fun `a reused SURVIVED outcome round-trips losslessly`() {
        val original = MutantResult(mutant("m"), MutantStatus.SURVIVED, listOf(t1, t2), killedBy = null, testsExecuted = 2)
        val reused = IncrementalSweep.plan(listOf(CacheCandidate(mutant("m"), fp)), cacheOf(original)).reuse.single()

        assertEquals(MutantStatus.SURVIVED, reused.status)
        assertEquals(listOf(t1, t2), reused.coveringTests)
        assertEquals(2, reused.testsExecuted)
        assertEquals(null, reused.killedBy)
    }

    @Test
    fun `a reused fail-fast KILLED keeps its whole covering set but a prefix testsExecuted`() {
        // Sweep visited t1 (passed), t2 (killed); t3 in the set but never run.
        val original = MutantResult(mutant("m"), MutantStatus.KILLED, listOf(t1, t2, t3), killedBy = t2, testsExecuted = 2)
        val reused = IncrementalSweep.plan(listOf(CacheCandidate(mutant("m"), fp)), cacheOf(original)).reuse.single()

        assertEquals(MutantStatus.KILLED, reused.status)
        assertEquals(listOf(t1, t2, t3), reused.coveringTests)
        assertEquals(t2, reused.killedBy)
        assertEquals(2, reused.testsExecuted)
    }

    @Test
    fun `a reused NO_COVERAGE outcome round-trips`() {
        val original = MutantResult(mutant("m"), MutantStatus.NO_COVERAGE, emptyList(), null, 0)
        val reused = IncrementalSweep.plan(listOf(CacheCandidate(mutant("m"), fp)), cacheOf(original)).reuse.single()

        assertEquals(MutantStatus.NO_COVERAGE, reused.status)
        assertTrue(reused.coveringTests.isEmpty())
        assertEquals(0, reused.testsExecuted)
    }

    @Test
    fun `a reused outcome carries this run's Mutant, not the cached line`() {
        val original = MutantResult(mutant("m", line = 4), MutantStatus.SURVIVED, listOf(t1), null, 1)
        val cache = cacheOf(original)

        // Same id, shifted line — the freshly-compiled mutant this run.
        val reused = IncrementalSweep.plan(listOf(CacheCandidate(mutant("m", line = 9), fp)), cache).reuse.single()

        assertEquals(9, reused.mutant.line, "location comes from this run, never the cache (ADR-0003 §2)")
    }

    @Test
    fun `combine splices the reused outcomes ahead of the fresh sweep`() {
        val cache = cacheOf(MutantResult(mutant("aaa"), MutantStatus.SURVIVED, listOf(t1), null, 1))
        val plan = IncrementalSweep.plan(
            listOf(CacheCandidate(mutant("aaa"), fp), CacheCandidate(mutant("zzz"), fp)),
            cache,
        )
        val executed = SweepResult(listOf(MutantResult(mutant("zzz"), MutantStatus.KILLED, listOf(t1), t1, 1)))

        val combined = plan.combine(executed)

        assertEquals(listOf("aaa", "zzz"), combined.results.map { it.mutant.id })
        assertEquals(1, combined.survived)
        assertEquals(1, combined.killed)
    }

    @Test
    fun `freshEntries covers only executed mutants that have a fingerprint`() {
        val executed = SweepResult(
            listOf(
                MutantResult(mutant("with-fp"), MutantStatus.SURVIVED, listOf(t1), null, 1),
                MutantResult(mutant("no-fp"), MutantStatus.SURVIVED, listOf(t1), null, 1),
            ),
        )

        val entries = IncrementalSweep.freshEntries(executed, listOf(CacheCandidate(mutant("with-fp"), fp)))

        assertEquals(listOf("with-fp"), entries.map { it.id })
    }
}
