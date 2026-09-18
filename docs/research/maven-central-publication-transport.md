# Maven Central publication transport

Research date: 2026-09-18  
Question: Which Central Publisher Portal transport should komust use for its multi-module Gradle releases?

## Recommendation

Use **GradleUp `nmcp`'s settings/aggregation plugin**, pinned initially to `1.6.2`, as a thin transport over publications produced by Gradle's built-in `maven-publish` and `signing` plugins. Aggregate every public komust publication into **one Central deployment bundle**. Start with `publishingType = "USER_MANAGED"`: upload and wait for validation, retain the deployment ID, inspect/test the validated deployment, and promote that exact deployment with `nmcpPublishDeployment -PnmcpDeploymentId=…` only after the release gate approves it.

Keep artifact construction independent of the transport:

- the shared komust publishing convention creates complete POM metadata, sources and Javadoc JARs;
- Gradle's `signing` plugin signs every Maven publication in memory from CI secrets;
- `nmcp` only stages those existing publications, checks their layout, aggregates them, creates the ZIP, calls the Portal API, waits for validation, and later promotes the validated deployment.

Central requires the publication metadata and detached signatures regardless of transport: non-`pom` projects need sources and Javadoc JARs, POMs must carry the required project metadata, and deployed files need checksums and `.asc` signatures ([Central publishing requirements](https://central.sonatype.org/publish/requirements/), [Central GPG requirements](https://central.sonatype.org/publish/requirements/gpg/)).

This division fits the current build: all four public JVM modules already obtain `maven-publish` from one convention plugin, while the root can aggregate the resulting outgoing publication variants. `nmcp` explicitly supports multi-project builds and does not create or redefine Maven publications ([nmcp quickstart](https://gradleup.com/nmcp/)). Its aggregation plugin collects publication variants from multiple projects and exposes one `publishAggregationToCentralPortal` task ([nmcp manual configuration](https://gradleup.com/nmcp/manual-configuration/)).

## Why one bundle matters

The Portal accepts one archive per publishing request, and that archive may contain more than one component ([Sonatype bundle-upload documentation](https://central.sonatype.org/publish/publish-portal-upload/)). Therefore komust can put the Gradle plugin implementation, compiler plugin, engine, scope library, and any Maven plugin-marker publication required by the selected topology into one deployment.

That gives the release an atomic **decision boundary**: Central validates the deployment before any component is published, and the whole validated deployment is promoted by its deployment ID. It does not promise simultaneous CDN/search visibility after publication; consumer verification must still wait until every expected coordinate resolves.

The Portal's states are `PENDING`, `VALIDATING`, `VALIDATED`, `PUBLISHING`, `PUBLISHED`, and `FAILED`. A user-managed deployment can be published only after it reaches `VALIDATED`, or dropped while `VALIDATED`/`FAILED` ([Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/)).

## Comparison

| Criterion | GradleUp `nmcp` | JReleaser Maven Central deployer | Hand-built bundle + Portal API |
|---|---|---|---|
| Multi-module deployment | Native Gradle aggregation collects publications from selected subprojects into one ZIP and one deployment. | One or more local staging repositories can be supplied to one deployer, so a common local Maven repository can also form one deployment. | Possible because the Portal accepts multiple components per archive, but komust must implement collision/layout checks and assembly. |
| Signing, sources, Javadoc, POM | Deliberately leaves creation to `maven-publish`/`signing`; this matches the existing shared convention and makes local/Portal artifacts identical. | Can sign, checksum, require sources/Javadoc, and verify POMs when `applyMavenCentralRules` is enabled ([JReleaser Maven Central reference](https://jreleaser.org/guide/latest/reference/deploy/maven/maven-central.html)). This is useful but creates a second release model beside Gradle publication configuration. | Everything must be produced and verified by custom Gradle logic or shell code. |
| Validation and promotion | Supports `USER_MANAGED`; after validation it prints the deployment ID and provides `nmcpPublishDeployment` for that ID ([nmcp Publisher API task source](https://github.com/GradleUp/nmcp/blob/v1.6.2/nmcp-tasks/src/main/kotlin/nmcp/internal/task/nmcpPublishWithPublisherApi.kt), [promotion task](https://github.com/GradleUp/nmcp/blob/v1.6.2/nmcp-tasks/src/main/kotlin/nmcp/internal/task/nmcpPublishDeployment.kt)). | Explicit `UPLOAD` and `PUBLISH` stages; writes namespace and deployment ID to `out/jreleaser/output.properties`, a stronger built-in handoff between CI jobs ([JReleaser staged deployments](https://jreleaser.org/guide/latest/reference/deploy/maven/maven-central.html#_staged_deployments)). | API supports upload, status, publish, and drop directly, but the workflow must own polling and state persistence. |
| Retry and recovery | Each HTTP operation currently gets three attempts with exponential delays; every failure except HTTP 404 is retried ([nmcp transport source](https://github.com/GradleUp/nmcp/blob/v1.6.2/nmcp-tasks/src/main/kotlin/nmcp/transport/transport.kt)). Promotion can resume from a known deployment ID. A workflow-level retry of an upload is **not automatically safe** after an ambiguous response because the Portal API documents no idempotency key; inspect the Portal before re-uploading. | Configurable status polling (`retryDelay`, `maxRetries`), resumable publication from a recorded deployment ID, and recent work specifically made the deployer resilient to transient Portal failures ([JReleaser reference](https://jreleaser.org/guide/latest/reference/deploy/maven/maven-central.html), [JReleaser 1.26.0 release](https://github.com/jreleaser/jreleaser/releases/tag/v1.26.0)). | Maximum control, but correct classification of transient failures, ambiguous uploads, polling, and resume logic all become komust-owned code. |
| CI integration | One Gradle aggregation task, Gradle providers for secrets, configuration-cache and isolated-project compatibility, and a separate promotion task. | Good Gradle and environment-variable integration, plus machine-readable deployment output. It also brings a broad general-purpose release framework that komust does not otherwise need. | `curl` is easy to start but produces the most custom CI glue and tests. |
| Local dry run and inspection | `nmcpZipAggregation` writes `build/nmcp/zip/aggregation.zip`; `nmcpPublishAggregationToMavenLocal` installs the same aggregation locally ([nmcp debugging](https://gradleup.com/nmcp/debugging/)). | Gradle first publishes to a local staging directory, which can be inspected before `jreleaserDeploy`; `jreleaserConfig` validates JReleaser configuration ([JReleaser Gradle example](https://jreleaser.org/guide/latest/examples/maven/maven-central.html#_gradle)). | The archive is directly inspectable, but all validation must be assembled separately. |
| Maintenance risk | Focused plugin with a small responsibility. `1.6.2` was released on 2026-08-31 with Gradle 9.8/10 compatibility work ([nmcp 1.6.2](https://github.com/GradleUp/nmcp/releases/tag/v1.6.2)). It is still a community plugin, not supported by Sonatype. | Mature, actively maintained, and the only integration Sonatype calls out by name before listing alternatives. Its much larger feature surface and separate configuration/signing model are unnecessary for this repository today. It is also community software rather than a Sonatype-supported Gradle client. | No third-party plugin risk, but the highest long-term maintenance and security burden because komust becomes the Portal client implementer. |

Do not choose the Portal's OSSRH staging compatibility API for a new workflow. Sonatype describes it as a partial compatibility implementation for migration, recommends that publishing plugins move to the Portal API, and documents weaker deployment grouping for Maven-like clients ([OSSRH staging compatibility API](https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/)). It adds ambiguity without helping this greenfield release flow.

## Why not JReleaser now

JReleaser is the strongest alternative. Its two-stage output (`output.properties`) is cleaner than scraping an `nmcp` deployment ID from console output, and `applyMavenCentralRules` adds useful preflight checks. If komust later wants one tool to coordinate many non-Gradle release targets, reconsider it.

For the first alpha, however, komust already needs correct Gradle publications for local TestKit/consumer testing and for the Gradle Plugin Portal. Keeping POMs, auxiliary JARs, and signatures in Gradle makes those checks exercise the same publications that Central receives. Adding a broad release framework solely for transport would introduce duplicate signing and artifact-validation concepts. `nmcp` is the smaller seam: Gradle owns publication correctness, `nmcp` owns Portal transport.

## Required release-flow rules

1. Pin the `com.gradleup.nmcp.settings` plugin; do not float its version. Sonatype states there is currently no official Gradle plugin for the Portal and that community integrations are not supported by Sonatype ([Sonatype Gradle guidance](https://central.sonatype.org/publish/publish-portal-gradle/)).
2. Select public projects explicitly for aggregation. Do not use an open-ended `allprojects` aggregation in the release contract; future internal modules should not silently become public.
3. Produce and inspect `aggregation.zip` before any network call. Assert the exact expected coordinates/versions and the presence of the main artifact, POM, sources JAR, Javadoc JAR, signatures, and checksums for each publication.
4. Upload one `USER_MANAGED` bundle and persist both the ZIP (as a short-lived protected CI artifact) and its deployment ID. Because `nmcp` emits the ID in task output rather than a documented machine-readable file, the implementation should add a small, tested capture step or query the Portal; it must not depend on a human copying an ID from logs.
5. Test the validated deployment through Sonatype's authenticated deployment repository before promotion; Sonatype documents resolving artifacts from a specific validated deployment for exactly this purpose ([Portal manual testing](https://central.sonatype.org/publish/publish-portal-api/#manually-testing-a-deployment-bundle)).
6. Promote by deployment ID. If validation fails, preserve the failed deployment while diagnosing it—Sonatype asks users not to drop failed deployments needed for support—then drop it only when it is no longer useful.
7. Never blindly rerun the upload job after a timeout or lost response. First look for an existing deployment with the intended name/coordinates. Re-run status/promotion for the known ID; upload a new bundle only when no deployment was created or the previous one was deliberately abandoned.
8. Treat `PUBLISHED` as Central's terminal transport state, not completion of the Public Release. The later release-state decision must still wait for every expected coordinate to resolve from `mavenCentral()` before publishing dependent Plugin Portal metadata and declaring success.

## Decision

Choose **GradleUp `nmcp` aggregation with `USER_MANAGED` publication**, backed by Gradle-native publication/signing configuration and an explicit deployment-ID handoff. Keep JReleaser as the documented fallback if implementation proves that reliable ID capture or staged CI recovery is awkward; do not build a custom Portal client for `0.1.0-alpha.1`.
