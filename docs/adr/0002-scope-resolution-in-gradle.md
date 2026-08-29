# 2. Scope resolution lives in the Gradle plugin; the compiler plugin filters by scope

Date: 2026-08-29

## Status

Accepted

## Context

komust runs **modified-files-only** by default (ADR context: map #1, ticket #6):
only changed code is mutated. This requires deciding *which files/lines* to
mutate and *how that reaches the mutating machinery* — the K2 compiler plugin,
which is the only component that knows a mutation point's `(file, line, col,
ordinal)` and each declaration's IR line-span (prototype #2).

Two components could resolve "what changed": the **Gradle plugin** (owns the
build graph, source sets, and runs at build time) or the **core engine**. And
the compiler plugin, proven in #2 to need *no* compile-time options, now needs
one to receive the scope.

## Decision

**The Gradle plugin resolves scope; the compiler plugin filters by it.**

1. **Input contract — one canonical object, the `Mutation Scope`** (`file →
   [line ranges]`; whole file = all lines). Three ways to produce it:
   - **git-derived default (zero-config):** working-tree diff against the
     merge-base with the default branch; base ref overridable (e.g. `--since
     HEAD` for the tight inner-loop). Staged + unstaged + untracked Kotlin
     sources count; renames follow to the new path; deletions drop out; new
     files enter whole.
   - **`--files a.kt,b.kt`:** whole-file sugar.
   - **`--scope scope.json`:** precise line ranges (the agent's path).
   - **Filter:** production Kotlin sources only — test source sets, generated /
     build dirs, and non-`.kt` files are excluded. Changed *test* files are
     ignored here; test impact is the coverage-mapped test-selection ticket
     (#8), not scoping.
   - **Precedence:** an explicit override (`--files` / `--scope`) **fully
     replaces** git — git is never consulted when an override is present.
   - **Empty changeset** (no changed production Kotlin) → clean success exit,
     zero mutants. The agent loop must tolerate "nothing to do" without failing.

2. **Resolution lives in the Gradle plugin.** It runs the git diff (or parses
   the explicit override), builds the `Mutation Scope`, writes it to
   `scope.json`, and passes the *path* to the compiler plugin as a compile-time
   plugin option.

3. **The compiler plugin does the enclosing-symbol expansion and filtering.**
   It receives the *raw* changed line ranges — no Kotlin parsing on the Gradle
   side. For each declaration whose IR line-span (`startOffset`/`endOffset` →
   `IrFileEntry`) intersects any changed range, it mutates **every** mutation
   point in that declaration. "Declaration" is the nearest enclosing **member**
   function / property-initializer / `init` block (lambdas and local functions
   included). This is the sole scope test the plugin performs — "does this
   symbol's span overlap a changed range?" — riding the same line/ordinal keys
   the coverage join (#8) uses.

4. **Comment / whitespace-only changed lines are not filtered in v1.** Editing
   only a comment inside a function puts the whole function in scope. Detecting
   comment-only diff lines reliably means tokenising the diff; the cost of one
   extra function is small. Deferred to fog.

## Considered options

- **Resolve scope in the core engine, Gradle plugin as a thin caller.** Rejected
  for v1: Gradle already owns source sets and the build lifecycle, so running
  the diff there avoids duplicating that knowledge. Trade-off recorded under
  Consequences.
- **Line-exact mutation (mutate only the literal changed lines).** Rejected: a
  one-line edit changes the enclosing function's behaviour, so line-exact misses
  newly-relevant mutants and is brittle against multi-line expressions and
  reformatting. Enclosing-symbol expansion is the chosen granularity.
- **Expand scope resolution/parsing into the compiler plugin (plugin runs git).**
  Rejected: the plugin should stay a pure IR transform driven by declarative
  input; git and source-set knowledge belong to the build tool.

## Consequences

- **Reverses a prototype #2 finding:** #2 showed the compiler plugin needs no
  `CommandLineProcessor` because it had no options. Scope is now a real
  compile-time option, so v1 must add one (a `scope.json` path). Feeds the
  Gradle-plugin integration ticket (#9).
- **The "thin Gradle plugin" grows a scope-resolution responsibility.** It stays
  free of *mutation* logic (the thick part remains in the compiler plugin), but
  it now owns git diffing and input normalisation.
- **The deferred standalone CLI (fog) inherits a gap:** without Gradle it must
  supply its own scope resolver, or scope resolution must be factored into a
  small shared library the CLI and Gradle plugin both call. Noted on the map.
