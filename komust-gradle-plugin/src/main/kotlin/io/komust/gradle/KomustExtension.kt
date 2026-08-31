package io.komust.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

/**
 * The `komust {}` configuration DSL (ADR-0005 §6).
 *
 * **Stable policy only** — every value here is a lazy [Property] / [SetProperty]
 * so the extension is config-cache-friendly. Per-run scope and overrides
 * (`--all`, `--since`, `--files`, `--scope`, `--tests`, `--no-cache`) are task
 * `@Option`s on `mutationTest`, not DSL.
 *
 * Deliberately **not** configurable in v1: the skip-list (built-in plus the
 * `@SuppressMutations` / `// komust:ignore` hatch, #4) and the JSON output paths
 * (fixed under `build/komust/`, #5). [sourceSets] is reserved — v1 always mutates
 * `main` and warns if it is set to anything else.
 */
public abstract class KomustExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Pins the git base ref for the modified-files scope (#6), like a persistent
     * `--since`. Unset (the default), the scope resolver auto-detects the default
     * branch. `--since` overrides it per run.
     */
    public abstract val baseRef: Property<String>

    /** Forked engine-worker count for the sweep (#7). Defaults to the available processors. */
    public abstract val workers: Property<Int>

    /** Baseline-relative per-test timeout multiplier (#7). */
    public abstract val timeoutFactor: Property<Double>

    /** Use the cross-run mutant-result cache (#10); `--no-cache` bypasses it per run. */
    public abstract val cache: Property<Boolean>

    /**
     * Which Kotlin source set(s) to mutate. Reserved for a later release — v1
     * always mutates `main` and logs a warning if this is set to anything else.
     */
    public abstract val sourceSets: ListProperty<String>

    /** Operator tier + per-operator enable/disable (ADR-0001, #4). */
    public val operators: Operators = objects.newInstance(Operators::class.java)

    /** Report / console output policy (#5). */
    public val output: Output = objects.newInstance(Output::class.java)

    public fun operators(action: Action<Operators>): Unit = action.execute(operators)

    public fun output(action: Action<Output>): Unit = action.execute(output)

    /** `operators { experimental / enable / disable }`. */
    public abstract class Operators {
        /** Turn the experimental operator tier on (off by default, #4). */
        public abstract val experimental: Property<Boolean>

        /** Operator slugs to force on (the opt-in path for experimental-tier operators). */
        public abstract val enabled: SetProperty<String>

        /** Operator slugs to turn off within their tier. */
        public abstract val disabled: SetProperty<String>

        public fun enable(vararg slugs: String) {
            enabled.addAll(slugs.toList())
        }

        public fun disable(vararg slugs: String) {
            disabled.addAll(slugs.toList())
        }
    }

    /** `output { humanReport / consoleSurvivorsOnly }`. */
    public abstract class Output {
        /** Render `build/komust/report.txt` from `report.json` (#5). */
        public abstract val humanReport: Property<Boolean>

        /** Print the token-dense survivor stream to the console. */
        public abstract val consoleSurvivorsOnly: Property<Boolean>
    }

    internal companion object {
        const val NAME: String = "komust"
    }
}
