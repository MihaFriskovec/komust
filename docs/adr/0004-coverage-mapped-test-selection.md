# 4. Coverage-mapped test selection and explicit override

Date: 2026-08-29

## Status

Accepted

## Context

komust runs each mutant against **only the tests that can kill it** (map #1;
execution model #7). That requires an algorithm to go from a mutant's source
location to a test set, plus an escape hatch for callers who want to pin the
tests themselves. Two proven pieces bracket this decision:

- **#2 (IR mutation core):** each mutant is keyed `(file, line, col, operator,
  ordinal)`; the injecting `IrGenerationExtension` also knows the enclosing IR
  declaration, hence its **binary class name**. All mutants are woven into one
  compile and switched at runtime.
- **#3 (per-test coverage):** a JaCoCo agent + JUnit `TestExecutionListener`
  yields a `(binary class name, source line) → { test unique id }` index in one
  sequential pass, cached per snapshot. Inline functions attribute covered
  lines to the callee declaration site, not the call sites — the sharpest join
  risk.

Open questions this ADR closes: which compiled artifact the coverage pass
observes; how a mutant joins to the coverage index; what happens to mutants no
test covers; and the semantics of an explicit override.

## Decision

### 1. One shared compiled artifact

The coverage pass runs on the **mutant-injected build with all mutants
inactive** — not a separate clean compile. #2 preserves source offsets under
injection, so with mutants off the original lines still record hits, and
JaCoCo's line map is **byte-identical** to the mutant line map. This gives an
exact join and a single compile. JaCoCo sees our injected
`if (mutantActive(id)) … else …` guards, which is harmless: komust consumes
*line* coverage, never branch coverage.

### 2. Exact `(binary class name, source line)` join

The mutant record **carries the binary class name** (already in hand at
injection time), so selection is a direct `(class, line)` lookup against the
coverage index — strictly more precise than a file-level union, which would
conflate a lambda body and its enclosing method when they share a source line.

### 3. Inline-line normalisation lives on the coverage side

All Kotlin line-quirk knowledge is concentrated in the **coverage-index
builder** (where JaCoCo's SMAP / `KotlinDebug` data and the ≥0.8.15 filter set
already live). It remaps inline callee lines to call-site lines so the index the
selector consumes is already clean, and the selector stays a dumb exact lookup.
A clean index is reusable by anything downstream, not just selection.

### 4. No-coverage mutants are `NO_COVERAGE`, and are surfaced to the agent

A mutant whose `(class, line)` has no covering tests is **never run** (nothing
could kill it) and reported as `NO_COVERAGE` (the enum reconciled in #7). Because
untested-yet-mutable code is a maximally actionable signal, `NO_COVERAGE`
mutants are surfaced in the **token-dense agent stream** (`survivors.json`
projection, #5) as their **own category**, not folded into survivors. This is an
*additive* extension to #5's schema (new category, no breaking change).

### 5. Explicit override: global + per-file, replace-not-augment

A caller may pin the test set at **global** granularity (one set for the whole
run) and/or **per-file** granularity. Per-mutant pinning is **out** for v1 —
mutant ids are content-hashes, fine to *target* but brittle as an override key
(deferred to fog). Where an override applies to a mutant it **fully replaces**
the coverage-derived covering set — the same replace-not-merge stance as the
Mutation Scope override (#6). The **coverage pass still runs** regardless: it is
the mandatory green baseline (#7), and integrity outweighs the time saved. An
overridden mutant skips the coverage *lookup* (its tests are pinned) and can
therefore never be `NO_COVERAGE`.

### Selection algorithm (summary)

```
0. Coverage pass (once, cached): compile with plugin (mutants off) →
   JaCoCo + JUnit listener → coverage index (class,line)→{testId},
   inline lines normalised. Must be green, else abort.
1. For each mutant m with key (class, line, …):
     if an override applies to m (per-file, else global):
        tests(m) = override set          # fully replaces
     else:
        tests(m) = index[(m.class, m.line)]   # exact lookup
        if tests(m) is empty:
           outcome(m) = NO_COVERAGE; do not run; surface to agent stream
2. Hand tests(m) to the execution model (#7), which orders them
   fastest-first and runs fail-fast.
```

## Consequences

- **One compile, exact join** — no cross-artifact line-drift class of bug.
- The mutant record grows one field (binary class name); it also enriches #5's
  survivor records.
- #5's agent stream gains a `NO_COVERAGE` category (additive).
- The override **input surface** (flags / file format) is left to the Gradle
  plugin integration (#9) and the input contract (#6); this ADR fixes only the
  *semantics* (global + per-file, replace, baseline still runs).
- **Tracked risk:** if coverage-side inline normalisation proves incomplete on
  IR edge cases, the fallback is to accept occasional false `NO_COVERAGE` on
  inline bodies as a documented v1 known-risk — not a v1 blocker.
