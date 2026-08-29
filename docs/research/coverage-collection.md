# Per-test line coverage collection on Kotlin/JVM

Research for [#3](https://github.com/MihaFriskovec/komust/issues/3) (part of map [#1](https://github.com/MihaFriskovec/komust/issues/1)).

**Question:** How should komust collect *per-test line coverage* on Kotlin/JVM so that each
mutant can run only the tests that execute its mutated line (coverage-mapped selection)?

## Summary / recommendation

Run a **one-time coverage pass** under the **JaCoCo runtime agent**, driven by a **JUnit
Platform `TestExecutionListener`** that calls `IAgent.getExecutionData(true)` (dump + reset)
around each leaf test. Analyse each per-test `.exec` snapshot offline with the JaCoCo
`Analyzer`/`CoverageBuilder` against the compiled classes to get covered lines per class,
then invert into a `(class, line) → {test unique ids}` index.

- **Join key:** `(binary class name, source line number)` → set of
  `TestIdentifier.getUniqueId()`. Line side comes from the JaCoCo `Analyzer`; test side is
  the JUnit Platform unique id, which is stable and re-executable via a `UniqueIdSelector`.
- **Cost:** one full instrumented, **sequential** run of the suite (~1.3–2× a normal run),
  plus a short offline analysis. Paid once per source snapshot and cached, not once per
  mutant.
- **Version pin:** JaCoCo ≥ **0.8.13** for Kotlin `inline`/`reified`/`when` filters;
  prefer the latest **0.8.15** (2026-06-04) for the newest coroutine / value-class /
  `JvmStatic` filters.

Why JaCoCo over rolling our own probes (pitest-style): it is a maintained primary
dependency carrying the only actively-maintained *Kotlin* bytecode-filter set. Why the JUnit
listener over per-test JVM forking: one sequential JVM + reset/dump is far cheaper and the
listener is the JUnit-native, runner-seam-friendly hook that map #1 already committed to.

---

## (a) JaCoCo with per-test session capture

JaCoCo's runtime agent exposes a small control API,
[`org.jacoco.agent.rt.IAgent`](https://www.jacoco.org/jacoco/trunk/doc/api/org/jacoco/agent/rt/IAgent.html)
(obtained via `org.jacoco.agent.rt.RT.getAgent()` inside the test JVM, or over JMX with the
agent option `jmx=true`, which exposes MBean `org.jacoco:type=Runtime`). The interface has
exactly six methods; the ones that matter here are:

- `byte[] getExecutionData(boolean reset)` — "Returns current execution data" as a "dump of
  current execution data in JaCoCo binary format"; when `reset=true` it clears the in-memory
  probe counters afterwards.
- `void reset()` — "Resets all coverage information" without dumping.
- `void dump(boolean reset)` — "Triggers a dump of the current execution data through the
  configured output" (can throw `IOException`), optionally resetting.
- `String getSessionId()` / `void setSessionId(String)` — label a session.

Source: [`IAgent` Javadoc](https://www.jacoco.org/jacoco/trunk/doc/api/org/jacoco/agent/rt/IAgent.html),
[JaCoCo agent / JMX docs](https://www.jacoco.org/jacoco/trunk/doc/agent.html).

**Per-test session pattern:**

1. Before a leaf test starts: `agent.getExecutionData(true)` (or `reset()`) to zero the
   counters.
2. Run the single test.
3. After it finishes: `byte[] data = agent.getExecutionData(true)` — this snapshot *is* that
   test's coverage, and the reset re-arms for the next test.

The captured bytes are a JaCoCo binary stream; feed them to `ExecutionDataReader` →
`ExecutionDataStore` + `SessionInfoStore`. To turn probe hits into **source lines**, run the
JaCoCo `Analyzer` over the compiled `.class` files with that store; it emits
`IClassCoverage`/`IMethodCoverage`/`ILine`, where each `ILine` carries covered/not status
(and branch counts) per source line number. JaCoCo probes are inserted at
control-flow-relevant bytecode and attributed back to line numbers via the class file's
`LineNumberTable`; the granularity the analyzer delivers is **per source line**. Source:
[JaCoCo API examples index](https://www.jacoco.org/jacoco/trunk/doc/api.html) (the
`CoreTutorial` / `ReportGenerator` examples show the
`ExecutionDataStore` → `Analyzer` → `CoverageBuilder` pipeline).

**Important isolation constraint:** JaCoCo counters are **global per JVM**, so per-test
isolation requires tests to run **sequentially in a single JVM** during the coverage pass
(disable parallel test execution for that pass), or one-JVM-per-test (far slower).
Reset/dump between tests is precisely what makes an inherently global counter behave
per-test. The `jacoco:report` "per session id" approach is the *report-time* analogue of the
same idea (each `SessionInfo` is a labelled slice), but for komust the in-process
reset+dump-per-test gives a cleaner, directly-keyed snapshot than parsing multi-session
`.exec` files after the fact.

## (b) JUnit Platform `TestExecutionListener` + a coverage probe

The JUnit Platform Launcher lets us register a
[`org.junit.platform.launcher.TestExecutionListener`](https://docs.junit.org/current/api/org.junit.platform.launcher/org/junit/platform/launcher/TestExecutionListener.html),
whose relevant callbacks are:

- `executionStarted(TestIdentifier)` — "invoked when execution of a test or container is
  about to begin." Fires for containers *before* their children, and **only if the test or
  container has not been skipped.**
- `executionFinished(TestIdentifier, TestExecutionResult)` — called after a test or
  container completes "regardless of the outcome" (fires for both passing and failing
  tests), and again only for non-skipped items.

`TestIdentifier` exposes a stable `getUniqueId()` (e.g.
`[engine:junit-jupiter]/[class:com.foo.BarTest]/[method:baz()]`) plus `isTest()` /
`isContainer()`. Source:
[`TestExecutionListener` Javadoc](https://docs.junit.org/current/api/org.junit.platform.launcher/org/junit/platform/launcher/TestExecutionListener.html),
[`TestExecutionListener` source](https://github.com/junit-team/junit-framework/blob/main/junit-platform-launcher/src/main/java/org/junit/platform/launcher/TestExecutionListener.java),
[`UniqueIdTrackingListener` Javadoc](https://docs.junit.org/current/api/org.junit.platform.launcher/org/junit/platform/launcher/listeners/UniqueIdTrackingListener.html)
(first-party proof that the per-test *unique id* is the canonical stable, re-selectable test
id — that listener exists specifically to emit unique ids for later re-execution).

**Wiring:** in `executionStarted`, when `identifier.isTest()`, call
`agent.getExecutionData(true)` to zero the counters; in `executionFinished`, capture
`agent.getExecutionData(true)` and associate the resulting store with
`identifier.getUniqueId()`. Only leaf `isTest()` identifiers get a probe window; container
start/finish events merely bracket them. This is the JUnit-native equivalent of what pitest
does with JUnit4 `Description`s (see (c)). The listener talks to the JaCoCo agent in-process
via `RT.getAgent()`, so the coverage pass needs the JaCoCo agent on the test JVM's command
line and `org.jacoco.agent.rt` on the test classpath. This is the cleanest fit for map #1's
"JUnit Platform only" locked decision and its swappable-runner seam.

## (c) How pitest collects per-test coverage, and Kotlin fit

pitest does **not** use JaCoCo. It ships its **own** ASM-based instrumentation and a global
hit store, `sun.pitest.CodeCoverageStore`, driven per test by `CoveragePipe`:

- **Probe storage:** `CodeCoverageStore` keeps a `Map<Integer, boolean[]>` — one boolean
  array per class id; **index 0 is a "class visited at all" flag**, the remaining slots are
  individual probes. `registerClass()`/`registerMethod()` assign class ids and probe ranges
  (delegating to an `InvokeReceiver`).
- **Probe id encoding:** a hit is a 64-bit long combining class and probe:
  `((long) classId << 32) | probeId`.
- **Retrieval / reset:** `getHits()` iterates `CLASS_HITS` and returns the encoded longs of
  every fired probe (skipping the class-level slot); `reset()` clears arrays only for classes
  whose visited-flag is set. `getOrRegisterClassProbes()` grows a class's probe array if
  another agent retransforms it and adds synthetic blocks.
- **Per-test driver:** `CoveragePipe.recordTestOutcome(...)` calls `getHits()`, serialises
  the test `Description` + status + timing + each hit long over a socket to the parent, then
  calls `CodeCoverageStore.reset()` to clear hits before the next test — the **same
  reset-between-tests idea** as the JaCoCo approach.
- **Orchestration:** `DefaultCoverageGenerator` runs the whole pass in a **separate JVM**
  ("CoverageMinion" via a `CoverageProcess` + `ServerSocket`), collecting `CoverageResult`
  objects; a `LineMapper` maps probe/block hits back to source lines. It rejects the run if
  tests fail without mutation ("Tests failing without mutation").

Sources:
[`sun/pitest/CodeCoverageStore.java`](https://github.com/hcoles/pitest/blob/master/pitest/src/main/java/sun/pitest/CodeCoverageStore.java),
[`CoveragePipe.java`](https://github.com/hcoles/pitest/blob/master/pitest/src/main/java/org/pitest/coverage/execute/CoveragePipe.java),
[`DefaultCoverageGenerator.java`](https://github.com/hcoles/pitest/blob/master/pitest-entry/src/main/java/org/pitest/coverage/execute/DefaultCoverageGenerator.java),
[PR #534 — block coverage / mutant-test pairs on blocks not lines](https://github.com/hcoles/pitest/pull/534).

**Rationale pitest itself gives** (from PR #534 discussion): retrieving each class's probe
array from the `CLASS_HITS` map is non-trivial overhead, so a compact global hit buffer that
is reset per test — rather than reading every instrumented class's fields at each test end —
is the chosen tradeoff. komust inherits the same constraint whichever probe it uses.

**Kotlin mapping:** pitest's per-test coverage maps to **JVM bytecode line numbers**, so it
inherits *exactly* Kotlin's line-attribution quirks (see (d)) with no Kotlin-specific line
remapping. pitest's own Kotlin story is architecturally weak (the reason map #1 exists), and
its `pitest-kotlin` plugin was archived in 2023. **Takeaway:** copy pitest's *mechanism*
(per-test probe capture → reset → line map → line→tests index) but not its engine; on Kotlin,
JaCoCo ≥ 0.8.13 has materially better Kotlin line filtering than pitest's raw line map, which
is why JaCoCo is the recommended probe.

## (d) Kotlin-specific coverage gotchas

All stem from the Kotlin compiler emitting synthetic/duplicated bytecode and line-number
tables that don't match a naive "one source line = one region" model. JaCoCo has been adding
**bytecode filters** release by release to correct these; pin a recent version. Source:
[JaCoCo Change History](https://www.jacoco.org/jacoco/trunk/doc/changes.html),
[issue #654 — inline fns not covered](https://github.com/jacoco/jacoco/issues/654),
[#1922 — inline/reified coverage regression](https://github.com/jacoco/jacoco/issues/1922).
Exact version attributions below are from the change history:

- **`inline` functions:** the inlined body's bytecode is copied into each call site, and its
  line numbers point back (via SMAP / `LineNumberTable`) to the *declaration* file/line, so
  an inline function can read as uncovered even when exercised, and caller lines get polluted.
  JaCoCo added **line coverage calculation for `inline` functions, and for `inline` functions
  with `reified` type parameters, in 0.8.13** (2025-04-02). Earlier (0.8.3) it only *filtered*
  inlined instructions from branch counts.
- **`when` expressions:** Kotlin emits extra synthetic branches. **Nullable-enum** and
  **nullable-String** subjects each add a null check — both **filtered in 0.8.13**; enum/sealed
  implicit-`else` was filtered back in 0.8.2, and `when`-on-String has had refinements through
  0.8.15 ("first branch with the largest hash").
- **Synthetic / bridge & `@JvmSynthetic` methods:** coverage calculation for Kotlin
  `JvmSynthetic` functions was added in **0.8.13**; Kotlin interface compatibility methods and
  `@JvmStatic`-generated methods are filtered in **0.8.15**. General synthetic/bridge filtering
  keeps these from skewing counts.
- **`suspend` / coroutines:** suspending lambdas *without* suspension points filtered in
  **0.8.13**; suspending lambdas *with parameters*, `suspendCoroutineUninterceptedOrReturn`
  intrinsic calls, and suspend functions returning *inline value classes* filtered in
  **0.8.14** (2025-10-11). (Basic suspend/tail-call branch filtering dates to 0.8.3–0.8.6.)
  Map #1 also flags coroutines/`suspend` as an unresolved hard edge for the mutation engine.
- **`data class` / default args / inline value classes:** generated `componentN`/`copy`, the
  `$default` argument-branch shims (default arg #33+ filtered in **0.8.14**; 32+ param cases in
  0.8.12), and inline value-class boxing all emit lines with no meaningful source; filters land
  across **0.8.12–0.8.15**.

**Kover (Kotlin's own tool).** [kotlinx-kover](https://github.com/Kotlin/kotlinx-kover) is
JetBrains' Kotlin-first coverage toolset. By default it uses the **IntelliJ coverage engine
(`intellij-coverage`)** — the same class-load-time bytecode agent IntelliJ IDEA ships — and
offers **JaCoCo as an alternative backend**. The IntelliJ engine is Kotlin-aware natively
(it is what powers IDEA's Kotlin coverage gutters), and IDEA Ultimate's coverage runner
supports **per-test coverage** ("click a line to see which tests executed it"), which is
architecturally the exact capability komust needs. Kover also documents
[offline instrumentation](https://kotlin.github.io/kotlinx-kover/offline-instrumentation/)
(instrumenting on-disk class files) via a CLI and a JVM agent, for environments without a
Java agent. Sources: [Kover README](https://github.com/Kotlin/kotlinx-kover),
[Kover Gradle plugin docs](https://kotlin.github.io/kotlinx-kover/gradle-plugin/),
[offline instrumentation docs](https://kotlin.github.io/kotlinx-kover/offline-instrumentation/).

*Why not adopt Kover/`intellij-coverage` as the primary probe now:* its per-test capability
is exposed through the IDEA runner rather than a small, documented public control API like
JaCoCo's `IAgent`, and its programmatic per-test surface for a headless CLI tool is not
first-party-documented. JaCoCo's `IAgent.getExecutionData(reset)` gives a clean, supported,
scriptable per-test snapshot today. Kover/`intellij-coverage` is the natural **fallback
backend** if JaCoCo's Kotlin filters prove insufficient — and it validates the design: the
Kotlin team's own tool solves per-test line coverage with a reset/instrumentation engine, not
by inventing new line semantics.

**Consequence for the join key:** because inline expansion attributes callee lines to the
declaration site, coverage-mapped selection must treat a mutated *inline function's* line as
covered by any test that reached **any call site** of it — i.e. resolve inline expansion when
building the mutant→line index, or accept a broader fallback test set for inline-function
mutants. This is the sharpest Kotlin risk for the downstream selection ticket.

---

## Deliverable 1 — Recommended mechanism

**JaCoCo runtime agent + JUnit Platform `TestExecutionListener`, in a dedicated one-time
coverage pass.**

1. Compile the target + tests normally (unmutated).
2. Launch the test suite in a **single, sequential** JVM with the **JaCoCo agent** attached
   and `org.jacoco.agent.rt` on the classpath.
3. Register a `TestExecutionListener`. On `executionStarted` of a leaf `isTest()` id:
   `agent.getExecutionData(true)` (zero the counters). On `executionFinished`: capture
   `agent.getExecutionData(true)` bytes, keyed by `TestIdentifier.getUniqueId()`.
4. Offline, for each per-test `.exec` snapshot, run JaCoCo `Analyzer` + `CoverageBuilder`
   over the compiled classes → covered source lines per class.
5. Invert into a **`(class, line) → {test unique ids}`** index; persist/cache it keyed by the
   source snapshot (git tree / content hash) for the mutant-selection ticket.

**Tradeoffs vs alternatives.** JaCoCo over rolling our own probes (pitest-style): JaCoCo is a
maintained primary dependency with the only actively-maintained *Kotlin* bytecode-filter set
(0.8.13–0.8.15), directly addressing the (d) gotchas we'd otherwise re-implement — pitest's
raw `LineMapper` has none of these. JUnit listener over per-test JVM forking (pitest's
CoverageMinion model): one sequential JVM + reset/dump is far cheaper than forking, and the
listener is the JUnit-native hook map #1 committed to. Kover/`intellij-coverage` kept as a
fallback backend if JaCoCo's Kotlin filtering falls short (see (d)).

## Deliverable 2 — Join key

`(binaryClassName, sourceLineNumber)` → set of `TestIdentifier.getUniqueId()`.

- **Line side:** binary/internal class name + source line number, produced by the JaCoCo
  `Analyzer` from each per-test `.exec` against the compiled classes (probe → line via the
  `LineNumberTable`). This is the same line identity the K2 IR mutation engine must emit for a
  mutant (a mutant carries the class + source line it sits on), so the two indices join
  directly.
- **Test side:** JUnit Platform `TestIdentifier.getUniqueId()` — stable, first-party, and
  **re-executable**: hand it straight back to the Launcher via a `UniqueIdSelector` to run
  exactly the covering tests. (The presence of `UniqueIdTrackingListener` in the platform is
  first-party confirmation this id is the intended durable test handle.)
- **Kotlin caveat:** normalise inline-function lines (map callee declaration line ↔ call
  sites) before the join, per (d), or fall back to a broader covering set for inline mutants.
- **Survives desugaring** because both sides are computed *after* Kotlin → JVM compilation:
  the analyzer reads real bytecode line tables (post-filter), and the mutant is defined on the
  same compiled classes, so no source-vs-bytecode mismatch is introduced by the join itself —
  only inline expansion needs the normalisation above.

## Deliverable 3 — Cost of the one-time coverage pass

- **Runtime:** one full instrumented run of the whole suite. JaCoCo on-the-fly instrumentation
  adds modest overhead; expect roughly **~1.3–2× wall-clock** vs a normal run, dominated by
  the requirement to run **sequentially** (no test parallelism) so per-test reset/dump stays
  isolated. `reset` + `getExecutionData` per test is cheap relative to test bodies.
- **Separate JVM run?** It is a **distinct JVM run performed once, before mutation** (like
  pitest's coverage minion) — not per mutant. The output (the inverted index) is what the
  mutation loop consumes.
- **Analysis:** offline `Analyzer` pass over N per-test snapshots, linear in
  (tests × classes touched); seconds-to-low-minutes for typical modules, off the critical
  mutation loop.
- **Memory/storage:** N per-test `.exec` blobs (small, resettable) reduced to one inverted
  index; cache keyed by source snapshot so the pass is paid **once per code change**, not once
  per mutant — the whole point of coverage-mapped selection.
- **Amortisation:** cost is incurred once and reused across every mutant in that snapshot; the
  mutation run then executes, per mutant, only the covering test set instead of the full suite.

---

## Open follow-ups for the selection ticket

- Inline-function line normalisation strategy (resolve call sites vs. conservative fallback).
- Snapshot/cache key definition (git tree hash vs. per-file content hash) for reuse across
  incremental runs.
- Handling tests with **no** recorded coverage for a mutated line (skip vs. always-run safety
  net).
- A parallel-suite coverage pass would need one-JVM-per-shard aggregation if sequential
  becomes too slow.
- Spike Kover/`intellij-coverage` as a fallback backend and compare its Kotlin line data to
  JaCoCo 0.8.15 on inline/`suspend` cases.

## Sources

- [JaCoCo `IAgent` Javadoc](https://www.jacoco.org/jacoco/trunk/doc/api/org/jacoco/agent/rt/IAgent.html)
- [JaCoCo agent / JMX docs](https://www.jacoco.org/jacoco/trunk/doc/agent.html)
- [JaCoCo API examples index](https://www.jacoco.org/jacoco/trunk/doc/api.html)
- [JaCoCo Change History (Kotlin filters)](https://www.jacoco.org/jacoco/trunk/doc/changes.html)
- [JaCoCo issue #654 — Kotlin inline functions not covered](https://github.com/jacoco/jacoco/issues/654)
- [JaCoCo issue #1922 — inline/reified coverage regression](https://github.com/jacoco/jacoco/issues/1922)
- [JUnit `TestExecutionListener` Javadoc](https://docs.junit.org/current/api/org.junit.platform.launcher/org/junit/platform/launcher/TestExecutionListener.html)
- [JUnit `TestExecutionListener` source](https://github.com/junit-team/junit-framework/blob/main/junit-platform-launcher/src/main/java/org/junit/platform/launcher/TestExecutionListener.java)
- [JUnit `UniqueIdTrackingListener` Javadoc](https://docs.junit.org/current/api/org.junit.platform.launcher/org/junit/platform/launcher/listeners/UniqueIdTrackingListener.html)
- [pitest `sun/pitest/CodeCoverageStore.java`](https://github.com/hcoles/pitest/blob/master/pitest/src/main/java/sun/pitest/CodeCoverageStore.java)
- [pitest `CoveragePipe.java`](https://github.com/hcoles/pitest/blob/master/pitest/src/main/java/org/pitest/coverage/execute/CoveragePipe.java)
- [pitest `DefaultCoverageGenerator.java`](https://github.com/hcoles/pitest/blob/master/pitest-entry/src/main/java/org/pitest/coverage/execute/DefaultCoverageGenerator.java)
- [pitest PR #534 — block coverage / mutant-test pairs](https://github.com/hcoles/pitest/pull/534)
- [Kover (kotlinx-kover) README](https://github.com/Kotlin/kotlinx-kover)
- [Kover Gradle plugin docs](https://kotlin.github.io/kotlinx-kover/gradle-plugin/)
- [Kover offline instrumentation docs](https://kotlin.github.io/kotlinx-kover/offline-instrumentation/)
