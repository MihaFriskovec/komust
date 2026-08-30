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
  Rename detection is **off** (`--no-renames`): a renamed file is a delete
  (dropped) plus an add (`wholeFile`), and output does not depend on the user's
  `diff.renames` config. Rename-*follow* (keeping only the changed lines at the
  new path) is #26.
- Unrelated histories (no merge-base between `HEAD` and the default branch) are
  a hard error, not a silent whole-branch diff.
- Filter: production Kotlin only — `.kt` files outside test source sets and
  outside `build` / `out` / `target` / `.gradle` / `generated` directories.
  (`.kts` build scripts are excluded.)
- Empty changeset ⇒ `{ "version": 1, "files": [] }`, exit success, zero mutants.

Explicit overrides (`--files`, `--scope`, `--since`) and git rename/deletion
edge-case handling are specified in #26.
