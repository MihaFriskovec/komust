package io.komust.engine.cache

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The single (de)serialisation point for `cache.json`.
 *
 * Unlike `report.json` ([io.komust.engine.report.ReportJson]), which throws on a
 * malformed or wrong-major file because it is an **agent-facing contract**,
 * `cache.json` is a **best-effort accelerator**: any decode failure — malformed
 * JSON, a wrong shape, an unknown enum value, a different schema major — degrades
 * silently to [MutantResultCache.EMPTY]. A wiped or corrupt cache costs time,
 * never correctness (ADR-0003 §1).
 *
 * The encoded form is pretty-printed with a two-space indent and its [entries]
 * are already `id`-sorted by [MutantResultCache.merge], so a given logical cache
 * state serialises to byte-identical output run to run.
 */
public object MutantResultCacheJson {

    @OptIn(ExperimentalSerializationApi::class) // prettyPrintIndent
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        // Emit `killed: false` and an empty `coveringTests` explicitly — this is
        // an internal artifact, not the token-dense agent contract, so a
        // self-descriptive file is worth the bytes (`ReportJson` chooses the
        // opposite for `report.json`).
        encodeDefaults = true
        // Additive-only within a major: tolerate keys a later minor added.
        ignoreUnknownKeys = true
    }

    public fun encode(cache: MutantResultCache): String =
        json.encodeToString(MutantResultCache.serializer(), cache)

    /**
     * Decode [text], or return [MutantResultCache.EMPTY] if it is not a cache
     * this build can safely reuse. Never throws.
     */
    public fun decodeOrEmpty(text: String): MutantResultCache {
        val decoded = try {
            json.decodeFromString(MutantResultCache.serializer(), text)
        } catch (_: SerializationException) {
            return MutantResultCache.EMPTY
        } catch (_: IllegalArgumentException) {
            return MutantResultCache.EMPTY
        }
        if (decoded.majorDiffersFromThisBuild()) return MutantResultCache.EMPTY
        return decoded
    }

    private fun MutantResultCache.majorDiffersFromThisBuild(): Boolean =
        schemaVersion.substringBefore('.') != MutantResultCache.SCHEMA_VERSION.substringBefore('.')
}
