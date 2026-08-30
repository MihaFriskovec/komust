package io.komust.engine.cache

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Reads and writes the single `cache.json` under a run's output directory
 * (`build/komust/` in a normal build — ADR-0003 §4).
 *
 * Every operation is **best-effort**, symmetric with ADR-0003 §1's "a cache
 * problem costs time, never correctness":
 *
 *  - [load] returns [MutantResultCache.EMPTY] for every failure mode — the file
 *    is absent (fresh clone, `./gradlew clean`), unreadable, or its bytes do not
 *    decode to a reusable cache. A missing cache is the normal cold-start case,
 *    not an error.
 *  - [write] swallows I/O failure (a subsequent run simply starts cold) rather
 *    than aborting a run that has otherwise finished.
 *
 * ## Single atomic merge
 *
 * [write] is called **once**, by the controller, at run-end, with the result of
 * [MutantResultCache.merge]. It writes to a sibling temp file and then renames it
 * over `cache.json` — atomically where the filesystem supports it — so a crash
 * mid-write leaves the previous cache intact rather than a truncated file.
 * Workers never call this (ADR-0003 §4): there are no cross-fork write races.
 */
public class MutantResultCacheStore(private val outputDir: Path) {

    private val cacheFile: Path get() = outputDir.resolve(CACHE_JSON)

    /** The prior cache, or [MutantResultCache.EMPTY] if there is none to reuse. */
    public fun load(): MutantResultCache {
        val file = cacheFile
        if (!file.isRegularFile()) return MutantResultCache.EMPTY
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return MutantResultCache.EMPTY
        }
        return MutantResultCacheJson.decodeOrEmpty(text)
    }

    /**
     * Atomically replace `cache.json` with [cache]. A failure to write is
     * swallowed — the accelerator is optional, and the run's report is already
     * complete by the time this is called.
     */
    public fun write(cache: MutantResultCache) {
        val target = cacheFile
        var tmp: Path? = null
        try {
            outputDir.createDirectories()
            tmp = Files.createTempFile(outputDir, "cache", ".json.tmp")
            Files.writeString(tmp, MutantResultCacheJson.encode(cache) + "\n")
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            tmp = null
        } catch (_: IOException) {
            // Best-effort: next run starts cold.
        } finally {
            try {
                tmp?.deleteIfExists()
            } catch (_: IOException) {
                // A stray temp file is harmless.
            }
        }
    }

    public companion object {
        public const val CACHE_JSON: String = "cache.json"
    }
}
