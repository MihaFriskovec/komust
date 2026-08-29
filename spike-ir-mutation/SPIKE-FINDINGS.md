# Spike verdict — compile-once IR mutation core (komust #2)

**PROTOTYPE / throwaway.** Branch: `prototype/ir-mutation-core`. Do not build on this code; build on the *model* below.

## Verdict: ✅ PROVEN on current K2 (Kotlin 2.2.0, JDK 21)

A K2 `IrGenerationExtension` injects a **runtime-switchable mutant** into a Kotlin
function in a **single compile**, and a test flips it on/off **per run with no
recompile and no class reload**. All three assertions pass:

| Test | Proves |
|------|--------|
| `baseline_is_original_addition` | switch OFF → original `+` runs (`add(2,3)==5`) |
| `activating_a_single_mutant_flips_one_operator` | switch ON → mutant `-` runs (`add(2,3)==-1`), **same compiled class** |
| `mutants_are_thread_isolated` | mutant active on thread A, original on thread B, concurrently |

Run: `cd spike-ir-mutation && JAVA_HOME=<jdk21> gradle :demo:test`

## The model the spec should adopt

### 1. Injection (compile-time, MutFlow-style)
Each mutable site `a + b` is rewritten in IR to a guarded conditional that carries
**both** original and mutant in one artifact:

```kotlin
if (mutantActive("<id>")) a - b   // mutant branch
else                      a + b   // original branch
```

Built with `DeclarationIrBuilder.irBlock { irTemporary(...); irIfThenElse(...) }`,
spilling both operands into temporaries so each branch reads them via a fresh
`irGet` (an IR node has exactly one parent — operands cannot be shared across
branches). Operators resolved via `pluginContext.referenceFunctions(CallableId(...))`.

### 2. Switching mechanism → **thread-local single-slot registry**
`io.komust.runtime.MutantRegistry` holds one active mutant id per thread
(`ThreadLocal<String?>`). The plugin injects a call to a top-level
`mutantActive(id): Boolean`. Chosen over a system property because:
- **Thread-local is required for parallel execution** — the isolation test shows
  different threads running different mutants against the *same loaded classes*.
  A JVM-wide system property would serialize the whole mutant sweep.
- Single-slot (one active mutant at a time) matches mutation-testing semantics:
  you measure each mutant independently. `activate(id)` / `clear()` per test run.

### 3. Mutant → source mapping (needed by #6 modified-files, #8 coverage)
From the `IrCall`: `file.fileEntry.name` + `getLineNumber(startOffset)` +
`getColumnNumber(startOffset)` (both 0-based → +1). Id format:

```
Calc.kt:5:32#ARITH:PLUS_TO_MINUS@0
 └─file  │  │ └─operator          └─ordinal
        line col
```

**Sharp edge found (load-bearing for #6/#8):** start-offset is **NOT unique**.
Nested same-line operators — `a + b + c` desugars to `(a+b)+c` where the outer and
inner `plus` calls **share the same startOffset** (`7:45`). Without a
disambiguator they collide onto one id and become non-independently-switchable.
Fixed here with a per-`(file:line:col)` **ordinal** (`@0`, `@1`). The mutant key is
therefore `(file, line, col, operator, ordinal)`, never position alone. The
line/col still give the source range #6 and #8 join against; the ordinal is the
tie-breaker within a position.

## Sharp edges / risks for the spec

- **API instability (the standing map risk, now concrete):** the entry point
  `CompilerPluginRegistrar` is `@ExperimentalCompilerApi` and IR construction is
  `@UnsafeDuringIrConstructionAPI` — both require explicit opt-in and can change
  per Kotlin release. → exact-version pin + a compatibility shim seam.
- **API churn is real:** value arguments now use the unified `IrCall.arguments[]`
  index list (dispatch receiver at `[0]`), replacing the older
  `dispatchReceiver` / `putValueArgument`. Any port from pre-2.2 examples (incl.
  MutFlow) must be re-checked against the installed compiler.
- **Plugin loading:** `kotlinCompilerPluginClasspath(project(":plugin"))` +
  `META-INF/services` ServiceLoader discovery works with **no** CommandLineProcessor
  when the plugin takes no compile-time options. Options (when needed) still want a
  `CommandLineProcessor` + `KotlinCompilerPluginSupportPlugin` (feeds #9).
- **Debug-info correctness: NOT yet verified.** The builder preserves start/end
  offsets, but that line numbers in the mutated bytecode still point at the
  original source (for stack traces in surviving-mutant reports) is unproven here.
  Flagged for the execution ticket (#7).
- **Operand side-effects:** spilling to temporaries evaluates each operand exactly
  once in both branches — correct, and avoids double-evaluation. Confirmed by
  construction; worth a dedicated test in the real engine.

## What this does NOT cover (deliberately out of spike scope)
Only `Int.plus` was mutated (one operator, to keep the spike minimal). The
operator catalog (#4) is already decided; this spike proves the *injection +
switch mechanism* those operators will ride on. Coroutines/`suspend` and inline
functions (map fog) are untouched — that IR-expressibility question now sharpens
on top of this proven core.
