package io.komust.engine.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MutantResultCacheStoreTest {

    @TempDir
    lateinit var dir: Path

    private val cache = MutantResultCache(
        entries = listOf(CacheEntry("a", "fp-a", CachedStatus.SURVIVED, listOf(CachedCoveringTest("t1")))),
    )

    @Test
    fun `load returns EMPTY when there is no cache file`() {
        assertSame(MutantResultCache.EMPTY, MutantResultCacheStore(dir).load())
    }

    @Test
    fun `write then load round-trips`() {
        val store = MutantResultCacheStore(dir)
        store.write(cache)
        assertEquals(cache, store.load())
    }

    @Test
    fun `write creates the output directory if absent`() {
        val nested = dir.resolve("build/komust")
        MutantResultCacheStore(nested).write(cache)
        assertEquals(cache, MutantResultCacheStore(nested).load())
    }

    @Test
    fun `write ends the file with a trailing newline`() {
        MutantResultCacheStore(dir).write(cache)
        assertEquals("\n", dir.resolve("cache.json").readText().takeLast(1))
    }

    @Test
    fun `write leaves no temp files behind`() {
        MutantResultCacheStore(dir).write(cache)
        assertFalse(dir.listDirectoryEntries().any { it.fileName.toString().contains(".tmp") })
    }

    @Test
    fun `a corrupt cache file loads as EMPTY`() {
        dir.createDirectories()
        dir.resolve("cache.json").writeText("totally not json {{{")
        assertSame(MutantResultCache.EMPTY, MutantResultCacheStore(dir).load())
    }

    @Test
    fun `overwriting an existing cache replaces it`() {
        val store = MutantResultCacheStore(dir)
        store.write(cache)
        val next = cache.merge(
            listOf(CacheEntry("b", "fp-b", CachedStatus.NO_COVERAGE, emptyList())),
            CachePrune.Scoped,
        )
        store.write(next)
        assertEquals(next, store.load())
    }
}
