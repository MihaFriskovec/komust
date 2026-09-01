# komust

Kotlin-native mutation testing for Kotlin/JVM — pitest for Kotlin, built AI-native.

komust operates at the **K2 compiler IR level**, not JVM bytecode. It weaves all
mutants into a single compilation and switches them at runtime, so there is no
recompilation per mutant. Its default scope is **modified files only** (git diff
against a base ref), making it fast enough for the edit-test inner loop.

The primary output is `survivors.json` — a token-dense file listing only the
actionable gaps in your test suite, each carrying a "write a test that does X"
instruction sized for an AI agent's context window.

> **Status:** `0.1.0-SNAPSHOT` — not published to Maven Central yet. Use a
> composite build or `publishToMavenLocal` to consume.

## Features

- **K2 IR mutations** — works on Kotlin constructs directly, not bytecode patterns
- **Compile-once, runtime-switchable** — one compilation, mutants toggled by a runtime guard
- **Modified-files default scope** — only mutates code changed vs. a git base ref
- **14 mutation operators** — 11 default, 3 experimental (see [Operators](#operators))
- **Coverage-mapped test selection** — per-mutant, only the tests that cover the mutated line run
- **Forked worker pool** — parallel mutant execution with process isolation and hang-kill timeouts
- **Incremental cache** — reuses prior results when source, tests, and config haven't changed
- **AI-native output** — `survivors.json` is structured for agent consumption; `report.json` is the lossless record

## Quick start

### 1. Add the plugin

**settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        mavenLocal()          // until published to Maven Central
        gradlePluginPortal()
        mavenCentral()
    }
}
```

**build.gradle.kts**

```kotlin
plugins {
    kotlin("jvm") version "2.2.0"
    id("io.komust") version "0.1.0-SNAPSHOT"
}
```

### 2. Run mutation testing

```console
# mutate only files changed vs. origin/main (the default)
./gradlew mutationTest

# mutate the entire project
./gradlew mutationTest --all

# mutate changes since a specific ref
./gradlew mutationTest --since feature-branch
```

### 3. Read the results

Output lands in `build/komust/`:

| File | Purpose |
| --- | --- |
| `survivors.json` | Actionable mutants only — survivors + no-coverage, with "write a test" summaries |
| `report.json` | Every mutant and its outcome (lossless, machine-readable) |
| `report.txt` | Human-readable report |

Example `survivors.json` entry:

```json
{
  "operator": "relational",
  "original": ">=",
  "mutated": ">",
  "enclosingSymbol": "discountRate",
  "location": {
    "path": "src/main/kotlin/com/example/PriceCalculator.kt",
    "startLine": 55
  },
  "summary": "In `discountRate` (PriceCalculator.kt:55), changing `>=` to `>` is not detected — 3 covering tests still pass. Add or strengthen a test..."
}
```

## Configuration

The `komust {}` DSL sets stable policy. All settings are optional — the defaults
work out of the box.

```kotlin
komust {
    baseRef.set("origin/main")       // git diff base ref (default: origin/main)
    workers.set(4)                   // forked worker count (default: available processors)
    timeoutFactor.set(3.0)           // per-test timeout multiplier (default: 3.0)
    cache.set(true)                  // cross-run result cache (default: true)

    operators {
        experimental.set(false)      // enable experimental operator tier (default: false)
        enable("elvis-default")      // force-enable a specific operator
        disable("increments")        // force-disable a specific operator
    }

    output {
        humanReport.set(true)        // render report.txt (default: true)
        consoleSurvivorsOnly.set(false)
    }
}
```

### Task options

Per-run overrides on the `mutationTest` task:

| Option | Description |
| --- | --- |
| `--all` | Mutate the whole project instead of only changed files |
| `--since <ref>` | Override the git base ref |
| `--files <globs>` | Comma-separated path globs to mutate |
| `--no-cache` | Bypass the incremental cache |

## Operators

### Default tier (on by default)

| Slug | What it does |
| --- | --- |
| `arithmetic` | `+` ↔ `-`, `*` ↔ `/`, `%` → `/` |
| `relational` | `<`, `<=`, `>`, `>=` boundary flips |
| `equality` | `==` ↔ `!=` (null comparisons skipped) |
| `boolean-logic` | `&&` ↔ `\|\|` |
| `boolean-inversion` | `expr` → `!expr` |
| `constant-boundary` | numeric literal ±1 |
| `boolean-return` | `return true` ↔ `return false` |
| `nullable-return` | `return x` → `return null` (nullable types only) |
| `increment` | `++` ↔ `--` |
| `empty-return` | return zero-value: `0`, `""`, `emptyList()`, etc. |
| `void-call` | remove a `Unit`-returning call site |

### Experimental tier (off by default)

| Slug | What it does |
| --- | --- |
| `elvis-default` | `a ?: b` → `b` |
| `invert-negatives` | `-x` → `x` |
| `exception-type-swap` | swap exception types (e.g. `IllegalArgumentException` ↔ `IllegalStateException`) |

### Suppression

When an operator produces noise on specific code, suppress it:

```kotlin
import io.komust.runtime.SuppressMutations

@SuppressMutations          // on a declaration — suppresses entire body
fun logMetrics() { ... }
```

```kotlin
val x = someCall()          // komust:ignore — suppresses this line
```

komust also has a built-in **skip-list** of constructs that are never mutated:
`!!`, `TODO()`, `require()`, `check()`, `error()`, `assert()`, `== null` / `!= null`,
exhaustive `when` without `else`, and synthetic null-checks from `?.` and `?:`.

## Example project

The [`komust-example/`](komust-example/) directory is a standalone Gradle build
that demonstrates the plugin on a small pricing library. It produces killed,
survived, and no-coverage outcomes:

```console
./gradlew -p komust-example mutationTest --all
```

```
komust: 41 mutant(s) — 29 killed, 3 survived, 9 no-coverage, 0 timeout
```

See [`komust-example/README.md`](komust-example/README.md) for a full walkthrough
including a tutorial on fixing the surviving mutant.

## How it works

1. **Scope resolution** — `komust-scope` diffs against the base ref and writes `scope.json`
2. **Mutation compilation** — a dedicated Kotlin compilation (separate from `compileKotlin`) applies the K2 compiler plugin, which weaves guarded mutants into the IR
3. **Coverage pass** — a single JaCoCo-instrumented run of the full test suite over the unmutated program builds the `(class, line) → tests` index
4. **Mutant sweep** — for each in-scope mutant, the runtime switch activates it, only covering tests run (fastest-first, fail-fast), and the result is recorded
5. **Report** — `report.json`, `survivors.json`, and `report.txt` are emitted to `build/komust/`

## Architecture

```
komust-compiler-plugin     K2 IR mutation + runtime guard (MutantSwitch)
komust-scope               git diff → Mutation Scope → scope.json
komust-engine              build-tool-agnostic orchestrator (coverage, sweep, report)
komust-gradle-plugin       Gradle adapter: DSL, tasks, compiler plugin wiring, engine fork
```

The Gradle plugin is a thin adapter. All mutation, coverage, and execution logic
lives in the engine, which can be driven by any build tool that produces the
engine input contract.

## Requirements

- Kotlin **2.2.0** (exact pin — the K2 compiler plugin API is unstable across versions)
- JDK **21+**
- Gradle **8.x**
- JUnit Platform (JUnit 5) for test execution

## License

TBD
