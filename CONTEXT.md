# Context: komust

A Kotlin-native (Kotlin/JVM) mutation-testing tool — "pitest for Kotlin, built AI-native". This file is the glossary. Implementation decisions live in `docs/adr/`; effort planning lives on the GitHub issue tracker (the wayfinder map, #1).

## Glossary

### Mutant
A single, deliberate change to the program under test, produced by applying one **mutation operator** at one site. komust uses **compile-once, runtime-switchable** mutants: all mutants are woven into one compilation and selected at runtime by a guard, never recompiled per mutant.

### Mutation operator (operator)
A rule that rewrites a specific Kotlin construct in the K2 IR to produce mutants (e.g. swap `+`↔`-`). An operator targets a construct; it does not merely observe it. The set of operators komust ships is the **operator catalog**.

### Operator catalog
The versioned set of operators komust applies, split into **tiers**. See ADR-0001.

### Tier
An operator's default activation state. komust v1 has two: **default** (on unless disabled) and **experimental** (off unless explicitly enabled). There is no "stronger" middle tier.

### Killed mutant
A mutant for which at least one selected test fails — the test suite detected the change. The goal of a good test.

### Survived mutant
A mutant that no selected test detected (all tests still pass). A survivor is a candidate test-quality gap and is the primary signal komust reports to an agent.

### Junk mutant
A mutant that is worthless as signal: it fails to compile, always crashes regardless of test quality, or is otherwise uninformative. komust avoids junk **by construction** (the operator never emits it), not by post-hoc detection.

### Equivalent mutant
A mutant whose behaviour is indistinguishable from the original program, so **no** test can ever kill it. A false survivor: it looks like a gap but is not one. komust v1 does not detect equivalents; it avoids operators/sites known to generate them (the **skip-list**), the same stance as pitest.

### Skip-list
The documented policy of constructs komust deliberately does **not** mutate because doing so reliably produces junk or equivalent mutants (e.g. `!!`, `TODO()`, `require`/`check`, exhaustive `when` without `else`, and the null-checks that `?:`/`?.` desugar into). The skip-list is komust's equivalent-mutant firewall. See ADR-0001.

### Spike-gated (operator)
An operator whose inclusion is decided but whose clean expression in K2 IR is not yet proven by the IR prototype (#2). If the prototype finds it awkward, it is demoted to **experimental** or the skip-list rather than reopening the catalog decision.

### Protected (construct)
A Kotlin construct that komust intentionally leaves untouched — it is on the skip-list. Distinct from "not yet supported": protection is a deliberate correctness choice. `?.`, `!!`, and desugared null-checks are protected.
