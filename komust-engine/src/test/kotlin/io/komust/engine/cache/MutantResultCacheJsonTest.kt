package io.komust.engine.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `cache.json` (de)serialisation. Unlike `report.json`, a bad file is **never**
 * an error — it degrades to [MutantResultCache.EMPTY] (ADR-0003 §1).
 */
class MutantResultCacheJsonTest {

    private val cache = MutantResultCache(
        entries = listOf(
            CacheEntry("a", "fp-a", CachedStatus.SURVIVED, listOf(CachedCoveringTest("t1", killed = false))),
            CacheEntry("b", "fp-b", CachedStatus.KILLED, listOf(CachedCoveringTest("t2", killed = true))),
            CacheEntry("c", "fp-c", CachedStatus.NO_COVERAGE, emptyList()),
        ),
    )

    @Test
    fun `round-trips through encode then decode`() {
        assertEquals(cache, MutantResultCacheJson.decodeOrEmpty(MutantResultCacheJson.encode(cache)))
    }

    @Test
    fun `encoding is stable for a given cache state`() {
        assertEquals(MutantResultCacheJson.encode(cache), MutantResultCacheJson.encode(cache))
    }

    @Test
    fun `malformed json decodes to EMPTY, not an exception`() {
        assertSame(MutantResultCache.EMPTY, MutantResultCacheJson.decodeOrEmpty("{ not json"))
        assertSame(MutantResultCache.EMPTY, MutantResultCacheJson.decodeOrEmpty(""))
    }

    @Test
    fun `an unknown status enum value decodes to EMPTY`() {
        val text = """{"schemaVersion":"1.0.0","entries":[{"id":"a","fingerprint":"fp","status":"EXPLODED","coveringTests":[]}]}"""
        assertSame(MutantResultCache.EMPTY, MutantResultCacheJson.decodeOrEmpty(text))
    }

    @Test
    fun `a different schema major decodes to EMPTY`() {
        val future = MutantResultCache(schemaVersion = "2.0.0", entries = cache.entries)
        assertSame(MutantResultCache.EMPTY, MutantResultCacheJson.decodeOrEmpty(MutantResultCacheJson.encode(future)))
    }

    @Test
    fun `a later minor within the same major is tolerated`() {
        val later = MutantResultCache(schemaVersion = "1.7.0", entries = cache.entries)
        val decoded = MutantResultCacheJson.decodeOrEmpty(MutantResultCacheJson.encode(later))
        assertEquals(3, decoded.size)
    }

    @Test
    fun `an unknown field a later minor added is skipped`() {
        val text = """
            {"schemaVersion":"1.1.0","note":"from the future","entries":[
              {"id":"a","fingerprint":"fp","status":"SURVIVED","coveringTests":[{"uniqueId":"t1","killed":false,"durationMs":12}]}
            ]}
        """.trimIndent()
        val decoded = MutantResultCacheJson.decodeOrEmpty(text)
        assertEquals(1, decoded.size)
        assertTrue(decoded.lookup("a", ValidityFingerprint("fp")) != null)
    }
}
