# `cache.json` format

The **mutant-result cache** (ADR-0003, CONTEXT.md) — komust's best-effort
cross-run store of prior mutant execution outcomes. A single file,
`build/komust/cache.json`, keyed by mutant `id`. It is an **accelerator, never a
contract**: a miss, a wipe, a version skew, or a corrupt file all degrade to
"re-execute", never to a wrong result, so — unlike `report.json` — a bad
`cache.json` is silently discarded rather than reported.

## Shape

```json
{
  "schemaVersion": "1.0.0",
  "entries": [
    {
      "id": "Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0",
      "fingerprint": "9f2b1c…",
      "status": "SURVIVED",
      "coveringTests": [
        { "uniqueId": "[engine:junit-jupiter]/[class:fixture.CalcTest]/[method:addLoose()]", "killed": false }
      ]
    },
    {
      "id": "Calc.kt:6:40:ARITH_TIMES_TO_DIV#0",
      "fingerprint": "1a44e0…",
      "status": "KILLED",
      "coveringTests": [
        { "uniqueId": "[engine:junit-jupiter]/[class:fixture.CalcTest]/[method:mulExact()]", "killed": true }
      ]
    }
  ]
}
```

## Contract

| Field                       | Rule                                                                                                             |
| --------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `schemaVersion`             | Semver. A **different major** on a read discards the whole file (total miss); a later minor is tolerated (additive-only). No in-repo JSON Schema — this is not an agent-facing artifact. |
| `entries`                   | Array, sorted ascending by `id`, so a given logical cache state serialises byte-identically.                    |
| `entries[].id`              | The line-independent content-hash mutant `id` (#5) — the join key with each run's freshly-compiled mutants.     |
| `entries[].fingerprint`     | The **validity fingerprint** (below) captured when this outcome was produced. Reuse requires it to still match. |
| `entries[].status`          | `SURVIVED \| KILLED \| NO_COVERAGE`. (`TIMEOUT` joins when the forked worker pool, #34, can produce it.)         |
| `entries[].coveringTests`   | The mutant's covering test set in the sweep's fastest-first visit order, each flagged `killed`. Exactly one `killed: true` for `KILLED` (the sweep is fail-fast); none for `SURVIVED`; empty for `NO_COVERAGE`. `testsExecuted` is **not** stored — it is rederived (the killer's position for a kill, the set size for a survivor). |

Only the **execution-derived** half of a mutant's result is stored. Everything a
compile can rederive — `location`, `operator`, `original → mutated`, the rendered
`summary` — is regenerated fresh each run and spliced onto the cached outcome, so
a reused survivor's line number is always the current run's (ADR-0003 §2).

One accepted imprecision: a reused fail-fast `KILLED` replays the **cached run's**
covering-test order and killer-prefix `testsExecuted`. Test timings are
re-measured every coverage pass and are deliberately *not* a fingerprint
determinant, so the fastest-first order — and hence which prefix a fail-fast kill
would stop at — can differ slightly from what a fresh execution would record. The
covering *set*, the `status`, and the killer are unaffected. Consistent with the
cache's "biased safe, cosmetically imprecise" posture.

## Validity fingerprint

A cached outcome for `id` is reused only when `id` **and** its fingerprint both
match. The fingerprint is a SHA-256 over every determinant whose change could
flip the outcome (ADR-0003 §3):

1. **Enclosing-symbol source** — the raw source span of the mutant's enclosing
   symbol, no normalisation (a comment/whitespace edit re-executes it).
2. **Covering-test content** — per covering test, its **declaring-class
   bytecode**, class-level: adding a method to a covering test class invalidates
   every mutant that class covers.
3. **Operator + config** — the komust / operator-catalog version.
4. **Environment** — Kotlin version + JDK.

Any determinant change is a miss and re-executes the mutant. Over-invalidation is
biased *safe*, consistent with #6's over-inclusion posture.

## Write semantics

- The **controller** performs one **atomic** (temp-file + rename)
  read-modify-write **merge** at run-end. Workers never touch the cache — no
  cross-fork write races. A failed write is swallowed: the run's report is
  already complete, and the next run simply starts cold.
- **Merge, not overwrite**: a scoped run only produces outcomes for in-scope
  symbols and **retains** every other entry (still valid until its own
  fingerprint changes). Overwriting would let a `--since HEAD` run erase the
  `diff-vs-main` cache.
- **Pruning** dead entries happens **only on full (unscoped) runs**, which see
  every live `id`. A scoped run cannot tell "deleted" from "out-of-scope", so it
  never prunes.

## Scope decides membership; the cache decides freshness

The **Mutation Scope** (#6) is the sole authority on *which* mutants appear in a
run's report. The cache only decides, of the in-scope mutants, which are served
from a prior outcome versus re-executed. An out-of-scope `id` the cache still
remembers is carried across merges but never surfaced — the whole-project picture
is a full-project *scope*, not a cache trick.

## `--no-cache`

Bypasses the cache entirely: the prior file is neither read (every in-scope
mutant is executed fresh) nor written (the run does not merge or prune). It is
the escape hatch for when the cache is distrusted.

## Determinism

`entries` is `id`-sorted, the JSON is pretty-printed with a two-space indent and
a trailing newline. `cache.json` is not covered by golden-file assertions — it is
a derived accelerator whose only invariants are "a hit is provably safe" and "a
miss costs only time".
