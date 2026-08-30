package io.komust.engine.sweep

/**
 * The engine side of the **runtime switch** (ADR-0003): the sweep switches
 * exactly one mutant on for the duration of its covering-test run, then back to
 * the green baseline.
 *
 * The woven code reads the slot through `io.komust.runtime.mutantActive(id)`;
 * this handle writes it. That runtime lives in the `komust-compiler-plugin`
 * artifact on the *woven code's* runtime classpath (ADR-0003), which the engine
 * has no compile dependency on — so [processGlobal] reaches
 * `io.komust.runtime.MutantRegistry` reflectively, through the same class loader
 * graph the sweep runs its tests in.
 *
 * A seam so the sweep is unit-testable against a recording fake, and so a
 * thread-scoped slot could be substituted if in-JVM parallel mutants are ever
 * revived (ADR-0003).
 */
public fun interface MutantSwitchHandle {

    /** Switch on [mutantId], or pass `null` to return to the green baseline. */
    public fun activate(mutantId: String?)

    /** Return to the green baseline — no mutant active. */
    public fun clear(): Unit = activate(null)

    public companion object {

        /**
         * A handle over the process-global switch
         * (`io.komust.runtime.MutantRegistry`), resolved from [classLoader]
         * (default: this thread's context class loader — the loader graph the
         * woven classes and the runtime are loaded from during a sweep).
         *
         * @throws IllegalStateException if the runtime is not reachable from
         *   [classLoader] — a wiring mistake worth failing loudly on, since
         *   every mutant would otherwise silently score SURVIVED.
         */
        public fun processGlobal(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
        ): MutantSwitchHandle {
            val registry = try {
                Class.forName("io.komust.runtime.MutantRegistry", true, classLoader)
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException(
                    "io.komust.runtime.MutantRegistry is not on the class loader graph the sweep runs in — " +
                        "the komust-compiler-plugin runtime must be on the woven code's runtime classpath",
                    e,
                )
            }
            val instance = registry.getField("INSTANCE").get(null)
            val activate = registry.getMethod("activate", String::class.java)
            return MutantSwitchHandle { mutantId -> activate.invoke(instance, mutantId) }
        }
    }
}
