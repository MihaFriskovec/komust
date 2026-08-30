# `report.json` / `survivors.json` format

komust's primary output is machine-readable JSON — the **agent-facing output
contract** (#5, ADR-0005). A run writes three files under `build/komust/`:

| File            | Role                                                                                     |
| --------------- | --------------------------------------------------------------------------------------- |
| `report.json`   | Lossless canonical record — every mutant + full run metadata. **The source of truth.**  |
| `survivors.json`| Token-dense projection — only the actionable outcomes, sized for an agent context window.|
| `report.txt`    | Human report, rendered **from** `report.json` so it can never disagree with it.          |

`survivors.json` and `report.txt` are derived from the re-read `report.json`, not
from run state.

## `report.json`

```json
{
  "schemaVersion": "1.0.0",
  "run": {
    "startedAt": "2026-08-30T10:00:00Z",
    "finishedAt": "2026-08-30T10:00:42Z",
    "komustVersion": "0.1.0-SNAPSHOT",
    "operators": ["arithmetic", "relational"],
    "counts": { "total": 4, "killed": 1, "survived": 1, "noCoverage": 1, "timeout": 1 }
  },
  "mutants": [
    {
      "id": "Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0",
      "status": "SURVIVED",
      "location": { "path": "src/main/kotlin/fixture/Calc.kt", "startLine": 4, "endLine": 4 },
      "operator": "arithmetic",
      "original": "+",
      "mutated": "-",
      "enclosingSymbol": "Calc.add",
      "coveringTests": ["[engine:junit-jupiter]/[class:fixture.CalcTest]/[method:addLoose()]"],
      "testsExecuted": 1,
      "summary": "In `Calc.add` (Calc.kt:4), changing `+` to `-` is not detected — 1 covering test still passes. Add or strengthen a test of `Calc.add` (Calc.kt:4) so that this change makes it fail."
    }
  ]
}
```

### Contract

| Field                    | Rule                                                                                              |
| ------------------------ | ----------------------------------------------------------------------------------------------- |
| `schemaVersion`          | Semver. Pinned by `schema/report.schema.json`. See **Versioning** below.                        |
| `run.startedAt` / `finishedAt` | ISO-8601 instants.                                                                        |
| `run.komustVersion`      | The komust build that produced the report.                                                      |
| `run.operators`          | Operator slugs that produced ≥ 1 mutant, ascending.                                             |
| `run.counts`             | `total == killed + survived + noCoverage + timeout`. There is **no mutation-score field** — score reporting / thresholds / CI gating are out of v1 (spec #23). A consumer derives a ratio from `counts` if it wants one. |
| `mutants`                | **Sorted by `(location.path, location.startLine, id)`.** Same order as `survivors.json`.        |
| `mutants[].status`       | `SURVIVED \| KILLED \| NO_COVERAGE \| TIMEOUT`. `NO_COVERAGE` is its own category, never folded into `SURVIVED`. `TIMEOUT` counts as killed for scoring but is reported distinctly. The v1 in-process sweep produces the first three; `TIMEOUT` arrives with the forked worker pool (#34) with no schema change. |
| `mutants[].location`     | `path` is repo-root-relative, `/`-separated. `startLine`/`endLine` 1-based inclusive. `startColumn`/`endColumn` are **spike-gated on #2** — omitted until the IR pass emits reliable spans (an additive change when they land). |
| `mutants[].original` / `mutated` | The construct as written and what the operator rewrote it to (e.g. `+` → `-`).           |
| `mutants[].enclosingSymbol` | Nearest enclosing member declaration — the unit a targeting test is written against.         |
| `mutants[].coveringTests`| JUnit Platform `uniqueId`s, fastest-first. The **whole** selected set for `KILLED`/`SURVIVED` (a fail-fast kill only ran a prefix); `[]` for `NO_COVERAGE`. |
| `mutants[].testsExecuted`| How many of `coveringTests` actually ran: `coveringTests.size` for `SURVIVED`, smaller for a fail-fast `KILLED`, `0` for `NO_COVERAGE`. |
| `mutants[].killedBy`     | `uniqueId` of the first failing covering test. Present **iff** `status` is `KILLED`.             |
| `mutants[].summary`      | Rendered "write a test that does X" instruction. Present for `SURVIVED` and `NO_COVERAGE` (the actionable outcomes); absent otherwise. |

Absent optional fields (`killedBy`, `summary`, the columns) mean `null` — they
are omitted, never emitted as `"x": null`.

## `survivors.json`

```json
{
  "schemaVersion": "1.0.0",
  "survivors": [
    {
      "id": "Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0",
      "location": { "path": "src/main/kotlin/fixture/Calc.kt", "startLine": 4, "endLine": 4 },
      "operator": "arithmetic",
      "original": "+",
      "mutated": "-",
      "enclosingSymbol": "Calc.add",
      "coveringTests": ["[engine:junit-jupiter]/[class:fixture.CalcTest]/[method:addLoose()]"],
      "summary": "In `Calc.add` (Calc.kt:4), changing `+` to `-` is not detected — 1 covering test still passes. Add or strengthen a test of `Calc.add` (Calc.kt:4) so that this change makes it fail."
    }
  ],
  "noCoverage": [
    {
      "id": "Calc.kt:6:40:ARITH_TIMES_TO_DIV#0",
      "location": { "path": "src/main/kotlin/fixture/Calc.kt", "startLine": 6, "endLine": 6 },
      "operator": "arithmetic",
      "original": "*",
      "mutated": "/",
      "enclosingSymbol": "Calc.mul",
      "summary": "In `Calc.mul` (Calc.kt:6), no test executes the `*` at line 6. Write a test that exercises `Calc.mul` (Calc.kt:6) and would fail if `*` became `/`."
    }
  ]
}
```

- `survivors` — mutants a test **covers** but no test **kills** (a test-quality
  gap). `coveringTests` are the tests that ran and passed anyway.
- `noCoverage` — mutants on a line **no test executes** (untested-but-mutable
  code). Their own category (ADR-0004 §4); no `coveringTests`.
- `KILLED` / `TIMEOUT` mutants are absent — nothing to act on. Their full record
  is in `report.json`.
- Both arrays use the same `(path, startLine, id)` sort as `report.json`.

Pinned by `schema/survivors.schema.json`.

## Determinism

Both JSON files are stable for a given run input: `mutants` (and both
`survivors.json` arrays) are `(path, line, id)`-sorted, the JSON is
pretty-printed with a two-space indent and a trailing newline, and every summary
is rendered purely from mutant facts. Golden-file assertions on both files are
appropriate (Testing Decisions, #23) — the only run-varying inputs are
`run.startedAt` / `run.finishedAt` / `run.komustVersion`.

## Versioning

`schemaVersion` is semver, and the in-repo `schema/*.schema.json` files are its
authority. Within a **major** version every change is **additive**: a new
optional field, a new `status` enum value, a new metadata key — never a removal,
a rename, or a type change. The schema files deliberately do **not** set
`additionalProperties: false`, so a `1.y` document validates against the `1.x`
schema even after fields are added, and komust's own reader
(`ignoreUnknownKeys`) skips fields it does not know. A reader **rejects** a
`schemaVersion` from a *different major* rather than guessing past a breaking
change. A breaking change bumps the major and ships a new schema file.
