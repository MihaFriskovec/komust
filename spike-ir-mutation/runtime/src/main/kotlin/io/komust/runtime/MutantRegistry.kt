package io.komust.runtime

/**
 * PROTOTYPE — the runtime switch for compile-once mutation.
 *
 * A single compiled artifact carries EVERY mutant, each guarded by a call to
 * [mutantActive]. Exactly one mutant (or none) is switched on per test run.
 *
 * Thread-local so a parallel test executor can run different mutants on
 * different threads against the same loaded classes — no recompile, no reload.
 */
object MutantRegistry {
    private val active = ThreadLocal.withInitial<String?> { null }

    /** Turn on exactly one mutant for the current thread (null = baseline/original). */
    fun activate(id: String?) { active.set(id) }

    fun clear() { active.set(null) }

    fun current(): String? = active.get()

    fun isActive(id: String): Boolean = active.get() == id
}

/** The function the compiler plugin injects a call to at each mutation point. */
fun mutantActive(id: String): Boolean = MutantRegistry.isActive(id)
