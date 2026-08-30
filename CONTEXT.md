# Context: komust

A Kotlin-native (Kotlin/JVM) mutation-testing tool — "pitest for Kotlin, built AI-native". This file is the glossary. Implementation decisions live in `docs/adr/`; effort planning lives on the GitHub issue tracker (the wayfinder map, #1).

## Glossary

### Incremental run
A run that reuses prior mutant outcomes from the **mutant-result cache** instead of re-executing every in-scope mutant, so the agent inner-loop (edit → re-run → read survivors) only pays for the mutants whose result could actually have changed. The opposite is a cold run, where every in-scope mutant is executed. Incrementality is an *optimisation over which mutants are executed*; it never changes which mutants are in the report — that is the **Mutation Scope**'s job. See ADR-0003.

### Mutant-result cache
The **best-effort** store of prior mutant execution outcomes, keyed by mutant `id`, reused across runs to skip re-executing a mutant whose determinants are unchanged. Best-effort means a miss is always safe: a cold, wiped, or version-skewed cache costs time, never correctness — the answer is always defined by *actually executing the mutant*. The cache holds only the **execution-derived** half of a mutant's result (its status and per-covering-test kill outcomes); everything a compile can rederive (location, operator, mutation description, summary) is regenerated fresh each run. See ADR-0003.

### Validity fingerprint
The per-mutant hash of the determinants whose change could flip a mutant's outcome: the **enclosing symbol**'s source, the content of each **covering test**, the operator/komust version, and the environment (Kotlin + JDK version). A cached outcome is reusable only when both the mutant `id` **and** its validity fingerprint match; any determinant change is a cache miss and re-executes the mutant. The fingerprint is what lets "the agent added one test" re-run only the affected mutants while every other symbol stays a cache hit. See ADR-0003.
### Mutant
A single, deliberate change to the program under test, produced by applying one **mutation operator** at one site. komust uses **compile-once, runtime-switchable** mutants: all mutants are woven into one compilation and selected at runtime by a guard, never recompiled per mutant.

### Mutation operator (operator)
A rule that rewrites a specific Kotlin construct in the K2 IR to produce mutants (e.g. swap `+`↔`-`). An operator targets a construct; it does not merely observe it. The set of operators komust ships is the **operator catalog**.

### Operator catalog
The versioned set of operators komust applies, split into **tiers**. See ADR-0001.

### Tier
An operator's default activation state. komust v1 has two: **default** (on unless disabled) and **experimental** (off unless explicitly enabled). There is no "stronger" middle tier.

### Killed mutant
A mutant for which at least one selected test fails — the test suite detected the change. The goal of a good test.

### Survived mutant
A mutant that no selected test detected (all tests still pass). A survivor is a candidate test-quality gap and is the primary signal komust reports to an agent.

### Junk mutant
A mutant that is worthless as signal: it fails to compile, always crashes regardless of test quality, or is otherwise uninformative. komust avoids junk **by construction** (the operator never emits it), not by post-hoc detection.

### Equivalent mutant
A mutant whose behaviour is indistinguishable from the original program, so **no** test can ever kill it. A false survivor: it looks like a gap but is not one. komust v1 does not detect equivalents; it avoids operators/sites known to generate them (the **skip-list**), the same stance as pitest.

### Skip-list
The documented policy of constructs komust deliberately does **not** mutate because doing so reliably produces junk or equivalent mutants (e.g. `!!`, `TODO()`, `require`/`check`, exhaustive `when` without `else`, and the null-checks that `?:`/`?.` desugar into). The skip-list is komust's equivalent-mutant firewall. See ADR-0001.

### Spike-gated (operator)
An operator whose inclusion is decided but whose clean expression in K2 IR is not yet proven by the IR prototype (#2). If the prototype finds it awkward, it is demoted to **experimental** or the skip-list rather than reopening the catalog decision. **Resolved (#29):** all three spike-gated operators — increments (`++↔--`), empty/default-return, per-call-site void-call removal — landed in the default tier; none demoted. See the addendum in ADR-0001.

### Suppression hatch
The user-facing escape valve for sites the built-in **skip-list** does not cover (#36): the `@SuppressMutations` annotation (`io.komust.runtime`, applied to a declaration or `@file:`) suppresses every mutation site inside it; the `// komust:ignore` comment does the same at single-line granularity (the marked line and the line below it). Distinct from the skip-list, which is fixed policy komust applies unprompted.

### Protected (construct)
A Kotlin construct that komust intentionally leaves untouched — it is on the skip-list. Distinct from "not yet supported": protection is a deliberate correctness choice. `?.`, `!!`, and desugared null-checks are protected.

### Modified-files run
komust's default mode: only code that changed relative to a base ref is mutated, not the whole project. Serves the agent inner-loop (mutate what I just edited) and PR review (mutate what this branch changed). The opposite — mutating the entire project — is available but never the default.

### Mutation Scope
The set of source locations a run considers for mutation, expressed as **file → line ranges** (a whole file is the range covering all its lines). Every input path produces one Mutation Scope: the git-derived changeset and any explicit override normalise to the same shape. It is an *input filter*, not a list of mutants — the operator catalog still decides what is mutable within it. See ADR-0002.

### Enclosing symbol
The nearest **member declaration** — function, property initializer, or `init` block — that contains a given source line. It is the unit a changed line **expands** to: when any line of a symbol is in scope, the whole symbol is in scope, because a one-line edit can shift the behaviour of its entire enclosing declaration. Lambdas and local functions belong to their host symbol, not their own. This keeps modified-files granularity at the function level — below the whole file, above the bare line.

### Coverage pass
A single, sequential run of the whole test suite over the **unmutated** program (all mutants inactive), instrumented by JaCoCo, that produces the **coverage index**. Run once per source snapshot and cached; it also serves as the mandatory **green baseline** (if it is not green, the run aborts). See ADR-0004.

### Coverage index
The `(binary class name, source line) → { test unique id }` map built from the coverage pass: for every covered source line, the set of JUnit Platform tests that executed it. The join surface between coverage and mutation. **Inline-function lines are normalised** when the index is built (callee declaration line ↔ call-site lines), so downstream lookups stay exact. See ADR-0004.

### Covering test set
For one mutant, the set of tests the coverage index maps to that mutant's `(class, line)` — the only tests that can possibly kill it, so the only tests komust runs against it. Absence of a covering test set is **not** a survivor; it is **no coverage**.

### Test selection
Choosing, per mutant, which tests to run. komust's default selection is **coverage-mapped**: the covering test set from the coverage index. An **explicit override** can replace it. Ordering *within* the selected set (fastest-first, fail-fast) is execution, not selection — see ADR-0003.

### No coverage (mutant outcome)
A mutant on a source line that **no test executes**. It is never run (there is nothing that could kill it) and is reported as `NO_COVERAGE`, distinct from a survivor. Because untested-yet-mutable code is a maximally actionable signal, `NO_COVERAGE` mutants are surfaced in the token-dense agent stream as their own category, not folded into survivors.

### Mutant sweep
The stage that scores every in-scope mutant. For each mutant it switches that one mutant on via the **runtime switch**, selects its **covering test set** by a direct `(binary class name, source line)` lookup into the **coverage index**, runs those tests **fastest-first** (baseline timing) with **fail-fast** (the first failing test ends the mutant as `KILLED`; all passing is `SURVIVED`), and switches back to the **green baseline**. A mutant with no covering test is `NO_COVERAGE` and is never run. The **sequential sweep** (`komust-engine`, #33) is the single-worker in-process core; the parallel **controller + forked worker pool** with hang-kills, `TIMEOUT`, and cross-mutant state isolation (#34) is layered on top of it. See ADR-0003.

### Explicit override (test selection)
A caller-supplied pinning of the test set, at **global** granularity (one set for the whole run) and/or **per-file** granularity. Where an override applies to a mutant it **fully replaces** the coverage-derived covering set (never augments it) — the same replace-not-merge stance as the Mutation Scope override. The coverage pass still runs regardless (the green baseline is non-negotiable); an overridden mutant simply skips the coverage lookup and can therefore never be `NO_COVERAGE`. See ADR-0004.

### Compiler plugin
The **K2 IR** artifact (`komust-compiler-plugin`) that weaves mutants into a Kotlin compilation and injects the runtime guard (#2). It is the **mutation surface** — the only component that understands Kotlin IR — and is loaded into a Kotlin compile as a Kotlin compiler plugin. It knows nothing about test running, coverage, or the build tool.

### Runtime switch
The process-global mechanism that selects which **mutant** is live in a given test run. A single `@Volatile` slot holds at most one active mutant `id` (or none — the **green baseline**); the compiler plugin injects a call to the guard `io.komust.runtime.mutantActive(id)` at every mutation point, and the execution engine flips the slot per mutant. It is **process-global, not thread-local** (ADR-0003, superseding the #2 spike): a thread-local slot silently ran the original on any thread the code under test spawned, under-killing concurrent code. The slot sits behind the small `MutantSwitch` interface (a documented seam) so a thread-scoped implementation could return if in-JVM parallel mutants are ever revived. Ships in the `komust-compiler-plugin` artifact (the guard must be on the woven code's runtime classpath); the module's `compileOnly` compiler dependency keeps that jar free of compiler code.

### Compat-shim seam
The **single file** in `komust-compiler-plugin` (`io.komust.compiler.ir.KotlinIrCompat`) through which *all* Kotlin-version-specific K2 compiler/IR API is touched — the standing mitigation for that API being officially unstable (`@ExperimentalCompilerApi`, `@UnsafeDuringIrConstructionAPI`, and IR construction churning every Kotlin release). Paired with the **exact Kotlin version pin** in the version catalog: a Kotlin bump is a deliberate, reviewed change whose blast radius is one file. Elsewhere in the module an `org.jetbrains.kotlin.*` import is allowed **only** to declare an SPI entry point the class cannot exist without — the `CompilerPluginRegistrar` / `CommandLineProcessor` it subclasses, the `IrGenerationExtension` it implements, and the types in those overridden signatures. Every other contact with the compiler/IR API (message reporting, extension registration, IR traversal, IR construction, symbol resolution) adds a narrow, intent-named helper to the seam instead. See ADR-0005.

### Core engine
The build-tool-agnostic orchestrator (`komust-engine`) that, given the **engine input contract**, runs the coverage pass (ADR-0004), the mutant sweep (ADR-0003), and emits the JSON output (agent contract, #5). It is the reusable heart of komust: the Gradle plugin and the deferred CLI both drive the *same* engine. It never touches Gradle APIs, and it drives test execution through the **JUnit Platform Launcher API** directly (not any build tool's test runner). See ADR-0005.

### Engine input contract
The build-tool-agnostic handoff the core engine consumes: the mutant-instrumented classes plus main runtime classpath, the test runtime classpath, the resolved **Mutation Scope**, and the resolved run configuration. Any adapter (the Gradle plugin today, the CLI later) that can produce this contract can drive the engine. See ADR-0005.

### Gradle plugin
The thin adapter (`komust-gradle-plugin`) that makes komust usable from a Gradle build: it wires the compiler plugin into a **mutation compilation**, invokes the **scope resolver**, discovers classpaths from the Gradle model, exposes the `komust {}` DSL and the `mutationTest` task, and then forks the core engine with the engine input contract. It contains no mutation, coverage, or execution logic — those live in the engine. See ADR-0005.

### Scope resolver
The shared module (`komust-scope`) that turns a git diff (or an explicit override) into a **Mutation Scope** and writes `scope.json`. Factored out of the Gradle plugin so the deferred CLI reuses the identical git→scope logic rather than reimplementing it (refines #6's placement without changing its behaviour). See ADR-0005.

### Mutation compilation
A **dedicated** Kotlin compilation of the project's production sources with the compiler plugin applied, producing the mutant-instrumented classes under `build/komust/`. Kept separate from the ordinary `compileKotlin` so a normal build never carries mutants and the incremental cache is never poisoned. The single artifact both the coverage pass (mutants off) and the sweep (mutants on) observe — the "one shared compile" of ADR-0004. See ADR-0005.

### Output contract
The agent-facing JSON komust emits under `build/komust/` (#5): the lossless canonical **`report.json`** (every mutant + run metadata; the source of truth) and the token-dense **`survivors.json`** projection (only the actionable outcomes — **survivors** and **no coverage**, as two disjoint categories — sized for an agent's context window). Each survivor record carries structured facts (content-hash `id`, `location`, `operator`, `original→mutated`, `enclosingSymbol`, covering-but-not-killing test `uniqueId`s) **plus** a rendered `summary` that reads as a "write a test that does X" instruction. `report.txt` (the human report) renders **from** `report.json`, never from run state, so the two can never disagree. Status enum `SURVIVED | KILLED | NO_COVERAGE | TIMEOUT`; deterministic `(path, line, id)` sort across both files; semver `schemaVersion` with an in-repo JSON Schema (`schema/`), additive-only within a major. Mutation-**score** reporting (and thresholds / CI gating) is out of v1 — `report.json` carries the killed/survived/no-coverage/timeout counts, not a score. The format contract is `docs/report-json.md`.
