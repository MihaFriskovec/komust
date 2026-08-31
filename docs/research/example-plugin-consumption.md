# How an in-repo example consumes the under-development `io.komust` Gradle plugin

Research for [#54](https://github.com/MihaFriskovec/komust/issues/54) (part of map
[#53](https://github.com/MihaFriskovec/komust/issues/53)).

**Question:** How does an in-repo example project consume a Gradle plugin that is still
under development in the same repository, what must `komust-gradle-plugin` declare for it to
work, what are the alternatives, and what does prior art do? Is the existing
`MutationTestSmokeTest` test-maven-repo approach reusable for a real example?

## Summary / recommendation

Consume the plugin **from source via a composite build**, with **no version string** in the
example's `plugins {}` block. The mechanism is Gradle's automatic dependency substitution of
the **plugin marker artifact** `io.komust:io.komust.gradle.plugin` against the project that
produces it — which works only because `komust-gradle-plugin` already applies
`java-gradle-plugin` and declares `gradlePlugin { plugins { create("komust") { id =
"io.komust" } } }`. No publish step, always tracks current source, green on a fresh clone.

The blocking constraint: **a plugin built by a module in a build cannot be applied via
`plugins {}` to another project in that same build.** komust currently has all four modules
(including `komust-gradle-plugin`) in the root build, so the example cannot simply be
`include(":komust-example")`. Two layouts resolve this:

- **Recommended (low disruption) — example as its own build that includes the komust root.**
  `komust-example/` gets its own `settings.gradle.kts` with
  `pluginManagement { includeBuild("../") }`. The root build is unchanged. CI runs the
  example with an explicit step (`./gradlew -p komust-example mutationTest`), mirroring how
  `gradle-pitest-plugin`/`cortinico` invoke their out-of-aggregate builds. The root build
  must **not** `includeBuild("komust-example")` (that would create an include cycle with the
  example's `includeBuild("../")`).
- **Alternative (full prior-art alignment) — relocate the plugin into an included build.**
  Move `komust-gradle-plugin` (+ `komust-scope`, `komust-compiler-plugin`, `komust-engine`,
  or a published subset) into `plugin-build/`, `includeBuild("plugin-build")` from the root,
  and add the example as a normal root subproject (`include(":komust-example")`). This is
  exactly the `ktlint-gradle` / `kotlin-gradle-plugin-template` layout and puts the example
  in the aggregate `check`, but it is a build restructure and touches the module graph that
  #24/#38 established.

**`MutationTestSmokeTest`'s test-maven-repo is test-only scaffolding — do not reuse it for
the example.** It publishes four `-SNAPSHOT` modules into `build/test-maven/` from the
`test` task and passes `--refresh-dependencies` to dodge stale metadata. An example wired
that way would fail on a fresh `git clone` until someone runs the publish task, and would
silently resolve a stale SNAPSHOT otherwise. The composite build has neither problem.

---

## 1. The mechanism: plugin marker substitution in a composite build

### 1.1 What the `plugins {}` block actually resolves

When a build script declares `plugins { id("io.komust") }`, Gradle does not look for an
artifact named `io.komust`. It looks for a **Plugin Marker Artifact** with coordinates
`plugin.id:plugin.id.gradle.plugin` — here **`io.komust:io.komust.gradle.plugin`** (the
exact coordinate named in #54). The marker's POM/module metadata carries a single
dependency: on the real plugin implementation artifact. Gradle resolves the marker, follows
that dependency, puts the implementation on the build's script classpath, then applies it.
([Gradle — Plugin Marker Artifacts](https://docs.gradle.org/current/userguide/plugins_intermediate.html),
[Gradle — Working with Plugins](https://docs.gradle.org/current/userguide/plugins_intermediate.html))

### 1.2 Why `java-gradle-plugin` is load-bearing

The `java-gradle-plugin` plugin, from the `gradlePlugin { plugins { … } }` block, generates
"multiple 'marker' publications (one for each plugin defined in the `gradlePlugin {}`
block)" and "configures the Plugin Marker Artifact publications … for each plugin" when a
publishing plugin is present.
([Gradle — Java Gradle Plugin Development](https://docs.gradle.org/current/userguide/java_gradle_plugin.html))

In a **composite build**, "dependencies declared in the `plugins {}` block … are
substituted in the same way as other dependencies." Gradle inspects each included build,
sees that it *produces* the `io.komust:io.komust.gradle.plugin` marker (because of the
`gradlePlugin` declaration), and substitutes the external marker dependency for a project
dependency on that included build — no marker is ever downloaded or published.
([Gradle — Composite Builds](https://docs.gradle.org/current/userguide/composite_builds.html),
[gradle/gradle#1518](https://github.com/gradle/gradle/issues/1518))

`komust-gradle-plugin/build.gradle.kts` **already declares everything needed:**

```kotlin
plugins { id("komust.kotlin-module"); `java-gradle-plugin` }
gradlePlugin {
    plugins {
        create("komust") {
            id = "io.komust"
            implementationClass = "io.komust.gradle.KomustGradlePlugin"
        }
    }
}
```

No change to that file is required for composite consumption. (Publishing config that the
smoke test relies on is orthogonal and can stay.)

### 1.3 `pluginManagement { includeBuild }` vs root `includeBuild`

Gradle's current guidance:

- Plugins applied in **project build scripts** → include the plugin build with
  `includeBuild` **inside `pluginManagement {}`** in `settings.gradle.kts`. The docs call
  this "more reliable plugin resolution, especially when using the modern `plugins {}`
  block," and note that the `--include-build` CLI flag "may not work reliably for plugins
  applied using the modern `plugins {}` block, especially for unpublished plugins."
- Plugins applied in the **settings file itself** → `includeBuild` inside `pluginManagement {}`.
- A plain root `includeBuild("…")` still works for `plugins {}` substitution in current
  Gradle (the `kotlin-gradle-plugin-template` uses exactly that), but `pluginManagement {
  includeBuild }` is the documented-preferred form and what `ktlint-gradle` uses.

([Gradle — Composite Builds / Included plugin builds](https://docs.gradle.org/current/userguide/composite_builds.html))

**Use `pluginManagement { includeBuild(...) }`.**

### 1.4 Version string handling under substitution

Prior art is unanimous: **omit the version** in the consuming `plugins {}` block when the
plugin comes from an included build.

- `kotlin-gradle-plugin-template/example/build.gradle.kts`:
  `id("com.ncorti.kotlin.gradle.template.plugin")` — no version.
- `ktlint-gradle/samples/kotlin-gradle/build.gradle.kts`:
  `id("org.jlleitschuh.gradle.ktlint")` — no version.

An included build satisfies a marker request regardless of the requested version, but the
cleanest, least-surprising form is id-only. (Where a central version *is* wanted,
`ktlint-gradle` pins it once in the *plugin build's own*
`pluginManagement { plugins { id(...) version "latest.release" } }`, not in the samples.)

### 1.5 The include-cycle constraint

An included build may itself `includeBuild` other builds, but **mutual inclusion is a
cycle** and Gradle rejects it. So for the recommended layout:

- `komust-example/settings.gradle.kts` → `pluginManagement { includeBuild("../") }` ✅
- root `settings.gradle.kts` → **must not** `includeBuild("komust-example")` ❌

The example is therefore driven as a standalone build in CI, not through the root aggregate.
This matches #53's still-open "Using the example as a CI integration-test fixture" note —
the example's `mutationTest` becomes an explicit CI step, not part of `check`.

---

## 2. Alternatives considered

| Approach | How | Fresh-clone green? | Tracks source? | Verdict for an *example* |
|---|---|---|---|---|
| **Composite build (recommended)** | `pluginManagement { includeBuild }`, id-only `plugins {}` | Yes | Yes, always | **Chosen.** Zero ceremony, no publish step. |
| `publishToMavenLocal` + pinned version | Example resolves `io.komust` from `mavenLocal()` at a fixed version | **No** — needs a manual publish first; `~/.m2` is machine-global and goes stale | Only after re-publish | Rejected. Breaks "green on fresh clone"; the exact failure mode of the smoke-test repo minus its `--refresh-dependencies` guard. |
| Test-maven-repo (the `MutationTestSmokeTest` pattern) | Publish 4 SNAPSHOT modules to `build/test-maven/`, example points a `maven {}` repo at it | **No** — depends on the `test` task having run | Only after re-publish | Rejected for the example. Fine for the smoke test because the *test* owns the publish→consume lifecycle in one task graph. |
| `--include-build` CLI flag | `./gradlew --include-build ../ mutationTest` from the example dir | N/A | Yes | Rejected as the primary mechanism — docs warn it is unreliable for the `plugins {}` block with unpublished plugins. Useful only as an ad-hoc dev override. |
| `buildSrc` / convention plugin | Put mutation wiring in `buildSrc` | Yes | Yes | Not applicable — `buildSrc` can't host `io.komust` itself (it's a shipped product module, not build logic), and the point of the example is to exercise the *published* apply-by-id path a user would use. |

---

## 3. Prior art

### 3.1 `cortinico/kotlin-gradle-plugin-template` (canonical template)

- **Layout:** plugin lives in an **included build** `plugin-build/` (own
  `settings.gradle.kts`, own wrapper); the example is a **root subproject**
  `include(":example")`. Root `settings.gradle.kts` does root-level
  `includeBuild("plugin-build")`.
- **Consumption:** `example/build.gradle.kts` → `plugins { java;
  id("com.ncorti.kotlin.gradle.template.plugin") }` — **no version**. No
  `example/settings.gradle.kts` (it's part of the root build).
- **Substitution key:** `plugin-build/settings.gradle.kts` sets
  `rootProject.name = "com.ncorti.kotlin.gradle.template"` to match `GROUP`; the README notes
  "the project relies on module name/group in order for dependency substitution to work."
- **CI / aggregate:** a root `preMerge` task fans out to both builds:
  `dependsOn(":example:check")` **and**
  `dependsOn(gradle.includedBuild("plugin-build").task(":plugin:check"))`. The publish job
  runs `./gradlew --project-dir plugin-build … publishPlugins` separately. CI also runs the
  plugin-created task against the example (`./gradlew templateExample …`) and greps its
  output — i.e. the example *is* an end-to-end CI check.

### 3.2 `JLLeitschuh/ktlint-gradle`

- **Layout:** plugin in included build `plugin/` (own `settings.gradle.kts`); samples under
  `samples/` are **root subprojects** (`include("samples:kotlin-gradle")`, …). **One shared
  wrapper** at the repo root.
- **Consumption:** root `settings.gradle.kts` →
  `pluginManagement { includeBuild("./plugin") }`; each sample's `build.gradle.kts` →
  `plugins { kotlin("jvm"); application; id("org.jlleitschuh.gradle.ktlint") }` — **no
  version** on the ktlint id (Kotlin version is centralised in
  `pluginManagement { plugins { … } }`).
- **Plugin declaration:** `plugin/build.gradle.kts` applies `kotlin-dsl` (which pulls in
  `java-gradle-plugin`) + `com.gradle.plugin-publish` and declares
  `gradlePlugin { plugins { register("ktlintPlugin") { id = "org.jlleitschuh.gradle.ktlint" … } } }`.
- Samples are ordinary subprojects, so `./gradlew check` exercises them in the aggregate.

### 3.3 `szpak/gradle-pitest-plugin`

- No in-repo "example project." Verification is **functional tests**: a dedicated
  `src/funcTest` source set driving Gradle TestKit against generated fixture builds — the
  same category as komust's `MutationTestSmokeTest`. Real-world usage examples live in a
  **separate** `gradle-pitest-plugin-samples` repo, out of the plugin repo entirely.

### 3.4 `hcoles/pitest` (engine) and `detekt/detekt`

- pitest keeps hand-written sample Maven projects under `samples/` used by
  `pitest-maven-verification` / `pitest-modern-verification` modules — integration fixtures,
  not a "how to use it" showcase.
- detekt ships `detekt-sample-extensions` (a subproject showing how to *write* a detekt
  extension) and dogfoods its own plugin via `build-logic` convention plugins; the
  user-facing "how to apply detekt" story lives in the `website/` docs, not a runnable
  example module.

### 3.5 Takeaways

1. Every project that ships a *runnable* example (`cortinico`, `ktlint-gradle`) uses a
   **composite build**, applies the plugin **by id with no version**, and keeps **one shared
   wrapper**.
2. In both, the **plugin is the included build and the example/samples are root
   subprojects** — the inverse of komust's current module layout. komust either flips to
   that layout (§1, alternative) or makes the example a separate build that includes the
   root (§1, recommended).
3. Projects that only need *verification*, not a showcase (`pitest`, `gradle-pitest-plugin`,
   detekt-core), use TestKit functional tests and push real usage examples to a separate
   repo — which is essentially what `MutationTestSmokeTest` already is.

---

## 4. The `MutationTestSmokeTest` question, answered

`komust-gradle-plugin/build.gradle.kts` wires, onto the `test` task:

```kotlin
dependsOn(":komust-*:publishAllPublicationsToTestMavenRepository")   // 4 modules
systemProperty("komust.testMavenRepo", "$rootDir/build/test-maven")
systemProperty("komust.version", project.version)                     // a -SNAPSHOT
```

and the test writes a fixture `settings.gradle.kts` pointing a `maven {}` repo at that
directory, with `--refresh-dependencies` on every run so the "always-freshest SNAPSHOT"
`maven-metadata.xml` is honoured.

This is **deliberate test scaffolding, not reusable for an example**:

- It only works *after* `:komust-gradle-plugin:test`'s dependencies have run the publish
  tasks. A fresh `git clone` + "open the example, run `mutationTest`" would resolve nothing.
- It resolves a `-SNAPSHOT`; without the `--refresh-dependencies` guard (which is a
  test-runner detail, not something you want in a checked-in example) a stale jar wins.
- It exercises the *published marker* path, which is valuable precisely as a
  publish-integration smoke test — a coverage komust should **keep**. The example covers a
  different path (apply-from-source) and the two are complementary, not redundant.

**Keep `MutationTestSmokeTest` as-is. Build the example on a composite build.**

---

## Sources

- [Gradle — Composite builds (Included builds)](https://docs.gradle.org/current/userguide/composite_builds.html)
- [Gradle — Working with Plugins / Plugin Marker Artifacts](https://docs.gradle.org/current/userguide/plugins_intermediate.html)
- [Gradle — Java Gradle Plugin Development](https://docs.gradle.org/current/userguide/java_gradle_plugin.html)
- [Gradle — Using Plugins](https://docs.gradle.org/current/userguide/plugins.html)
- [gradle/gradle#1518 — Composite Build support for plugins block](https://github.com/gradle/gradle/issues/1518)
- [gradle/gradle#2528 — Support composite builds for `plugins` block](https://github.com/gradle/gradle/issues/2528)
- [gradle/gradle#4343 — Allow opting out of plugin dependency substitution in a composite](https://github.com/gradle/gradle/issues/4343)
- [cortinico/kotlin-gradle-plugin-template](https://github.com/cortinico/kotlin-gradle-plugin-template) — root `settings.gradle.kts`, `build.gradle.kts` (`preMerge`/`reformatAll`), `example/build.gradle.kts`, `plugin-build/settings.gradle.kts`, `.github/workflows/pre-merge.yaml`
- [JLLeitschuh/ktlint-gradle](https://github.com/JLLeitschuh/ktlint-gradle) — root `settings.gradle.kts` (`pluginManagement { includeBuild("./plugin") }`), `samples/kotlin-gradle/build.gradle.kts`, `plugin/settings.gradle.kts`, `plugin/build.gradle.kts` (`gradlePlugin` block)
- [szpak/gradle-pitest-plugin](https://github.com/szpak/gradle-pitest-plugin) — `src/funcTest` layout; separate `gradle-pitest-plugin-samples` repo
- [hcoles/pitest](https://github.com/hcoles/pitest) — `samples/`, `pitest-*-verification` modules
- [detekt/detekt](https://github.com/detekt/detekt) — `detekt-sample-extensions`, `build-logic/`, `website/`
- komust repo: `docs/adr/0005-gradle-plugin-architecture.md`, `komust-gradle-plugin/build.gradle.kts`, `komust-gradle-plugin/src/test/kotlin/io/komust/gradle/MutationTestSmokeTest.kt`, `settings.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties` (Gradle 9.7.1)
