package io.komust.runtime

/**
 * The runtime switch seam for **compile-once, runtime-switchable** mutation.
 *
 * A single compiled artifact carries *every* mutant, each guarded by a call to
 * [mutantActive]. At most one mutant (or none) is switched on at a time, so each
 * mutant is measured independently against its covering tests.
 *
 * ## Why this is an interface (the documented seam)
 *
 * The #2 IR spike switched mutants with a **thread-local** slot, so a shared JVM
 * could run different mutants on different threads. ADR-0003 supersedes that: the
 * execution engine forks a **worker-JVM pool** and each worker runs exactly one
 * mutant at a time, so the disambiguation a thread-local slot bought is no longer
 * needed — and a thread-local slot has a latent bug (a mutant is inactive on any
 * thread the code under test spawns, silently under-killing concurrent code).
 * v1 therefore switches on a **process-global `@Volatile` single slot**
 * ([ProcessGlobalMutantSwitch]).
 *
 * The slot lives behind this interface so a thread-scoped implementation can
 * return unchanged if in-JVM parallel mutants are ever revived — swap the
 * installed switch via [MutantRegistry.installSwitch], nothing else moves.
 */
public interface MutantSwitch {

    /** The mutant id currently switched on, or `null` at the green baseline. */
    public fun activeMutantId(): String?

    /**
     * Switch on exactly one mutant. Passing `null` (or calling [clear]) returns
     * to the **green baseline** — the original, unmutated behaviour.
     */
    public fun activate(id: String?)

    /** Return to the green baseline (no mutant active). */
    public fun clear(): Unit = activate(null)

    /** Whether [id] is the mutant currently switched on. */
    public fun isActive(id: String): Boolean = activeMutantId() == id
}

/**
 * The v1 switch: a **process-global single slot**, `@Volatile` so a write on the
 * engine's harness thread is seen by the code under test wherever it runs —
 * including threads and dispatchers that code spawns (ADR-0003).
 */
public object ProcessGlobalMutantSwitch : MutantSwitch {

    @Volatile
    private var active: String? = null

    override fun activeMutantId(): String? = active

    override fun activate(id: String?) {
        active = id
    }
}
