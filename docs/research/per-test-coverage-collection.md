# Per-test line coverage collection on Kotlin/JVM

Research for [#3](https://github.com/MihaFriskovec/komust/issues/3) (part of map [#1](https://github.com/MihaFriskovec/komust/issues/1)).

**Question:** How should Komust collect *per-test line coverage* on Kotlin/JVM so each
mutant can run only the tests that execute its mutated line (coverage-mapped selection)?

**TL;DR recommendation:** Run a **one-time coverage pass** under the **JaCoCo runtime
agent**, driven by a **JUnit Platform `TestExecutionListener`** that calls
`IAgent.getExecutionData(true)` (reset+dump) around each leaf test. Analyze each per-test
`ExecutionDataStore` offline with the JaCoCo `Analyzer`/`CoverageBuilder` against the
compiled classes to get covered lines per class. The **join key** is
`(binary class name, source line number)` → set of `TestIdentifier.getUniqueId()`.
Pin **JaCoCo ≥ 0.8.15** for the Kotlin bytecode filters. Cost is one full instrumented
test-suite run, roughly **1.3–2×** a normal run plus a short offline analysis, done once
per source snapshot and cached.

---

## (a) JaCoCo with per-test session capture

JaCoCo's runtime agent exposes a small control API, `org.jacoco.agent.rt.IAgent`
(obtained via `org.jacoco.agent.rt.RT.getAgent()` inside the test JVM, or over JMX with
`jmx=true` exposing bean `org.jacoco:type=Runtime`):

- `byte[] getExecutionData(boolean reset)` — returns the current execution data in
  JaCoCo's binary `.exec` format and, when `reset=true`, clears the in-memory probe
  counters afterwards.
- `void reset()` — clears all coverage counters without dumping.
- `void dump(boolean reset)` — writes current data through the agent's configured output.
- `getSessionId()` / `setSessionId(String)` — label a session.

Source: [`IAgent` Javadoc, JaCoCo 0.8.16](https://www.jacoco.org/jacoco/trunk/doc/api/org/jacoco/agent/rt/IAgent.html),
[JaCoCo FAQ (JMX runtime bean)](https://www.jacoco.org/jacoco/trunk/doc/faq.html).

**Per-test session pattern:**

1. Before a leaf test starts: `agent.getExecutionData(true)` (or `reset()`) to zero the
   counters.
2. Run the single test.
3. After it finishes: `byte[] data = agent.getExecutionData(true)` — this snapshot *is*
   that test's coverage, and the reset re-arms for the next test.

The captured bytes are a JaCoCo binary stream; feed them to
`ExecutionDataReader` → `ExecutionDataStore` + `SessionInfoStore`. To turn probe hits into
**source lines**, run the JaCoCo `Analyzer` over the compiled `.class` files with that
store; it emits `IClassCoverage`/`IMethodCoverage`/`ILine` where each `ILine` carries a
status (covered/not) per source line number. JaCoCo probes are inserted at
control-flow-relevant bytecode and then attributed back to line numbers via the class
file's `LineNumberTable`; granularity delivered by the analyzer is **per source line**
(with branch counts per line). Source:
[JaCoCo API examples index](https://www.jacoco.org/jacoco/trunk/doc/api.html) (CoreTutorial /
ReportGenerator show the `ExecutionDataStore` → `Analyzer` → `CoverageBuilder` pipeline).

**Important:** JaCoCo counters are **global per JVM**, so per-test isolation requires the
tests to run **sequentially in a single JVM** during the coverage pass (disable parallel
test execution for that pass), or one-JVM-per-test (far slower). Reset/dump between tests
is the mechanism that makes an inherently global counter behave per-test.

## (b) JUnit Platform `TestExecutionListener` + a coverage probe

The JUnit Platform Launcher lets us register a `TestExecutionListener`
(`org.junit.platform.launcher.TestExecutionListener`), whose relevant callbacks are:

- `executionStarted(TestIdentifier)` — fired for a test/container about to run (only if not
  skipped).
- `executionFinished(TestIdentifier, TestExecutionResult)` — fired when it finishes,
  regardless of outcome.

`TestIdentifier` exposes a stable `getUniqueId()` (e.g.
`[engine:junit-jupiter]/[class:com.foo.BarTest]/[method:baz()]`) plus `isTest()`/`isContainer()`.
Source:
[`TestExecutionListener` Javadoc](https://docs.junit.org/5.9.3/api/org.junit.platform.launcher/org/junit/platform/launcher/TestExecutionListener.html),
[`TestExecutionListener` source](https://github.com/junit-team/junit-framework/blob/main/junit-platform-launcher/src/main/java/org/junit/platform/launcher/TestExecutionListener.java),
[`UniqueIdTrackingListener`](https://docs.junit.org/current/api/org.junit.platform.launcher/org/junit/platform/launcher/listeners/UniqueIdTrackingListener.html)
(first-party proof that per-test unique ids are the canonical stable test id).

**Wiring:** in `executionStarted`, when `identifier.isTest()`, call
`agent.getExecutionData(true)` to zero counters; in `executionFinished`, capture
`agent.getExecutionData(true)` and associate the resulting store with
`identifier.getUniqueId()`. Only leaf `isTest()` identifiers get a probe window; container
start/finish events bracket them. This is the JUnit-native equivalent of what pitest does
with JUnit4 `Description`s (below).

The listener talks to the JaCoCo agent in-process via `RT.getAgent()`; the coverage pass
therefore needs the JaCoCo agent on the test JVM's command line and JaCoCo `agent.rt` on
the test classpath. This is the cleanest fit for map #1's "JUnit Platform only" locked
decision and swappable runner seam.

## (c) How pitest collects per-test coverage, and Kotlin fit

pitest does **not** use JaCoCo. It ships its **own** ASM-based instrumentation and a global
`CodeCoverageStore` of probe hits, driven per test by `CoveragePipe` /
`CoverageDecorator`:

- Classes are instrumented with probe arrays; `CodeCoverageStore.getHits()` returns the
  probe ids that fired.
- Each test is wrapped; on completion `recordTestOutcome()` records the test
  `Description`, pass/fail, timing, and the hit probe set, then `CodeCoverageStore.reset()`
  clears hits before the next test — the same reset-between-tests idea as the JaCoCo
  approach above.
- `DefaultCoverageGenerator` orchestrates the pass; a `LineMapper` maps probe/block hits
  back to source line numbers, and pitest then runs, for each mutated line, only the tests
  whose recorded coverage reached that line.

Sources:
[`DefaultCoverageGenerator.java`](https://github.com/hcoles/pitest/blob/master/pitest-entry/src/main/java/org/pitest/coverage/execute/DefaultCoverageGenerator.java),
`CoveragePipe.java` (registerProbes/recordTestOutcome/reset),
[PR #534 "block coverage / mutant-test-pairs on blocks not lines"](https://github.com/hcoles/pitest/pull/534).

**Design note pitest itself gives** (rationale for reset-per-test rather than reading all
class fields at test end): gathering hits from every instrumented class in the JVM at the
end of each test is expensive, so a compact global hit buffer that is reset per test is the
chosen tradeoff. We inherit the same constraint.

**Kotlin mapping:** pitest's per-test coverage maps to **JVM bytecode line numbers**, so it
inherits *exactly* Kotlin's line-attribution quirks (see (d)) — it has no Kotlin-specific
line remapping. pitest's own Kotlin support is architecturally weak (the reason map #1
exists), and its `pitest-kotlin` plugin was archived in 2023. **Takeaway:** copy pitest's
*mechanism* (per-test probe capture + reset + line map + line→tests index) but not its
engine; on Kotlin, JaCoCo ≥ 0.8.15 has materially better Kotlin line filtering than
pitest's raw line map, which is why JaCoCo is the recommended probe.

## (d) Kotlin-specific coverage gotchas

All stem from the Kotlin compiler emitting synthetic/duplicated bytecode and line-number
tables that don't match a naive "one source line = one region" model. JaCoCo has been
adding **bytecode filters** release by release to fix these; pin a recent version.
Source: [JaCoCo Change History](https://www.jacoco.org/jacoco/trunk/doc/changes.html),
[issue #654 (inline fns not covered)](https://github.com/jacoco/jacoco/issues/654),
[#1873 / #1922 (inline + reified regressions)](https://github.com/jacoco/jacoco/issues/1922).

- **`inline` functions:** the inlined body's bytecode is copied into each call site, and its
  line numbers point back to the *declaration* file/line (SMAP/`LineNumberTable`), so an
  inline function could show uncovered even when exercised, and the caller's lines get
  polluted. JaCoCo added line-coverage calculation for `inline` functions and for `inline`
  functions with `reified` type parameters in **0.8.13**, with fixes/refinements through
  **0.8.15**.
- **`when` expressions:** Kotlin emits extra synthetic branches, especially for a
  **nullable enum / nullable String subject** (an added null check). JaCoCo filters these
  in **0.8.13** (nullable enum) and **0.8.15** (nullable String) so they don't inflate
  branch/line counts.
- **Synthetic / bridge & `@JvmSynthetic` methods:** JaCoCo added coverage calculation for
  Kotlin `JvmSynthetic` functions in **0.8.15**; general synthetic-method filtering avoids
  bridge methods skewing counts.
- **`suspend` / coroutines:** suspending lambdas without suspension points filtered in
  **0.8.13**; suspending lambdas with parameters, `suspendCoroutineUninterceptedOrReturn`
  intrinsics, and suspend fns returning inline value classes filtered in **0.8.14**. Note
  map #1 flags coroutines/`suspend` as an unresolved hard edge for the mutation engine too.
- **`data class` / default args / inline value classes:** generated `componentN`/`copy`,
  the `$default` argument-branch shims, and inline value-class boxing all emit lines with no
  meaningful source; filters landed across **0.8.13–0.8.14**.

**Consequence for the join key:** because inline expansion attributes callee lines to the
declaration site, coverage-mapped selection must treat a mutated *inline function's* line as
covered by any test that reached **any call site** of it — i.e. resolve inline expansion
when building the mutant→line index, or accept that inline-function mutants fall back to a
broader test set. This is the sharpest Kotlin risk for the downstream selection ticket.

---

## Recommended mechanism

**JaCoCo runtime agent + JUnit Platform `TestExecutionListener`, in a dedicated one-time
coverage pass.**

1. Compile the target + tests normally (unmutated).
2. Launch the test suite in a **single, sequential** JVM with the **JaCoCo agent**
   attached and `org.jacoco.agent.rt` available.
3. Register a `TestExecutionListener`. On `executionStarted` of a leaf `isTest()` id:
   `agent.getExecutionData(true)` (zero). On `executionFinished`: capture
   `agent.getExecutionData(true)` bytes, keep them keyed by `TestIdentifier.getUniqueId()`.
4. Offline, for each per-test `.exec` snapshot, run JaCoCo `Analyzer` +
   `CoverageBuilder` over the compiled classes → covered source lines per class.
5. Invert into a **`(class, line) → {test unique ids}`** index; persist/cache it keyed by
   the source snapshot (git tree / content hash) for the mutant-selection ticket #8.

Why JaCoCo over rolling our own probes (pitest-style): JaCoCo is a maintained primary
dependency with the **only actively-maintained Kotlin bytecode-filter set** (0.8.13–0.8.15),
directly addressing the (d) gotchas we'd otherwise re-implement. Why the JUnit listener over
per-test JVM forking: one sequential JVM + reset/dump is far cheaper and the listener is the
JUnit-native, runner-seam-friendly hook map #1 already committed to.

## Join key

`(binaryClassName, sourceLineNumber)` → set of `TestIdentifier.getUniqueId()`.

- **Line side:** binary/internal class name + line number, produced by the JaCoCo
  `Analyzer` from each per-test `.exec` against the compiled classes. This is the same line
  identity the K2 IR mutation engine must emit for a mutant (mutant carries the class +
  source line it sits on), so the two indices join directly.
- **Test side:** JUnit Platform `TestIdentifier.getUniqueId()` — stable, first-party,
  re-executable (can be handed straight back to the Launcher via a `UniqueIdSelector` to run
  exactly the covering tests).
- **Kotlin caveat:** normalize inline-function lines (map callee decl line ↔ call sites)
  before the join, per (d).

## Cost of the one-time coverage pass

- **Runtime:** one full instrumented run of the whole suite. JaCoCo's on-the-fly
  instrumentation typically adds modest overhead; expect roughly **~1.3–2× wall-clock** vs a
  normal run, dominated by the requirement to run **sequentially** (no parallelism) so
  per-test reset/dump stays isolated. Reset+`getExecutionData` per test is cheap relative to
  test bodies.
- **Analysis:** offline `Analyzer` pass over N per-test snapshots — linear in
  (tests × classes touched); seconds-to-low-minutes for typical modules, off the critical
  mutation loop.
- **Memory/storage:** N per-test `.exec` blobs (small, resettable) reduced to one inverted
  index; cache keyed by source snapshot so the pass is paid **once per code change**, not
  once per mutant — the whole point of coverage-mapped selection.
- **Amortization:** cost is incurred once and reused across every mutant in that snapshot;
  the mutation run then executes, per mutant, only the covering test set instead of the full
  suite.

---

## Open follow-ups for the selection ticket (#8)

- Inline-function line normalization strategy (resolve call sites vs. conservative fallback).
- Snapshot/cache key definition (git tree hash vs. per-file content hash) for reuse across
  incremental runs.
- Handling tests with **no** recorded coverage for a mutated line (skip vs. always-run
  safety net).
- Parallel-suite coverage pass would need one-JVM-per-shard aggregation if sequential
  becomes too slow.

## Primary sources

- [JaCoCo `IAgent` Javadoc](https://www.jacoco.org/jacoco/trunk/doc/api/org/jacoco/agent/rt/IAgent.html)
- [JaCoCo API examples index](https://www.jacoco.org/jacoco/trunk/doc/api.html)
- [JaCoCo FAQ (JMX runtime bean)](https://www.jacoco.org/jacoco/trunk/doc/faq.html)
- [JaCoCo Change History (Kotlin filters)](https://www.jacoco.org/jacoco/trunk/doc/changes.html)
- [JaCoCo issue #654 — Kotlin inline functions not covered](https://github.com/jacoco/jacoco/issues/654)
- [JaCoCo issue #1922 — inline/reified coverage regression](https://github.com/jacoco/jacoco/issues/1922)
- [JUnit `TestExecutionListener` Javadoc](https://docs.junit.org/5.9.3/api/org.junit.platform.launcher/org/junit/platform/launcher/TestExecutionListener.html)
- [JUnit `TestExecutionListener` source](https://github.com/junit-team/junit-framework/blob/main/junit-platform-launcher/src/main/java/org/junit/platform/launcher/TestExecutionListener.java)
- [JUnit `UniqueIdTrackingListener` Javadoc](https://docs.junit.org/current/api/org.junit.platform.launcher/org/junit/platform/launcher/listeners/UniqueIdTrackingListener.html)
- [pitest `DefaultCoverageGenerator.java`](https://github.com/hcoles/pitest/blob/master/pitest-entry/src/main/java/org/pitest/coverage/execute/DefaultCoverageGenerator.java)
- [pitest PR #534 — block coverage / mutant-test pairs](https://github.com/hcoles/pitest/pull/534)
