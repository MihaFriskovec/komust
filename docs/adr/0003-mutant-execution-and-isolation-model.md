# ADR 0003: Mutant execution and isolation model

- **Status:** Accepted
- **Date:** 2026-08-29
- **Resolves:** [#7 — Mutant execution and isolation model](https://github.com/MihaFriskovec/komust/issues/7)
- **Builds on:** [#2 — Prove the compile-once IR mutation core](https://github.com/MihaFriskovec/komust/issues/2), [#3 — Per-test coverage collection](https://github.com/MihaFriskovec/komust/issues/3), [#5 — Agent-facing output contract](https://github.com/MihaFriskovec/komust/issues/5)

## Context

The IR spike (#2) proved a K2 compiler plugin can inject a **compile-once, runtime-switchable mutant** — each site `a + b` becomes `if (mutantActive("<id>")) a - b else a + b`, both branches in one artifact, flipped per run with no recompile. The spike switched mutants via a **thread-local single-slot registry**, chosen specifically so a shared JVM could run many mutants concurrently on different threads (thread A = mutant, thread B = original).

That compile-once win is real and we keep it. But turning the spike into a production execution engine forces a decision the spike deferred: **how is each mutant isolated from every other during test execution?** Two forces make shared-JVM-with-thread-parallelism (the spike's implied direction) unsafe:

1. **Non-terminating mutants cannot be killed inside a shared JVM.** A boundary or increment mutation (`i < n` → `i <= n`, `i++` → `i--`) can make a loop never return. The JVM has no safe way to stop a running thread (`Thread.stop` is unsafe; `interrupt()` does nothing to a tight compute loop with no blocking points). The only reliable kill is killing the **OS process**.
2. **Static/global state in the code under test leaks across mutants.** The mutant switch is isolated, but the tested code is not — singletons, static caches, temp files. Two mutants sharing a JVM can contaminate each other's verdicts.

## Decision

Adopt a **forked JVM worker pool**, modelled on pitest and adapted to the compile-once artifact.

### Isolation architecture

- A **controller** process owns a single authoritative **mutant work queue** and forks a pool of **worker** JVMs (default: one per CPU core, configurable).
- Each worker **pulls one mutant at a time** (work-stealing — a fast worker is never blocked behind a slow one) and runs that mutant to completion before pulling the next. A worker reuses its loaded classes across many mutants; the compile-once artifact means no recompile, ever.
- Parallelism comes from **multiple worker processes**, not multiple threads in one JVM. The process boundary is what makes both a clean hang-kill and hard cross-mutant state isolation possible.

### Switch-state scope: process-global, not thread-local

Because a worker runs exactly one mutant at a time, the spike's reason for a *thread-local* slot (disambiguating concurrent mutants) no longer applies. We switch to a **process-global `volatile` single slot**. This is simpler and fixes a latent bug in the thread-local design: under thread-local, a mutant is active only on the thread that set it, so any thread the code under test spawns (`Executor`, `Thread`, coroutine dispatcher) silently runs the **original**, under-killing. A process-global slot fires the mutation wherever the code actually runs. The registry stays behind a small interface (a documented seam) so a thread-scoped slot could return if in-JVM parallel mutants are ever revived.

### Green baseline (precondition)

The mutation run **reuses the #3 coverage pass** as its baseline: one unmutated run of the full suite with the JaCoCo agent + JUnit `TestExecutionListener`, which also records per-test timing. That baseline **must be green** — any failing or erroring test aborts the run with a clear error, because a kill cannot be attributed against an already-red suite.

### Per-mutant test execution

- A mutant runs only its **covering tests** (from #3), ordered **fastest-first** (using the baseline timing) to reach a killing test as cheaply as possible.
- **Fail-fast:** the first killing test ends the mutant (outcome is binary — killed or survived); remaining covering tests are skipped.
- **Timeout budget** is **baseline-relative**: `timeout = base_constant + factor × normal_test_time`, falling back to a fixed ceiling when a test has no baseline time. This adapts to genuinely slow-but-correct tests instead of flagging them as hangs.

### Hang detection and worker recovery

- A worker emits `START <mutantId>` before running and `RESULT <mutantId> <outcome>` after, streaming results to the controller.
- Each test runs on a harness thread with `join(timeout)`. On timeout the worker reports the mutant as **timed-out**, then **recycles itself** (exits) because the runaway thread is unkillable and would keep burning CPU / holding locks; the controller respawns a fresh worker that pulls the next queue item.
- **Backstop:** a controller-side heartbeat watchdog kills any worker silent beyond `timeout + grace`. On any worker death, the `START`-without-`RESULT` mutant is the hang → recorded as timed-out; unstarted queue items remain for other workers.

### Outcome taxonomy (reconciled with #5)

The engine internally distinguishes: test-failure kill, **memory error** (OOM / `StackOverflowError`), **timeout**, survived, and no-coverage. These map onto #5's existing `report.json` status enum `SURVIVED | KILLED | NO_COVERAGE | TIMEOUT` — **no schema change**:

| Engine outcome        | `report.json` status | Notes                                                        |
| --------------------- | -------------------- | ----------------------------------------------------------- |
| test failure/exception| `KILLED`             | a covering test failed or threw                              |
| memory error (OOM/SOE)| `KILLED`             | internal cause tracked only to **recycle the worker** (heap may be unstable) |
| timeout               | `TIMEOUT`            | scored as killed                                            |
| all covering pass     | `SURVIVED`           |                                                             |
| no covering tests     | `NO_COVERAGE`        | not run                                                     |

Both timeout and memory error **count as killed** for scoring — the mutant produced detectable behavioral divergence.

## Consequences

- **We trade raw throughput for correctness.** A shared-JVM thread-parallel sweep would avoid fork/classload cost, but we accept per-worker startup (amortized across the many mutants each worker processes) in exchange for safe hang-kills and leak-free isolation.
- **The spike's thread-local switch is superseded** by a process-global slot; the spike branch's registry must be revised before it becomes production code.
- **#9 (Gradle plugin) inherits** a controller/worker process model to wire into Gradle's build/test infrastructure.
- **Known risk — stack-trace line fidelity (deferred from #2):** mutated-bytecode line numbers for runtime stack traces are unverified. This is **not** a v1 correctness blocker: a survivor's reported `location` comes from compile-time IR metadata (`file, line, col, ordinal`, per #2/#5), not from a runtime trace, so an off-by-a-line trace never corrupts agent output — it only affects a human reading a raw failure. Mitigation: preserve the site's original `startOffset` on the injected `if/else` and verify empirically during implementation.

## Alternatives considered

- **(A) Shared JVM, mutants across threads** — the spike's implied direction. Rejected: cannot safely kill a non-terminating mutant, and non-thread-safe test code + static state leaks across concurrently-running mutants.
- **(C) Single forked JVM, mutants sequential** — trivially correct but no parallelism; too slow for real projects.
