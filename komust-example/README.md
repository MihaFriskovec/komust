# komust example

A tiny, runnable demonstration of the **`io.komust`** Gradle plugin — Kotlin-native
mutation testing. It applies the plugin to a small pricing/cart library, runs the
`mutationTest` task, and produces a report that shows komust's three signals:
mutants **killed**, a **surviving** mutant (a real gap in the tests), and a
**no-coverage** method (code shipped with no test at all).

> This example lives inside the komust repo and consumes the plugin **straight from
> source** — there is no published release to depend on yet.

## How it consumes the plugin

The example is its **own Gradle build** that pulls the plugin in via a
[composite build](https://docs.gradle.org/current/userguide/composite_builds.html).
`komust-example/settings.gradle.kts` includes the repo root:

```kotlin
pluginManagement {
    includeBuild("../")          // substitutes the io.komust plugin from source
    repositories { gradlePluginPortal(); mavenCentral() }
}
includeBuild("../")              // substitutes io.komust:* libraries from source too
```

and `komust-example/build.gradle.kts` applies it **by id with no version** — the
composite build substitutes it from source, so there is nothing to keep in sync and
nothing that can rot on a version bump:

```kotlin
plugins {
    kotlin("jvm") version "2.2.0"
    id("io.komust")              // no version — resolved from ../
}
```

No `publishToMavenLocal`, no pinned version, no separate wrapper. It is not part of
the root `./gradlew build`; you invoke it explicitly (below).

## Run it

From the **repo root** (the example reuses the root Gradle wrapper):

```console
$ ./gradlew -p komust-example mutationTest --all
```

`--all` mutates the whole project — the simple, deterministic default for a demo.
komust also has a **modified-files** scope (the default when you omit `--all`: it
mutates only what changed against your git base ref, `--since <ref>` to pick the
base), which is what you'd normally use day to day. This example doesn't stage a git
diff, so run it with `--all`.

You'll see:

```
komust: 41 mutant(s) — 29 killed, 3 survived, 9 no-coverage, 0 timeout
komust: report → komust-example/build/komust/report.json
komust: survivors → komust-example/build/komust/survivors.json
```

Outputs land in `komust-example/build/komust/`:

| file | what it is |
| --- | --- |
| `report.json` | every mutant and its outcome (the full machine-readable report) |
| `survivors.json` | just the actionable mutants — survivors + no-coverage |
| `report.txt` | the same, human-readable |

## What the report tells you

The sample library is [`PriceCalculator`](src/main/kotlin/io/komust/example/PriceCalculator.kt)
— six small methods, each written to exercise a different family of komust's default
mutation operators (this run exercises **10** of them: arithmetic, relational,
equality, boolean-logic, boolean-return, boolean-inversion, constant-boundary,
increment, empty-return, nullable-return). The tests in
[`PriceCalculatorTest`](src/test/kotlin/io/komust/example/PriceCalculatorTest.kt) are
shaped to produce a believable spread of outcomes.

### ✅ Killed (29) — the tests do their job

`lineTotal`, `isFreeShipping`, and `findCoupon` are tested thoroughly. Every mutant
komust weaves into them is caught by an assertion, so they never appear in
`survivors.json`. Note `isFreeShipping` is tested **at its boundary** (`total == 100`
and `total == 99`) — contrast that with `discountRate` below.

### ⚠️ Survived (3) — a real gap: the untested boundary in `discountRate`

```kotlin
fun discountRate(qty: Int, tier: Tier): Int {
    val bulk = if (qty >= 10) 15 else 0        // ← line 55
    ...
}
```

The tests check `discountRate` at `qty = 5` and `qty = 20` — but **never at the
`qty = 9 / qty = 10` boundary**. So komust flips the boundary three ways and every
one slips past the tests:

```jsonc
// komust-example/build/komust/survivors.json (excerpt)
{
  "survivors": [
    {
      "operator": "relational",
      "original": ">=", "mutated": ">",
      "enclosingSymbol": "discountRate",
      "location": { "path": "src/main/kotlin/io/komust/example/PriceCalculator.kt", "startLine": 55 },
      "summary": "In `discountRate` (PriceCalculator.kt:55), changing `>=` to `>` is not detected — 3 covering tests still pass. Add or strengthen a test..."
    },
    { "operator": "constant-boundary", "original": "10", "mutated": "9",  "enclosingSymbol": "discountRate", "...": "..." },
    { "operator": "constant-boundary", "original": "10", "mutated": "11", "enclosingSymbol": "discountRate", "...": "..." }
  ]
}
```

All three say the same thing: **the `qty >= 10` boundary is untested.** `>= 10` → `> 10`
and `>= 10` → `>= 11` both change behaviour only at exactly `qty == 10`; `>= 10` → `>= 9`
only at `qty == 9`. Tests at 5 and 20 can't tell any of them apart from the original.

### 🚫 No coverage (9) — a method shipped with no test

Every mutant in `loyaltyPoints` is reported as **no-coverage**: not a single test
calls it. This is the most actionable signal of all — komust is telling you a whole
feature went out untested. (Line coverage alone would also flag this; komust adds the
detail of exactly which behaviours are unverified — the `++`, the `/ 10`, the return.)

### Not in the report at all — the escape hatch and the skip-list

- `checkout` carries **`@SuppressMutations`** (imported from `io.komust.runtime`), so
  komust weaves **no** mutants anywhere inside it — the escape hatch for glue/logging
  code whose mutants would be noise. It never appears in the report.
- The `require(qty > 0)` guard in `lineTotal` is a komust **skip-list** construct:
  komust never mutates inside `require` / `check` / `error` / `assert`, so that guard
  sits unmutated even though the arithmetic beside it is fair game.

## Tutorial: fix the survivor

The surviving mutants are a genuine test gap — the boundary is untested. Pin it with
one test:

```diff
+    @Test
+    fun `discountRate pins the bulk boundary`() {
+        assertEquals(0, calc.discountRate(9, Tier.STANDARD))   // just below → no bulk discount
+        assertEquals(15, calc.discountRate(10, Tier.STANDARD)) // exactly at → bulk discount applies
+    }
```

Re-run `./gradlew -p komust-example mutationTest --all` and the count moves to:

```
komust: 41 mutant(s) — 32 killed, 0 survived, 9 no-coverage, 0 timeout
```

All three `discountRate` survivors are now killed by that single boundary test. That
edit → re-run → survivor-gone loop is the whole point of mutation testing.

> This repo deliberately **leaves the survivor unfixed** so you can reproduce it. Add
> the test above yourself to watch it go green.

## Configuration

The plugin exposes a `komust { }` DSL for stable policy (operator tiers, worker
count, report options). This example relies entirely on the defaults, so it declares
no `komust { }` block at all. See the plugin docs / `KomustExtension` for the knobs.
