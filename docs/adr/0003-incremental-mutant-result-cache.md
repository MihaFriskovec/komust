# ADR-0003: Incremental mutant-result cache

## Status

Accepted (resolves map ticket #10, "Cross-run mutant-result caching model"). Depends on #5 (mutant identity + output contract), #6 (Mutation Scope), #7 (execution model).

## Context

The agent inner loop is edit → re-run komust → read survivors, repeated many times per feature. Under the **Mutation Scope** (#6) a modified-files run already only mutates changed **enclosing symbols**, so in the tightest loop (`--since HEAD`) there is little to cache. The cache earns its keep on the *broader* scopes the same loop uses — a `diff-vs-main` run where the branch touches many symbols but only one changed since the last komust run, or a full-project run where almost nothing changed run-to-run. There the expensive work is **executing covering tests per mutant**; compilation is compile-once (#7) and paid regardless. We need a model for reusing prior per-mutant execution outcomes without ever risking a wrong survivor set.

Prerequisites already fixed the two halves this depends on: mutant **identity** is a line-independent content-hash `id` (#5), and the **execution model** is a controller + forked-worker pool over a green baseline (#7).

## Decision

### 1. Best-effort, never authoritative

Correctness is always defined by *actually executing the mutant*. The cache only ever lets us **skip a run we are certain is redundant**. A cache miss, a wiped `build/`, a schema-version bump, or a corrupt file all degrade to "re-execute" — never to a wrong result. This is the only stance under which an agent can trust the output, and it lets the cache live in `build/komust/` (wiped by `./gradlew clean`, absent on fresh CI) with no correctness consequence.

### 2. Scope decides membership; the cache decides freshness

The **Mutation Scope** (#6) remains the sole authority on *which* mutants appear in a run's report. The cache only decides, of the in-scope mutants, which get **re-executed** versus served from a prior outcome. A cache-served mutant appears in `report.json` / `survivors.json` **identical to a freshly-executed one** — the agent cannot tell the difference. An out-of-scope symbol never appears just because the cache remembers it; the whole-project picture is a full-project *scope*, not a cache trick.

Because #7 compiles every in-scope mutant regardless (compile-once), the cache never supplies compile-derived fields. `location`, `operator`, `original→mutated`, and the rendered `summary` are **regenerated fresh** from this run's compile; the cache supplies only the **execution-derived** outcome. This is what keeps a cached survivor's line number current even when code above it shifted (the line-independent `id` matches; the location is this run's).

### 3. Validity fingerprint

A cached outcome for mutant `id` is reusable only when `id` **and** a per-mutant **validity fingerprint** both match. The fingerprint hashes every determinant whose change could flip the outcome:

1. **Enclosing-symbol source** — the raw source span of the mutant's enclosing symbol (the exact bytes the compiler saw for that declaration), no normalisation. A comment/whitespace edit therefore re-executes the symbol, matching #6's accepted over-inclusion and its enclosing-symbol unit.
2. **Covering-test content** — for each covering test, the hash of its **declaring-class bytecode** (available from the green baseline compile). Class-level is deliberately slightly coarse: adding a method to `CalculatorTest` invalidates every mutant that class covers — which is *correct* (the new method might kill them) plus mild, acceptable collateral. This granularity is load-bearing: a coarse "did any test change" fingerprint would invalidate the whole cache every inner-loop iteration, exactly when the agent adds a test.
3. **Operator + config** — the komust / operator-catalog version (an operator's semantics changing must invalidate).
4. **Environment** — Kotlin version + JDK (already load-bearing in #5's metadata; the K2 plugin API and operator behaviour are Kotlin-version-sensitive).

Baseline greenness (#7) is **not** a fingerprint input: a red baseline aborts the whole run, so no cache read happens against it.

### 4. Store layout & write semantics

- A **single** `build/komust/cache.json`, keyed by mutant `id` (not per-mutant files — a run has thousands of mutants; one read-modify-write is cheaper and atomic).
- Each entry is **minimal**: `{ id, fingerprint, status, coveringTests: [{ uniqueId, killed }] }`. Nothing a compile can rederive is stored.
- The **controller** (which #7 already makes the owner of assembled results) performs one **atomic** (temp-file + rename) **read-modify-write merge** at run-end: load the prior cache, refresh the in-scope `id`s, and **retain** the rest (still valid until their own fingerprint changes). Workers never touch the cache — they only return outcomes, so there are no cross-fork write races.
- **Merge, not overwrite**, because a scoped run only produces outcomes for in-scope symbols; overwriting would let a `--since HEAD` run erase the `diff-vs-main` cache.
- **Pruning** dead entries happens **only on full (unscoped) runs**, which see every live `id`; a scoped run cannot distinguish "deleted" from "out-of-scope," so it never prunes.

### 5. Which mutants re-run vs reuse

On a run, for each in-scope mutant (freshly generated by the compile): compute its validity fingerprint, look up its `id` in the cache. **Fingerprint match → reuse** the cached status + covering-test outcomes (splice with fresh compile-derived fields, re-render summary). **Miss → execute** via the #7 worker pool, then write the fresh outcome into the merged cache.

## Consequences

- The cache targets exactly the expensive term (per-mutant test execution) and is naturally bounded by what the compiler processed this run (= scope), so #6 stays the single authority on report membership.
- Correctness is independent of the cache in all cases; the cache is a pure accelerator.
- Over-invalidation is biased *safe* (coarser than strictly necessary), consistent with #6's v1 over-inclusion posture. Method-level covering-test granularity is deferred to fog.
- The green baseline full-suite run (#3/#7) is still paid every run; the cache saves only the per-mutant covering-test executions.

## Deferred (fog)

- **Method-level covering-test fingerprinting** (tighter than class-level bytecode).
- Any **cross-machine / durable** cache sharing (v1 is a best-effort local `build/` artifact).
