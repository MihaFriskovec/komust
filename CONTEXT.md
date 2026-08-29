# Context: komust

A Kotlin-native (Kotlin/JVM) mutation-testing tool — "pitest for Kotlin, built AI-native". This file is the glossary. Implementation decisions live in `docs/adr/`; effort planning lives on the GitHub issue tracker (the wayfinder map, #1).

## Glossary

### Incremental run
A run that reuses prior mutant outcomes from the **mutant-result cache** instead of re-executing every in-scope mutant, so the agent inner-loop (edit → re-run → read survivors) only pays for the mutants whose result could actually have changed. The opposite is a cold run, where every in-scope mutant is executed. Incrementality is an *optimisation over which mutants are executed*; it never changes which mutants are in the report — that is the **Mutation Scope**'s job. See ADR-0003.

### Mutant-result cache
The **best-effort** store of prior mutant execution outcomes, keyed by mutant `id`, reused across runs to skip re-executing a mutant whose determinants are unchanged. Best-effort means a miss is always safe: a cold, wiped, or version-skewed cache costs time, never correctness — the answer is always defined by *actually executing the mutant*. The cache holds only the **execution-derived** half of a mutant's result (its status and per-covering-test kill outcomes); everything a compile can rederive (location, operator, mutation description, summary) is regenerated fresh each run. See ADR-0003.

### Validity fingerprint
The per-mutant hash of the determinants whose change could flip a mutant's outcome: the **enclosing symbol**'s source, the content of each **covering test**, the operator/komust version, and the environment (Kotlin + JDK version). A cached outcome is reusable only when both the mutant `id` **and** its validity fingerprint match; any determinant change is a cache miss and re-executes the mutant. The fingerprint is what lets "the agent added one test" re-run only the affected mutants while every other symbol stays a cache hit. See ADR-0003.
