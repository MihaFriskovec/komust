package io.komust.runtime

/**
 * The single entry point both the **woven code** (via [mutantActive]) and the
 * **execution engine** (via [activate] / [clear]) use to reach the current
 * [MutantSwitch].
 *
 * It holds one installed switch, defaulting to [ProcessGlobalMutantSwitch]. The
 * indirection is the seam ADR-0003 calls for: a future thread-scoped slot is a
 * one-line [installSwitch] call, with every caller and every woven guard
 * untouched.
 */
public object MutantRegistry {

    private val DEFAULT: MutantSwitch get() = ProcessGlobalMutantSwitch

    @Volatile
    private var switch: MutantSwitch = DEFAULT

    /** The currently installed switch. */
    public fun switch(): MutantSwitch = switch

    /**
     * Replace the installed switch. The seam for tests and for a future
     * thread-scoped slot; production code never calls this.
     */
    public fun installSwitch(replacement: MutantSwitch) {
        switch = replacement
    }

    /** Restore the default [ProcessGlobalMutantSwitch]. */
    public fun resetSwitch() {
        switch = DEFAULT
    }

    /** Switch on exactly one mutant (`null` = green baseline). */
    public fun activate(id: String?): Unit = switch.activate(id)

    /** Return to the green baseline (no mutant active). */
    public fun clear(): Unit = switch.clear()

    /** The mutant id currently switched on, or `null` at the green baseline. */
    public fun current(): String? = switch.activeMutantId()

    /** Whether [id] is the mutant currently switched on. */
    public fun isActive(id: String): Boolean = switch.isActive(id)
}

/**
 * The guard the compiler plugin injects a call to at every mutation point:
 *
 * ```
 * if (mutantActive("<id>")) a - b else a + b
 * ```
 *
 * Kept as a top-level function so the injected IR is a plain static call with a
 * single string argument — the smallest possible woven footprint.
 */
public fun mutantActive(id: String): Boolean = MutantRegistry.isActive(id)
