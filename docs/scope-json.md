# `scope.json` format

The `komust-scope` module resolves the canonical **Mutation Scope** (`file →
line ranges`) and writes it as `scope.json`. This is the stable handoff from the
scope resolver to the compiler plugin (via the Gradle plugin — ADR-0002,
ADR-0005). The deferred CLI produces the identical file.

## Shape

```json
{
  "version": 1,
  "files": [
    {
      "path": "src/main/kotlin/com/example/Bar.kt",
      "wholeFile": true
    },
    {
      "path": "src/main/kotlin/com/example/Foo.kt",
      "ranges": [
        { "start": 10, "end": 14 },
        { "start": 20, "end": 20 }
      ]
    }
  ]
}
```

## Contract

| Field             | Rule                                                                                                      |
| ----------------- | -------------------------------------------------------------------------------------------------------- |
| `version`         | Integer, currently `1`. A reader **must reject** an unrecognised version rather than guess.               |
| `files`           | Array, sorted ascending by `path`. `[]` is valid and means an **empty scope** — a clean, zero-mutant run. |
| `files[].path`    | Repo-root-relative, `/`-separated POSIX path. The consumer resolves it against the repository root.       |
| `files[].wholeFile` | `true` ⇒ the entire file is in scope (new / untracked file, or a `--files` override later). Mutually exclusive with `ranges`. |
| `files[].ranges`  | Non-empty array of `{ start, end }`, **1-based, inclusive**, sorted, non-overlapping, non-adjacent (merged). Present iff `wholeFile` is absent. |

Exactly one of `wholeFile: true` or a non-empty `ranges` is present per entry. A
consumer that only intersects line spans may treat `wholeFile` as an unbounded
range `[1, ∞)`.

## Determinism

The file is stable for a given changeset: `files` is path-sorted, `ranges` is
line-sorted and merged, and the JSON is pretty-printed with a trailing newline.
Golden-file assertions on `scope.json` are appropriate (Testing Decisions, #23).

## Git-derived default (this is what zero-config produces)

- Base ref: the **merge-base of `HEAD` with the default branch** (`origin/HEAD`,
  falling back to `origin/main` / `origin/master` / `main` / `master`).
- Changeset: working-tree diff against that base — **staged + unstaged +
  untracked** all count.
- New and untracked files enter as `wholeFile`. Deleted files drop out.
- Rename detection is **on** (`--find-renames`, passed explicitly so output does
  not depend on the user's `diff.renames` config). A renamed file is **followed**
  to its new path and carries only its changed line ranges — a renamed-then-
  edited file is not re-mutated whole. A pure rename (no content change)
  contributes nothing. A rename git cannot pair up (content rewritten past the
  similarity threshold) degrades safely to a drop + a `wholeFile` add.
- Unrelated histories (no merge-base between `HEAD` and the default branch) are
  a hard error, not a silent whole-branch diff.
- Filter: production Kotlin only — `.kt` files outside test source sets and
  outside `build` / `out` / `target` / `.gradle` / `generated` directories.
  (`.kts` build scripts are excluded.)
- Empty changeset ⇒ `{ "version": 1, "files": [] }`, exit success, zero mutants.

## Explicit overrides

An explicit override **fully replaces** git — the git changeset is never
consulted when one is present (ADR-0002). All three still normalise to the same
`scope.json` shape above.

| Producer            | Effect                                                                                                                   |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `--files <globs>`   | Whole-file sugar. Every production Kotlin source under the repo that a pattern matches enters as `wholeFile`. Patterns match repo-root-relative `/`-separated paths and support `*` (within a segment), `**` (across segments) and `?`; a pattern with no `/` also matches by basename anywhere. The candidate list is a work-tree walk (`build` / `out` / `target` / `.gradle` / `generated` and dot-directories are not descended); git is not run. A pattern that matches nothing is a hard error. |
| `--scope <file>`    | Precise line ranges read straight from an existing `scope.json`. Ranges pass through unchanged apart from re-normalisation (sorted, merged, path-sorted). A missing or malformed file is a hard error. |
| `--since <ref>`     | Not a replacement for git — it swaps the ref the base merge-base is taken with. `--since HEAD` collapses to "working-tree changes only"; `--since <branch>` compares against that branch's divergence point. An unknown ref, or one with no common ancestor with `HEAD`, is a hard error. |
