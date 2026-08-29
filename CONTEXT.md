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
An operator whose inclusion is decided but whose clean expression in K2 IR is not yet proven by the IR prototype (#2). If the prototype finds it awkward, it is demoted to **experimental** or the skip-list rather than reopening the catalog decision.

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

### Explicit override (test selection)
A caller-supplied pinning of the test set, at **global** granularity (one set for the whole run) and/or **per-file** granularity. Where an override applies to a mutant it **fully replaces** the coverage-derived covering set (never augments it) — the same replace-not-merge stance as the Mutation Scope override. The coverage pass still runs regardless (the green baseline is non-negotiable); an overridden mutant simply skips the coverage lookup and can therefore never be `NO_COVERAGE`. See ADR-0004.
