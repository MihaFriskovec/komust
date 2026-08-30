package io.komust.engine.sweep

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tunables for the forked mutant sweep ([ForkedMutantSweep], #34).
 *
 * @property workerCount how many worker JVMs the controller forks. Defaults to
 *   one per available processor (ADR-0003 §Isolation architecture). Clamped to
 *   the mutant count at run time — forking more workers than there is work to do
 *   only wastes JVM startups.
 * @property timeoutPolicy the per-covering-test [TimeoutPolicy].
 * @property watchdogGrace how long past a mutant's whole timeout budget the
 *   controller-side heartbeat watchdog waits before force-killing a silent
 *   worker (ADR-0003 §Hang detection: "silent beyond `timeout + grace`"). The
 *   worker recycles itself on its own timeout well before this fires; the
 *   watchdog is the backstop for a worker that is wedged hard enough that it
 *   cannot even report.
 */
public data class SweepConfig(
    val workerCount: Int = defaultWorkerCount(),
    val timeoutPolicy: TimeoutPolicy = TimeoutPolicy(),
    val watchdogGrace: Duration = 20.seconds,
) {
    init {
        require(workerCount >= 1) { "workerCount must be >= 1, was $workerCount" }
        require(watchdogGrace > Duration.ZERO) { "watchdogGrace must be > 0, was $watchdogGrace" }
    }

    public companion object {
        /** One worker per available processor (ADR-0003). */
        public fun defaultWorkerCount(): Int = Runtime.getRuntime().availableProcessors()
    }
}
