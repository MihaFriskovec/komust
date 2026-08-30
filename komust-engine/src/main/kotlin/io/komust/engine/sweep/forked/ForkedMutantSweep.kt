package io.komust.engine.sweep.forked

import io.komust.engine.coverage.CoveragePassResult
import io.komust.engine.coverage.TestId
import io.komust.engine.sweep.CoveringTestSelection
import io.komust.engine.sweep.Mutant
import io.komust.engine.sweep.MutantResult
import io.komust.engine.sweep.MutantSweep
import io.komust.engine.sweep.SweepConfig
import io.komust.engine.sweep.SweepResult
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The **parallel mutant sweep** (ADR-0003, issue #34): a controller that owns the
 * authoritative work queue and drives a pool of forked worker JVMs.
 *
 * Same contract as the sequential [MutantSweep] — `sweep(mutants)` returns a
 * [SweepResult] with one [MutantResult] per mutant, in the order supplied — with
 * the robustness only a process boundary buys:
 *
 *  - **Work-stealing.** Each worker pulls one mutant at a time; a fast worker is
 *    never blocked behind a slow one.
 *  - **State isolation.** Each mutant runs against freshly reloaded classes in
 *    its worker (singletons / static caches / temp-file handles start clean), and
 *    workers never share a heap.
 *  - **Hang kill.** A non-terminating mutant overruns its baseline-relative
 *    budget; the worker scores it `TIMEOUT` and recycles itself; the controller
 *    respawns a replacement that resumes the queue.
 *  - **Memory-error recovery.** An OOM / `StackOverflowError` mutant is scored
 *    `KILLED`; the worker (its heap now suspect) recycles and is respawned.
 *  - **Heartbeat watchdog.** Any worker silent past `budget + grace` is
 *    force-killed; its in-flight mutant is scored `TIMEOUT` (if it had started)
 *    or requeued (if it had not).
 *
 * `NO_COVERAGE` mutants are scored by the controller without a worker ever seeing
 * them (there is nothing to run). Selection and fastest-first ordering are shared
 * verbatim with the sequential sweep ([CoveringTestSelection]).
 *
 * @constructor internal — the [WorkerLauncher] seam. Production callers use
 *   [forking]; tests substitute an in-memory launcher.
 */
public class ForkedMutantSweep internal constructor(
    private val coveragePass: CoveragePassResult,
    private val launcher: WorkerLauncher,
    private val config: SweepConfig,
    private val clock: () -> Long,
    private val watchdogInterval: Duration,
) {

    public companion object {

        /**
         * A [ForkedMutantSweep] that forks real worker JVMs.
         *
         * @param workerClasspath everything a worker needs on its class path (the
         *   engine, JUnit Platform + a Jupiter engine, the `io.komust.runtime`
         *   switch, Kotlin stdlib) **plus** [reloadableRoots].
         * @param reloadableRoots the code-under-test + test-class output dirs the
         *   worker reloads per mutant for state isolation.
         * @param jvmArgs extra worker JVM flags (heap sizing, `-javaagent`, …).
         */
        public fun forking(
            coveragePass: CoveragePassResult,
            workerClasspath: List<Path>,
            reloadableRoots: List<Path>,
            config: SweepConfig = SweepConfig(),
            jvmArgs: List<String> = emptyList(),
        ): ForkedMutantSweep = ForkedMutantSweep(
            coveragePass = coveragePass,
            launcher = ProcessWorkerLauncher(workerClasspath, reloadableRoots, jvmArgs),
            config = config,
            clock = System::nanoTime,
            watchdogInterval = 250.milliseconds,
        )
    }

    private data class MutantPlan(val mutant: Mutant, val orderedTests: List<TestId>, val work: WorkItem)

    private data class Assignment(val mutantId: String, val started: Boolean)

    /** The slice of an assignment the watchdog thread reads — kept immutable in a concurrent map. */
    private data class WatchState(val mutantId: String, val deadlineNanos: Long)

    private sealed interface Tick {
        data class FromWorker(val envelope: WorkerEnvelope) : Tick
        data class WatchdogExpired(val workerId: Int, val mutantId: String) : Tick
    }

    /** Score every mutant in [mutants] across the worker pool, returning results in the order given. */
    public fun sweep(mutants: List<Mutant>): SweepResult {
        val plans = mutants.associate { m ->
            val ordered = CoveringTestSelection.select(coveragePass, m)
            m.id to MutantPlan(m, ordered, workItemFor(m.id, ordered))
        }
        val run = Run(plans)
        run.execute()
        return SweepResult(mutants.map { run.results.getValue(it.id) })
    }

    private fun workItemFor(mutantId: String, ordered: List<TestId>) = WorkItem(
        mutantId = mutantId,
        tests = ordered.map { CoveringTestSpec(it.uniqueId, config.timeoutPolicy.budgetFor(coveragePass.timing(it)).inWholeMilliseconds.coerceAtLeast(1)) },
    )

    /** One `sweep(...)` call's mutable state and event loop. Confined to the calling thread bar [deadlines]. */
    private inner class Run(private val plans: Map<String, MutantPlan>) {

        val results: MutableMap<String, MutantResult> = HashMap()

        private val pending = ArrayDeque(plans.values.filter { it.orderedTests.isNotEmpty() }.map { it.work })
        private val ticks = LinkedBlockingQueue<Tick>()
        private val handles = HashMap<Int, WorkerHandle>()
        private val assignment = HashMap<Int, Assignment>()

        /** workerId → its current [WatchState]; the only state shared with the watchdog thread. */
        private val watch = ConcurrentHashMap<Int, WatchState>()

        private var nextWorkerId = 0
        private var launches = 0
        private val maxLaunches = config.workerCount + plans.size * 2 + 1
        private var abort: String? = null

        private val stallTimeout: Duration =
            (maxItemBudget() + config.watchdogGrace) * 3 + 60.seconds

        fun execute() {
            // Mutants with no covering test never reach a worker.
            plans.values.filter { it.orderedTests.isEmpty() }
                .forEach { results[it.mutant.id] = MutantResult.noCoverage(it.mutant) }
            if (results.size == plans.size) return

            val watchdog = startWatchdog()
            try {
                repeat(minOf(config.workerCount, pending.size)) { launchWorker() }
                loop()
            } finally {
                watchdog.interrupt()
                handles.values.forEach { runCatching { it.kill() } }
            }
            abort?.let { throw ForkedSweepException(it) }
        }

        private fun loop() {
            while (results.size < plans.size && abort == null) {
                val tick = ticks.poll(stallTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    ?: run { abort = "no worker made progress for $stallTimeout — the sweep is wedged"; return }
                when (tick) {
                    is Tick.FromWorker -> onWorkerEvent(tick.envelope.workerId, tick.envelope.event)
                    is Tick.WatchdogExpired -> onWatchdogExpired(tick.workerId, tick.mutantId)
                }
            }
        }

        private fun onWorkerEvent(workerId: Int, event: WorkerEvent) {
            when (event) {
                is WorkerEvent.Message -> when (val message = event.message) {
                    WorkerMessage.Ready -> Unit
                    is WorkerMessage.Started -> assignment[workerId]?.let {
                        assignment[workerId] = it.copy(started = true)
                        plans[message.mutantId]?.let { p ->
                            watch[workerId] = WatchState(message.mutantId, clock() + budgetNanos(p.work))
                        }
                    }
                    is WorkerMessage.Completed -> {
                        record(message.outcome)
                        assignment.remove(workerId)
                        watch.remove(workerId)
                        // A recycling worker self-exits; wait for Exited to respawn. Otherwise reuse it.
                        if (!message.outcome.requiresWorkerRecycle) pump(workerId)
                    }
                    is WorkerMessage.Fatal -> abort = message.message
                }

                is WorkerEvent.Exited -> {
                    handles.remove(workerId)
                    watch.remove(workerId)
                    assignment.remove(workerId)?.let { a ->
                        if (a.mutantId !in results) {
                            // START seen but no RESULT before death -> the hang (ADR-0003); else it never ran.
                            if (a.started) recordTimeout(a.mutantId) else requeue(a.mutantId)
                        }
                    }
                    relaunchIfWorkRemains()
                }
            }
        }

        private fun onWatchdogExpired(workerId: Int, mutantId: String) {
            val a = assignment[workerId] ?: return
            if (a.mutantId != mutantId || a.mutantId in results) return
            handles[workerId]?.let { runCatching { it.kill() } }
            if (a.started) recordTimeout(a.mutantId) else requeue(a.mutantId)
            assignment.remove(workerId)
            watch.remove(workerId)
            // The killed process's Exited event follows and triggers the respawn.
        }

        private fun launchWorker() {
            val id = nextWorkerId++
            launches++
            handles[id] = launcher.launch(id) { ticks.put(Tick.FromWorker(it)) }
            pump(id)
        }

        private fun relaunchIfWorkRemains() {
            if (pending.isEmpty() || results.size == plans.size) return
            if (launches >= maxLaunches) {
                abort = "workers are dying faster than they can be replaced ($launches launches) — aborting"
                return
            }
            launchWorker()
        }

        /** Hand [workerId] the next queued item, or close its input if the queue is empty. */
        private fun pump(workerId: Int) {
            val item = pending.removeFirstOrNull()
            val handle = handles[workerId] ?: return
            if (item == null) {
                assignment.remove(workerId)
                watch.remove(workerId)
                runCatching { handle.endInput() }
                return
            }
            assignment[workerId] = Assignment(item.mutantId, started = false)
            watch[workerId] = WatchState(item.mutantId, clock() + budgetNanos(item))
            handle.submit(item)
        }

        private fun requeue(mutantId: String) {
            plans[mutantId]?.let { pending.addFirst(it.work) }
        }

        private fun record(outcome: MutantOutcome) {
            if (outcome.mutantId in results) return
            val plan = plans.getValue(outcome.mutantId)
            results[outcome.mutantId] = when (outcome.status) {
                MutantOutcome.Status.SURVIVED -> MutantResult.survived(plan.mutant, plan.orderedTests)
                MutantOutcome.Status.TIMEOUT ->
                    MutantResult.timedOut(plan.mutant, plan.orderedTests, outcome.testsExecuted)
                MutantOutcome.Status.KILLED -> MutantResult.killed(
                    plan.mutant,
                    plan.orderedTests,
                    killedBy = outcome.killedByUniqueId?.let(::TestId),
                    testsExecuted = outcome.testsExecuted,
                )
            }
        }

        private fun recordTimeout(mutantId: String) {
            if (mutantId in results) return
            val plan = plans.getValue(mutantId)
            results[mutantId] = MutantResult.timedOut(plan.mutant, plan.orderedTests, testsExecuted = 0)
        }

        private fun budgetNanos(item: WorkItem): Long =
            (item.tests.sumOf { it.timeoutMillis }.milliseconds + config.watchdogGrace).inWholeNanoseconds

        private fun maxItemBudget(): Duration =
            (pending.maxOfOrNull { it.tests.sumOf { t -> t.timeoutMillis } } ?: 0L).milliseconds

        private fun startWatchdog(): Thread = thread(isDaemon = true, name = "komust-sweep-watchdog") {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(watchdogInterval.inWholeMilliseconds)
                    val now = clock()
                    watch.forEach { (workerId, state) ->
                        if (now > state.deadlineNanos) {
                            ticks.put(Tick.WatchdogExpired(workerId, state.mutantId))
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // shutting down
            }
        }
    }
}
