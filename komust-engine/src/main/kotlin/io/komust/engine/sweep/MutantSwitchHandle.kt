package io.komust.engine.sweep

/**
 * The engine side of the **runtime switch** (ADR-0003): the sweep switches
 * exactly one mutant on for its covering-test run, then back to the green
 * baseline.
 *
 * This is the reflective mirror, on the engine side of the artifact boundary, of
 * the runtime's own `io.komust.runtime.MutantSwitch` seam. The woven code reads
 * the slot through `io.komust.runtime.mutantActive(id)`; this handle writes it.
 * That runtime ships in `komust-compiler-plugin`, on the *woven code's* runtime
 * classpath (ADR-0003) — which `komust-engine` has no compile dependency on — so
 * [processGlobal] reaches `MutantRegistry` reflectively, through the same class
 * loader graph the sweep runs its tests in.
 *
 * A `fun interface` so the sweep is unit-testable against a recording fake.
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
         * @throws IllegalStateException if the runtime cannot be reached through
         *   [classLoader] — a wiring mistake worth failing loudly on, since
         *   every mutant would otherwise silently score SURVIVED.
         */
        public fun processGlobal(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
        ): MutantSwitchHandle {
            val (instance, activate) = try {
                val registry = Class.forName("io.komust.runtime.MutantRegistry", true, classLoader)
                registry.getField("INSTANCE").get(null) to registry.getMethod("activate", String::class.java)
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException(
                    "io.komust.runtime.MutantRegistry is not reachable from the class loader the sweep runs in — " +
                        "the komust-compiler-plugin runtime must be on the woven code's runtime classpath",
                    e,
                )
            }
            return MutantSwitchHandle { mutantId -> activate.invoke(instance, mutantId) }
        }
    }
}
