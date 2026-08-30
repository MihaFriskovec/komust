# 1. v1 mutation operator catalog and junk-mutant skip policy

Date: 2026-08-29

## Status

Accepted

## Context

komust needs a defined set of mutation operators for v1 and a policy for
avoiding junk/equivalent mutants. Two prior-art tools frame the space:

- **pitest** (JVM bytecode) ships a broad DEFAULTS operator set plus STRONGER
  and experimental groups. It does not detect equivalent mutants; it avoids
  them by not shipping operators known to generate them.
- **MutFlow** (K2 Kotlin compiler plugin, compile-once) implements 11 operators
  proven to express cleanly in K2 IR, and — importantly — ships **zero**
  operators that target Kotlin-specific constructs (`?:`, `?.`, user-written
  `when`). Its Kotlin-ness is entirely *defensive*: those constructs are
  protected, not mutated, because they desugar to IR (synthesized null-checks,
  `noWhenBranchMatchedException()`) that mutates into always-crash or equivalent
  mutants.

komust's destination is an **AI-native** tool whose survivor list feeds an
agent's context window. Noisy equivalents directly poison that signal, so the
selection criterion for v1 is **signal-per-junk** (a small, high-kill catalog),
with breadth as the tie-breaker — not parity with pitest. The IR mutation core
is still being proven by prototype #2, so operators whose IR expression is not
already demonstrated by MutFlow are marked **spike-gated**.

## Decision

**Two tiers**: `default` (on) and `experimental` (opt-in). No middle tier.

**Equivalent mutants are avoided by construction** (skip-list), not detected.
Detection is left as a future seam; the skip-list is a policy layer it can
later augment.

**Default tier** — the MutFlow-proven core plus three spike-gated additions:

| Operator | Mutation | Spike-gated |
|---|---|---|
| Arithmetic | `+↔-`, `*↔/`, `%→/` (0/0 guarded) | no |
| Relational boundary/flip | `<, <=, >, >=` | no |
| Equality swap | `==↔!=` (null comparisons skipped) | no |
| Boolean logic | `&&↔||` | no |
| Boolean inversion | `e → !e` | no |
| Constant boundary | numeric constant `±1` | no |
| Boolean return | `→ true`, `→ false` | no |
| Nullable return | `→ null` (nullable return type only) | no |
| Increments | `++ ↔ --` | yes |
| Empty/default return | `0`, `""`, `emptyList()`, `emptyMap()`; skip types with no cheap default | yes |
| Void-call removal | remove an individual `Unit`-returning call site | yes |

**Experimental tier**:

| Operator | Mutation | Note |
|---|---|---|
| Elvis-default | `a ?: b → b` | komust's first Kotlin-native operator; type-safe, high-signal. Reverse (`→ a`) stays skipped as junk. |
| Invert negatives | `-x → x` | low signal, often near-equivalent |
| Exception-type swap | `IllegalArgumentException ⇄ IllegalStateException` | MutFlow-proven, niche |

**Skip-list (v1 policy, adopted from MutFlow verbatim)** — protect and do not
mutate:

- `== null` / `!= null` and the compiler-synthesized null-checks that `?:` and
  `?.` desugar into.
- `!!`, `TODO()`, `require(...)`, `check(...)` — all `IrCall`, not `IrThrow`.
- Exhaustive `when` without `else` (ends in `noWhenBranchMatchedException()`).
- Synthetic / expression-body returns with undefined or zero-width source spans.
- Already-constant returns (`return true/false/null`).
- Property accessors (getters/setters) and already-empty function bodies.
- Guard `0/0` in arithmetic mutants (inject `else → 1`) rather than emit a
  divide-by-zero crash that would false-positive as "killed".

## Consequences

- v1 deliberately **diverges from pitest defaults**: Invert-negatives is demoted
  to experimental, and komust adds a Kotlin-native operator (elvis-default) pitest
  cannot express. This is the point of being Kotlin-native.
- Void removal is **per-call-site** (precise fault localization for the agent),
  not MutFlow's whole-body-empty (coarse). Whole-body-empty is the fallback only
  if per-call proves IR-awkward in the spike.
- The three spike-gated default operators and elvis-default are **contingent on
  prototype #2**. If #2 finds any awkward to express, it demotes to experimental
  or skip; the catalog decision is not reopened.
- `when`-branch mutation is deferred to fog — it awaits #2 and the elvis-default
  operator proving the Kotlin-IR-targeting pattern.
- Not adopting equivalent-mutant *detection* means some equivalents will still
  slip through as false survivors; the skip-list is the only firewall in v1.

## Spike-gated outcome (2026-08-30, #29)

The full default catalog is now implemented in `komust-compiler-plugin`
(K2 IR). All three spike-gated operators **landed** in the default tier — none
demoted:

- **Increments (`++ ↔ --`)** — express as an `inc`/`dec` `IrCall` swap, same
  shape as the arithmetic operator.
- **Empty/default-return** — `0` / `""` / `emptyList()` / `emptySet()` /
  `emptyMap()`, keyed off the return value's IR type; types with no cheap
  default are skipped.
- **Per-call-site void-call removal** — any `Unit`-returning `IrCall` (not a
  setter, not a skip-list assertion) is replaced by `Unit` under its own guard,
  giving per-call fault localisation as intended (not whole-body-empty).

Two implementation notes worth carrying forward:

- The `0/0` guard (`%→/`, `*→/`) spills the divisor to a temporary; the woven
  module now gets a `patchDeclarationParents()` pass so a later const-evaluation
  lowering never trips over a still-parentless temporary.
- `@SuppressMutations` at `@file:` scope is detected by scanning `annotations`
  directly — `IrAnnotationContainer.hasAnnotation(FqName)` misses that case
  under the pinned compiler.

The enabled/disabled-operators option (`operators { enable / disable }`, #38)
is wired through the `CommandLineProcessor` as `disabledOperators` /
`enabledOperators` slug lists.

## Sources

- pitest mutators: <https://pitest.org/quickstart/mutators/>
- MutFlow operators (read from source, `master`): <https://github.com/anschnapp/mutflow>
