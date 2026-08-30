package io.komust.engine.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Lookup gating and the run-end merge/prune semantics of ADR-0003 §4. */
class MutantResultCacheTest {

    private val fpA = ValidityFingerprint("aaaa")
    private val fpB = ValidityFingerprint("bbbb")

    private fun survived(id: String, fp: ValidityFingerprint) =
        CacheEntry(id, fp.hex, CachedStatus.SURVIVED, listOf(CachedCoveringTest("t1", killed = false)))

    @Test
    fun `lookup hits only when id and fingerprint both match`() {
        val cache = MutantResultCache(entries = listOf(survived("m1", fpA)))

        assertEquals(CachedStatus.SURVIVED, cache.lookup("m1", fpA)?.status)
        assertNull(cache.lookup("m1", fpB), "stale fingerprint must miss")
        assertNull(cache.lookup("m2", fpA), "absent id must miss")
    }

    @Test
    fun `an internally inconsistent entry never hits`() {
        // KILLED but no covering test flagged as the killer.
        val broken = CacheEntry("m1", fpA.hex, CachedStatus.KILLED, listOf(CachedCoveringTest("t1", killed = false)))
        val cache = MutantResultCache(entries = listOf(broken))

        assertNull(cache.lookup("m1", fpA))
    }

    @Test
    fun `merge retains out-of-scope entries and refreshes in-scope ones`() {
        val prior = MutantResultCache(
            entries = listOf(survived("out-of-scope", fpA), survived("in-scope", fpA)),
        )
        val fresh = listOf(
            CacheEntry("in-scope", fpB.hex, CachedStatus.KILLED, listOf(CachedCoveringTest("t1", killed = true))),
        )

        val merged = prior.merge(fresh, CachePrune.Scoped)

        assertEquals(CachedStatus.SURVIVED, merged.lookup("out-of-scope", fpA)?.status)
        assertEquals(CachedStatus.KILLED, merged.lookup("in-scope", fpB)?.status)
        assertNull(merged.lookup("in-scope", fpA), "the stale in-scope entry was replaced")
    }

    @Test
    fun `a scoped merge never drops a remembered entry`() {
        val prior = MutantResultCache(entries = listOf(survived("a", fpA), survived("b", fpA)))

        val merged = prior.merge(emptyList(), CachePrune.Scoped)

        assertEquals(2, merged.size)
    }

    @Test
    fun `a full-run merge prunes entries whose id is no longer live`() {
        val prior = MutantResultCache(
            entries = listOf(survived("still-here", fpA), survived("deleted-mutant", fpA)),
        )
        val fresh = listOf(survived("brand-new", fpB))

        val merged = prior.merge(fresh, CachePrune.FullRun(liveIds = setOf("still-here", "brand-new")))

        assertEquals(setOf("brand-new", "still-here"), merged.entries.map { it.id }.toSet())
        assertNull(merged.lookup("deleted-mutant", fpA))
    }

    @Test
    fun `merge output is id-sorted for deterministic serialisation`() {
        val prior = MutantResultCache(entries = listOf(survived("z", fpA), survived("a", fpA)))
        val fresh = listOf(survived("m", fpB))

        val merged = prior.merge(fresh, CachePrune.Scoped)

        assertEquals(listOf("a", "m", "z"), merged.entries.map { it.id })
        assertEquals(MutantResultCache.SCHEMA_VERSION, merged.schemaVersion)
    }
}
