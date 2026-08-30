package io.komust.engine.sweep.forked

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The line-framed wire protocol between the controller ([ForkedMutantSweep]) and
 * a forked worker JVM ([io.komust.engine.sweep.forked.worker.WorkerMain]).
 *
 * **Controller → worker** (worker stdin): one [WorkItem] JSON object per line.
 * Closing the stream (EOF) tells the worker to exit cleanly.
 *
 * **Worker → controller** (worker stdout): one [WorkerMessage] per line, each
 * prefixed with [MARKER] so a stray `println` from the code under test on stdout
 * is ignored rather than parsed as a message. Anything the worker writes to
 * stderr is passed through untouched for debugging.
 */
internal object WorkerProtocol {

    /** Prefix on every worker→controller line — stray stdout without it is dropped. */
    const val MARKER: String = "##komust##"

    val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
}

/**
 * One unit of work the controller hands a worker: score this mutant against
 * these covering tests, in this order (already fastest-first), fail-fast.
 *
 * Selection happens controller-side (it owns the [io.komust.engine.coverage.CoveragePassResult]);
 * a `NO_COVERAGE` mutant is scored without a worker ever seeing it, so every
 * [WorkItem] has a non-empty [tests].
 */
@Serializable
internal data class WorkItem(
    val mutantId: String,
    val tests: List<CoveringTestSpec>,
)

/** One covering test plus its baseline-relative timeout budget (ADR-0003). */
@Serializable
internal data class CoveringTestSpec(
    val uniqueId: String,
    val timeoutMillis: Long,
)

/** Why a `KILLED` mutant was killed — tracked only so the controller knows whether to recycle the worker. */
@Serializable
internal enum class KillKind {
    /** A covering test failed or threw — the worker is fine, keep using it. */
    TEST_FAILURE,

    /** A covering test ran the JVM out of memory / overflowed the stack — recycle the worker (heap unstable). */
    MEMORY_ERROR,
}

/** The worker's verdict for one mutant (the RESULT payload). */
@Serializable
internal data class MutantOutcome(
    val mutantId: String,
    val status: Status,
    val testsExecuted: Int,
    val killKind: KillKind? = null,
    val killedByUniqueId: String? = null,
) {
    @Serializable
    enum class Status { SURVIVED, KILLED, TIMEOUT }

    /** True when the worker must be recycled after this outcome (ADR-0003 §Hang detection / §Outcome taxonomy). */
    val requiresWorkerRecycle: Boolean
        get() = status == Status.TIMEOUT || killKind == KillKind.MEMORY_ERROR
}

/** A message from a worker to the controller. */
internal sealed interface WorkerMessage {

    /** The worker has booted and is ready for its first [WorkItem]. */
    data object Ready : WorkerMessage

    /** The worker has begun scoring [mutantId] (emitted before running any test). */
    data class Started(val mutantId: String) : WorkerMessage

    /** The worker finished scoring a mutant. */
    data class Completed(val outcome: MutantOutcome) : WorkerMessage

    /**
     * The worker hit an unrecoverable wiring problem (e.g. a covering test id
     * that resolves to no runnable test) and is exiting. Terminal for the run —
     * the controller aborts rather than mis-scoring.
     */
    data class Fatal(val message: String) : WorkerMessage

    fun encode(): String {
        val payload = when (this) {
            Ready -> "READY"
            is Started -> "START $mutantId"
            is Completed -> "RESULT " + WorkerProtocol.json.encodeToString(MutantOutcome.serializer(), outcome)
            is Fatal -> "FATAL " + WorkerProtocol.json.encodeToString(String.serializer(), message)
        }
        return "${WorkerProtocol.MARKER} $payload"
    }

    companion object {
        /** Parse one worker stdout line, or `null` if it is not a protocol line. */
        fun parse(line: String): WorkerMessage? {
            val prefix = "${WorkerProtocol.MARKER} "
            val trimmed = line.trim()
            if (!trimmed.startsWith(prefix)) return null
            val body = trimmed.substring(prefix.length)
            val verb = body.substringBefore(' ')
            val rest = body.substringAfter(' ', "")
            return when (verb) {
                "READY" -> Ready
                "START" -> Started(rest)
                "RESULT" -> Completed(WorkerProtocol.json.decodeFromString(MutantOutcome.serializer(), rest))
                "FATAL" -> Fatal(WorkerProtocol.json.decodeFromString(String.serializer(), rest))
                else -> null
            }
        }
    }
}
