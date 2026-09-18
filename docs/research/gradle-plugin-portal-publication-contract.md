# Gradle Plugin Portal publication contract for `io.komust`

_Researched 2026-09-18 for [Determine the Gradle Plugin Portal publication contract](https://github.com/MihaFriskovec/komust/issues/72). Sources are Gradle's current official documentation, the live Plugin Portal/Maven endpoints, and the current komust source tree._

## Decision

Publish `io.komust` with a pinned `com.gradle.plugin-publish` 2.2.1, using `group = "io.komust"` and the fixed, non-SNAPSHOT version `0.1.0-alpha.1`. Publish and verify every same-version `io.komust` module on Maven Central **before** uploading the Gradle plugin. Run both local plugin validation and the Portal's no-upload validation before the irreversible upload.

The first Portal upload is not the end of the release: it enters manual review, which Gradle says can take a few days. The release workflow must checkpoint there and resume only when the marker is publicly resolvable. A clean consumer must then prove both resolution paths:

1. `plugins { id("io.komust") version "0.1.0-alpha.1" }` resolves the Portal marker and plugin implementation; and
2. `mavenCentral()` in the consumer's normal dependency repositories resolves `komust-engine` and `komust-compiler-plugin`, which the plugin creates as project configurations at runtime.

Do not retry an uploaded version in place. Treat a version as consumed once uploaded; correct mistakes with the next fixed prerelease version. Pending versions can be deleted, but the Portal does not promise that deleting makes the version reusable.

## Portal identity and approval

- A new plugin undergoes manual review, normally only for its first version. Changing either the Maven group or plugin ID triggers manual review again. Gradle says review can take a few days and communicates acceptance or requested changes through the account email. [Portal approval rules](https://plugins.gradle.org/docs/publish-plugin#approval), [Gradle publishing guide](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html#publishing_your_plugin)
- `io.komust` is a reverse-domain identity. Gradle requires plugin IDs to trace back to the author, requires the plugin ID and Maven group to share the same top-level namespace, and can demand a DNS TXT record proving control of the domain. For this project, `group = "io.komust"` and plugin ID `io.komust` satisfy the shared-prefix rule, subject to proving control of `komust.io`. [Portal ID rules](https://plugins.gradle.org/docs/publish-plugin#plugin-ids-should-trace-back-to-the-author)
- The Portal account should be linked to the maintainer's GitHub identity, and the account email must be monitored during the first approval. Gradle cites an unlinked account and inability to prove organization/domain ownership as common reasons for rejection. [Approval troubleshooting](https://plugins.gradle.org/docs/publish-plugin#ownership-cant-be-established)
- The plugin must have real functionality, be useful beyond one company, come from the original rather than a forked repository, have public English documentation, and comply with Gradle's Code of Conduct. Komust's mutation-testing task is substantive and broadly applicable; its public README/project URLs must be live before submission. [Complete approval rules](https://plugins.gradle.org/docs/publish-plugin#approval)
- A live check on 2026-09-18 found no existing marker for `io.komust`: the Portal endpoint redirected to Maven Central and ended in 404 for both [marker metadata](https://plugins.gradle.org/m2/io/komust/io.komust.gradle.plugin/maven-metadata.xml) and the [`0.1.0-alpha.1` marker POM](https://plugins.gradle.org/m2/io/komust/io.komust.gradle.plugin/0.1.0-alpha.1/io.komust.gradle.plugin-0.1.0-alpha.1.pom). This is evidence of current technical availability, not a reservation or trademark clearance.

## Required publication shape and metadata

Apply `com.gradle.plugin-publish` 2.2.1 to `komust-gradle-plugin`. Since plugin-publish 1.0, it automatically applies `java-gradle-plugin` and `maven-publish`; Maven publications are the only supported metadata source. It publishes a main plugin implementation publication and a marker publication. [Portal publishing instructions](https://plugins.gradle.org/docs/publish-plugin#publication), [Java Gradle Plugin interactions](https://docs.gradle.org/current/userguide/java_gradle_plugin.html#sec:java_gradle_plugin_and_maven_publish)

The build must supply:

- project `group` and a fixed `version`;
- `gradlePlugin.website` pointing to working public English documentation;
- `gradlePlugin.vcsUrl` pointing to the public source repository;
- plugin `id`, `implementationClass`, `displayName`, `description`, and discovery `tags`;
- an explicit Gradle feature-compatibility declaration, including an honest `configurationCache = false` if it is not supported.

Gradle documents these fields in its [publishing configuration](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html#configure_the_plugin_publishing_plugin). The Portal now warns when Gradle feature compatibility is omitted and says it plans eventually to reject such submissions, so the alpha flow should require it rather than rely on the current warning. [Feature compatibility declaration](https://plugins.gradle.org/docs/publish-plugin#declaring-compatibility-with-gradle-features)

Plugin Publish automatically creates sources and Javadoc JARs. Applying Gradle's `signing` plugin makes Plugin Publish sign every artifact in the main and marker Maven publications. The Portal documentation presents signing as optional, not as an approval prerequisite; Maven Central's independent contract may still require signing for the Central copies. [Sources, Javadoc, and signing](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html#configuring_the_plugin_publishing_plugin)

The Plugin Portal rules do not name PGP signatures or a POM license field as Portal acceptance requirements. Nevertheless, the Portal Terms require the publisher to own the required rights and keep the designated license accurate, so the Apache-2.0 declaration and repository license must agree. [Plugin Portal Terms, publishing](https://plugins.gradle.org/docs/terms#publishing-to-the-plugin-portal)

## Marker and dependency resolution

The plugins DSL maps plugin ID and version to a marker with coordinates:

```text
io.komust:io.komust.gradle.plugin:0.1.0-alpha.1
```

That marker POM depends on the actual plugin implementation module. `java-gradle-plugin` generates the marker automatically, and Plugin Publish uploads both marker and implementation publications. Without the marker, consumers would need a `pluginManagement.resolutionStrategy` mapping instead of the normal plugins DSL. [Plugin marker artifacts](https://docs.gradle.org/current/userguide/plugins_intermediate.html#sec:plugin_markers), [publication generation](https://docs.gradle.org/current/userguide/java_gradle_plugin.html#sec:java_gradle_plugin_and_maven_publish)

Komust's implementation artifact has a runtime dependency on `io.komust:komust-scope:<version>`. The Portal's Maven endpoint currently redirects artifacts it does not host to Maven Central (a live request for Kotlin stdlib returned HTTP 303 to `repo.maven.apache.org`), and Gradle explicitly notes that plugins commonly depend on Maven Central libraries. Standard consumers using `gradlePluginPortal()` therefore resolve the marker, implementation, and Central-hosted transitive dependencies through the plugin-repository path. Mirrored/corporate builds must expose both a Portal mirror and a Maven Central mirror under `pluginManagement.repositories`. [Official mirroring guidance](https://plugins.gradle.org/docs/mirroring)

That does **not** cover everything komust resolves. The current plugin creates `komustEngineClasspath` and `komustRuntimeGuardClasspath` as normal project configurations for:

```text
io.komust:komust-engine:<plugin-version>
io.komust:komust-compiler-plugin:<plugin-version>
```

Plugin repositories and project dependency repositories are distinct. Therefore the documented consumer contract must include `mavenCentral()` in normal dependency resolution (for example under `dependencyResolutionManagement.repositories`), not only the default Plugin Portal. [Gradle repository separation](https://docs.gradle.org/current/userguide/declaring_repositories_basics.html#sec:declaring_plugin_repositories)

## Ordering contract

The safe ordering is:

1. Build and test all artifacts from the same commit and fixed version.
2. Run `:komust-gradle-plugin:validatePlugins` (preferably failing on warnings) and a local-repository clean-consumer test that exercises the generated marker.
3. Run `:komust-gradle-plugin:publishPlugins --validate-only`. Gradle describes this as server-side Portal validation with no upload. It validates a submission but, because it uploads nothing, does not reserve `io.komust` or replace manual ownership review. [No-upload validation](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html#publishing_your_plugin), [`validatePlugins` API](https://docs.gradle.org/current/kotlin-dsl/gradle/org.gradle.plugin.devel.tasks/-validate-plugins/index.html)
4. Publish the same-version modules to Maven Central, including `komust-scope`, `komust-compiler-plugin`, `komust-engine`, and the Central copy of `komust-gradle-plugin` if the public artifact topology requires all four there.
5. Wait until each exact GAV resolves from the canonical Maven Central repository. Portal publication must not race Central promotion: both the plugin implementation's `komust-scope` dependency and komust's runtime-created engine/compiler configurations require those modules.
6. Run `:komust-gradle-plugin:publishPlugins` with the protected Portal credentials.
7. On the first version, enter a pending-approval state rather than holding a CI runner. Resume after the account receives approval and the exact marker POM resolves publicly. A group or ID change uses this same review gate on later releases.
8. Run a network-clean consumer using only `gradlePluginPortal()` for plugins and `mavenCentral()` for ordinary dependencies. It must apply `io.komust` by ID and run a representative `mutationTest`.

Portal publication belongs after Central because a visible marker whose implementation graph or runtime-created configurations cannot resolve is a broken release. This ordering is a release-design inference from Gradle's marker model, repository separation, Portal-to-Central behavior, and komust's current plugin source.

## Validation, credentials, and secrets

- Portal authentication is an API key and secret created under the account's **API Keys** page. Gradle accepts them as `gradle.publish.key` / `gradle.publish.secret` properties or, for CI, `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET`. [Account setup](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html#account_setup)
- Store those two values in the protected GitHub `release` environment selected for the release job. Never put them in repository `gradle.properties` or workflow output.
- Portal signing, if enabled, additionally needs the signing key material and passphrase already required by the Maven Central leg. Keep Central credentials separate from Portal credentials even when the same protected environment releases both.
- Pin Plugin Publish to 2.2.1; do not use a dynamic version. Its current official example uses 2.2.1. [Current Portal example](https://plugins.gradle.org/docs/publish-plugin#example)
- `0.1.0-alpha.1` is allowed by the stated Portal rule because it is a fixed version and does not end in `SNAPSHOT`. The Portal rejects SNAPSHOT versions. [Version rule](https://plugins.gradle.org/docs/publish-plugin#only-final-versions-should-be-published)

## Immutability and recovery

Portal versions are fixed and cannot be overwritten, which is why SNAPSHOT versions are rejected. If local or `--validate-only` validation fails before upload, fix the build and retry the same proposed version. Once `publishPlugins` has created the remote version, treat that version as immutable and issue the next prerelease (`alpha.2`, etc.) for corrections. [Fixed-version rule](https://plugins.gradle.org/docs/publish-plugin#plugin-version-cant-be-snapshot)

Owners can delete a version within seven days of publication or approval, whichever is later; pending versions can be deleted at any time. After seven days, removal requires Portal support. Deletion breaks consumers, and the documentation does not say that a deleted coordinate can be reused, so deletion is an emergency cleanup mechanism rather than a retry strategy. [Portal deletion policy](https://plugins.gradle.org/docs/deleting)

## Consequences for the release specification

- Namespace/domain proof and a Portal account are human prerequisites for the first upload, not automatable validation steps.
- Portal validation and publication must be separate protected actions; `--validate-only` is a required preflight but not namespace reservation or approval.
- Central promotion is a hard predecessor of Portal upload.
- Initial approval is an asynchronous release state measured in days. The workflow should stop cleanly after submission and resume from a separate verification/promotion entry point.
- A Public Release is not complete when `publishPlugins` returns successfully. It is complete only after approval, public marker resolution, and the clean-consumer test prove the whole graph.
- Normal future releases can skip the manual-wait state unless group or plugin ID changes or Gradle flags a release for review; they must still preserve Central-first ordering and immutable-version handling.
