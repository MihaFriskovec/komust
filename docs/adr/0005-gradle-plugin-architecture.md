# 5. Gradle plugin architecture and the engine boundary

Date: 2026-08-29

## Status

Accepted

## Context

The map (#1) packages komust as a **core engine + thin Gradle plugin**, with a
CLI deferred but kept alive in the fog. This ADR fixes how the Gradle plugin
wraps the engine, so the boundary is drawn once and the deferred CLI can reuse
the engine without a rewrite. Several prior decisions bracket it:

- **#2 (IR core):** the mutation surface is a K2 `IrGenerationExtension` loaded
  as a Kotlin compiler plugin. Loading via `kotlinCompilerPluginClasspath` +
  ServiceLoader is proven; with no compile-time options no `CommandLineProcessor`
  is needed.
- **#6 (scope):** the plugin resolves a **Mutation Scope**, writes `scope.json`,
  and passes its path to the compiler plugin **as a compile-time option** — which
  reverses #2's no-options finding and now *requires* a `CommandLineProcessor`.
  #6 parked git scope resolution "in the Gradle plugin".
- **#7 (execution):** a controller + **forked-JVM worker pool** sweeps mutants
  over the compile-once artifact.
- **#8 (coverage/selection):** the coverage pass runs on the **mutant-injected
  build with mutants off** — one shared compile — and drives tests via JUnit
  Platform.
- **#5 (output):** `report.json` + `survivors.json` under `build/komust/`.

Open questions this ADR closes: what the artifact split is and where the
engine boundary falls; where orchestration runs relative to the Gradle daemon;
how the compiler plugin is injected and into which compilation; how tests are
driven; the task model; and the configuration DSL.

## Decision

### 1. Three artifacts, one agnostic engine

komust ships as **three** artifacts, not two:

- **`komust-compiler-plugin`** — K2 IR mutation surface (#2).
- **`komust-engine`** — build-tool-agnostic orchestrator. Consumes the **engine
  input contract** `(mutant-instrumented classes + main runtime classpath, test
  runtime classpath, resolved Mutation Scope, resolved config)` and runs
  coverage (ADR-0004) → sweep (ADR-0003) → JSON emit (#5). No Gradle APIs.
- **`komust-gradle-plugin`** — thin adapter: wires the compiler plugin in,
  invokes the scope resolver, discovers classpaths, exposes the DSL + task, then
  forks the engine.

Plus a shared **`komust-scope`** module (git diff → Mutation Scope → `scope.json`).
This *refines* #6: the Gradle plugin still drives scope resolution, but the logic
lives in a module the deferred CLI reuses verbatim, rather than being welded to
Gradle. It closes the CLI fog's known gap ("without Gradle it has no scope
resolver") by construction.

### 2. Orchestration runs in a forked engine JVM

The `mutationTest` task is a **launcher**: it forks a dedicated engine JVM
(JavaExec-style) handed a manifest (classpaths, `scope.json`, config). The
engine owns the controller + worker pool (#7). The controller does **not** run
in the Gradle daemon.

Rationale: daemon hygiene (a long, memory-heavy sweep that itself spawns worker
JVMs; classloader isolation from the user's build), and — decisively — the CLI
will invoke the **identical** engine entry point, so the fork *is* the agnostic
boundary made concrete. Cost: the input manifest is serialised across the
process boundary. Accepted.

### 3. Injection via the supported API, into a dedicated compilation

Registration uses the official **`KotlinCompilerPluginSupportPlugin`**
interface, whose `applyToCompilation` returns the `SubpluginOption`s — including
the `scope.json` path (#6) — rather than hand-appending `freeCompilerArgs`. This
is the version-tracking supported path; the scope option is what forces the
`CommandLineProcessor` #2 anticipated.

Mutants are built in a **dedicated mutation compilation** of the production
sources, output under `build/komust/`, applied **only** when a mutation run is
requested. The ordinary `compileKotlin` therefore never carries mutants and the
incremental cache is never poisoned. The coverage pass and the sweep both
observe this one artifact (ADR-0004's "one shared compile").

*Implementation risk to pin:* standing up a separate Kotlin compilation that
reuses the main sources (versus gating the main compilation) is the fiddly part
of the wiring and must be validated against the installed Kotlin version, which
churns (the #2 compat-shim seam applies here too).

### 4. Tests driven through the JUnit Platform Launcher API

The engine drives the **JUnit Platform Launcher API directly** inside its
workers, not Gradle's `Test` task (which cannot do runtime-switched, per-mutant
execution). The coverage/baseline pass is one full-suite Launcher run with the
JaCoCo agent + the #3 `TestExecutionListener`; per-mutant runs build a
`LauncherDiscoveryRequest` selecting the covering tests by `uniqueId` (#3's join
key), flip the process-global switch (#7), and run fastest-first / fail-fast.
The Gradle plugin's only test-side responsibility is supplying the **test
runtime classpath**. This keeps execution in the agnostic engine, identical
under the CLI.

### 5. One task, git-modified default, options for override

A single **`mutationTest`** task whose **default scope is the git diff** (#6),
with per-run Gradle `@Option`s selecting scope and overrides:

- `--all` — mutate the whole project (opt out of the modified-files default).
- `--since <ref>` — override the base ref.
- `--files <globs>` / `--scope <file.json>` — explicit Mutation Scope override (#6).
- `--tests <override>` — explicit test-selection override (#8).
- `--no-cache` — bypass the cross-run cache (#10).

Not two tasks: one task, scope chosen by options, matching #6's "git default +
explicit override" model. `mutationTest` depends on test compilation but is
**not** wired into `check` by default (opt-in), so ordinary CI is not slowed.

### 6. The `komust {}` configuration DSL

Stable policy lives in a property-based (config-cache-friendly) extension;
per-run scope/override lives in the task `@Option`s above.

```kotlin
komust {
    baseRef.set("origin/main")           // default git base (#6); --since overrides
    operators {
        experimental.set(false)          // opt-in tier off by default (#4)
        enable("elvis-default")          // per-operator on/off within tiers
        disable("increments")
    }
    output {
        humanReport.set(true)            // human report rendered from the JSON (#5)
        consoleSurvivorsOnly.set(true)   // token-dense survivor stream to console
    }
    workers.set(Runtime.getRuntime().availableProcessors())  // (#7)
    timeoutFactor.set(1.5)               // baseline-relative timeout (#7)
    cache.set(true)                      // cross-run cache (#10)
    sourceSets.set(listOf("main"))       // which Kotlin sourceSet(s) to mutate
}
```

Deliberately **not** configurable in v1: the skip-list (built-in plus the
`@SuppressMutations` / `// komust:ignore` hatch only, per #4) and the JSON
output paths (fixed under `build/komust/`, per #5).

## Consequences

- The engine is testable and shippable independent of Gradle, and the deferred
  CLI becomes an alternative adapter over the same engine + `komust-scope`, not a
  parallel implementation.
- A second process boundary (task → engine fork) and a serialised manifest are
  introduced; this is deliberate isolation, but it is a surface that must stay in
  sync with the engine's input contract.
- The dedicated mutation compilation is the load-bearing wiring risk and rides on
  the unstable Kotlin compiler-plugin API (#2); the compat-shim seam covers it.
- Nothing here reopens #2–#8/#10; it composes them into a runnable pipeline.
