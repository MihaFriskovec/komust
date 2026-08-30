package io.komust.engine.sweep.forked

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What a faked worker should do with the n-th attempt at a given mutant. */
internal sealed interface WorkerScript {
    /** Emit `START` then `RESULT` with [outcome]; stay alive for the next item. */
    data class Complete(val outcome: MutantOutcome) : WorkerScript

    /** Emit `START` and then go silent forever — the controller watchdog must step in. */
    data object HangAfterStart : WorkerScript

    /** Emit `START`, then die (exit [code]) without a `RESULT`. */
    data class DieAfterStart(val code: Int) : WorkerScript

    /** Die (exit [code]) before emitting `START` at all. */
    data class DieBeforeStart(val code: Int) : WorkerScript

    /** Emit `START`, `RESULT` with [outcome], then self-exit ([code]) — the self-recycle path. */
    data class RecycleThenExit(val outcome: MutantOutcome, val code: Int) : WorkerScript
}

/**
 * An in-memory [WorkerLauncher] for exercising [ForkedMutantSweep]'s queue,
 * requeue, respawn and watchdog logic with no forked processes. Each worker runs
 * its scripted behaviour on a single-thread executor so events reach the
 * controller asynchronously, as they would from a real process.
 */
internal class FakeWorkerLauncher(
    private val script: (mutantId: String, attempt: Int) -> WorkerScript,
) : WorkerLauncher {

    val launchCount = AtomicInteger()
    val submittedMutants = CopyOnWriteArrayList<String>()
    val killedWorkerIds = CopyOnWriteArrayList<Int>()
    private val attempts = ConcurrentHashMap<String, Int>()

    override fun launch(id: Int, sink: (WorkerEnvelope) -> Unit): WorkerHandle {
        launchCount.incrementAndGet()
        return Handle(id, sink)
    }

    private inner class Handle(override val id: Int, private val sink: (WorkerEnvelope) -> Unit) : WorkerHandle {
        private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-worker-$id").apply { isDaemon = true } }
        private val finished = AtomicBoolean(false)

        private fun message(m: WorkerMessage) = sink(WorkerEnvelope(id, WorkerEvent.Message(m)))

        private fun exit(code: Int) {
            if (finished.compareAndSet(false, true)) {
                sink(WorkerEnvelope(id, WorkerEvent.Exited(code)))
                exec.shutdown()
            }
        }

        override fun submit(item: WorkItem) {
            submittedMutants.add(item.mutantId)
            val attempt = attempts.merge(item.mutantId, 1, Int::plus)!!
            exec.execute {
                when (val s = script(item.mutantId, attempt)) {
                    is WorkerScript.Complete -> {
                        message(WorkerMessage.Started(item.mutantId))
                        message(WorkerMessage.Completed(s.outcome))
                    }
                    WorkerScript.HangAfterStart -> message(WorkerMessage.Started(item.mutantId))
                    is WorkerScript.DieAfterStart -> {
                        message(WorkerMessage.Started(item.mutantId))
                        exit(s.code)
                    }
                    is WorkerScript.DieBeforeStart -> exit(s.code)
                    is WorkerScript.RecycleThenExit -> {
                        message(WorkerMessage.Started(item.mutantId))
                        message(WorkerMessage.Completed(s.outcome))
                        exit(s.code)
                    }
                }
            }
        }

        override fun endInput() = exit(0)

        override fun kill() {
            killedWorkerIds.add(id)
            exit(137)
        }
    }
}
