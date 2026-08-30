package io.komust.engine.sweep.forked

/**
 * The forked sweep could not produce a trustworthy [io.komust.engine.sweep.SweepResult]
 * and aborted rather than report a guessed score.
 *
 * Terminal, like the coverage package's exceptions: a covering test id that
 * resolves to nothing (suite drift), or workers dying faster than the controller
 * can replace them, means the run's numbers cannot be believed.
 */
public class ForkedSweepException internal constructor(message: String) : RuntimeException(message)
