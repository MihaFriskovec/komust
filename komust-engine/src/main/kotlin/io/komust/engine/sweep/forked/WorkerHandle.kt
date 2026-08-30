package io.komust.engine.sweep.forked

/**
 * A live worker the controller can feed and kill. The seam between
 * [ForkedMutantSweep] and a real forked JVM ([ProcessWorkerLauncher]) — a test
 * substitutes an in-memory fake to drive the controller's queue / requeue /
 * watchdog logic without spawning processes.
 */
internal interface WorkerHandle {

    /** This worker's controller-assigned id (stable across the run; a respawn gets a new one). */
    val id: Int

    /** Hand the worker one [WorkItem] (a line of JSON on its stdin). */
    fun submit(item: WorkItem)

    /** No more work: close the worker's stdin so it exits cleanly. */
    fun endInput()

    /** Force-kill the worker process now (a wedged worker the watchdog gave up on). */
    fun kill()
}

/** Something a worker did, delivered to the controller's single event queue. */
internal sealed interface WorkerEvent {

    /** The worker emitted a protocol [WorkerMessage] on stdout. */
    data class Message(val message: WorkerMessage) : WorkerEvent

    /** The worker process exited with [code] (its stdout is now closed). */
    data class Exited(val code: Int) : WorkerEvent
}

/** A [WorkerEvent] tagged with the worker it came from. */
internal data class WorkerEnvelope(val workerId: Int, val event: WorkerEvent)

/**
 * Launches workers. Every [WorkerEvent] a launched worker produces — each stdout
 * [WorkerMessage] and the terminal [WorkerEvent.Exited] — must be delivered to
 * [sink] as a [WorkerEnvelope]. `sink` is safe to call from any thread.
 */
internal fun interface WorkerLauncher {
    fun launch(id: Int, sink: (WorkerEnvelope) -> Unit): WorkerHandle
}
