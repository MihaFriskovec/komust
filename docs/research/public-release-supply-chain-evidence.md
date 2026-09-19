# Public Release supply-chain evidence

_Researched 2026-09-19 for “Define the Public Release supply-chain evidence.” This note records current platform facts and feasible options; it does not choose komust's policy._

## Maven Central integrity evidence

- Maven Central requires a valid `.md5` and `.sha1` checksum for every deployed file. SHA-256 and SHA-512 are supported but optional. Detached ASCII-armored OpenPGP signatures are separately required for every deployed artifact and POM; signatures do not themselves need checksums, and checksum files do not need signatures. ([Central publishing requirements](https://central.sonatype.org/publish/requirements/))
- Sonatype's Maven publishing plugin defaults to generating all four accepted checksum algorithms (MD5, SHA-1, SHA-256, and SHA-512); its `required` mode generates only MD5 and SHA-1. A user-assembled Publisher Portal bundle may likewise contain the optional stronger checksums alongside the mandatory pair. ([Central Maven publishing](https://central.sonatype.org/publish/publish-portal-maven/), [bundle upload layout](https://central.sonatype.org/publish/publish-portal-upload/))
- Published Central components are not removed or modified. Corrections require a new version, and Sonatype identifies signatures and checksums as the evidence that bytes downloaded later are the originally published bytes. ([Central component immutability](https://central.sonatype.org/publish/requirements/immutability/))

The existing komust Qualification manifest already inventories the release's JAR and POM files by SHA-256. Emitting `.sha256` sidecars is therefore mechanically cheap and gives consumers the same modern digest the manifest uses. SHA-512 is also accepted by Central, but adds no new binding to komust's current manifest.

## SBOM format and generation

- CycloneDX 1.7 is the current specification. It has a registered JSON media type, a conventional `*.cdx.json` filename, and a model for components, dependency relationships, hashes, metadata, and external references. ([CycloneDX specification overview](https://cyclonedx.org/specification/overview/))
- The official CycloneDX Gradle plugin can produce one direct SBOM per project and one aggregate SBOM for a multi-project build from Gradle's resolved dependency graphs. Its 3.x line supports Gradle 8.4+, Java 8+, JSON/XML, and schema 1.6 by default with 1.7 opt-in. It records dependency selection after resolution, including transitives, conflict resolution, constraints, and substitution. ([CycloneDX Gradle plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin/blob/master/README.md))
- Central does not require an SBOM. Sonatype advises publishers to avoid redundant optional artifacts, so an SBOM need not be attached to every Maven module merely to pass Central validation. ([Central publishing requirements](https://central.sonatype.org/publish/requirements/), [reducing publishing usage](https://central.sonatype.org/publish/reducing-publishing-usage/))

A single aggregate CycloneDX JSON file is feasible for the locked, lockstep four-artifact topology. Whether it describes only shipped runtime/compile dependencies or also build/test tooling is a policy choice; the former better describes what consumers actually receive.

## Provenance and attestations

- GitHub build artifact attestations are created from GitHub Actions with `id-token: write`, `contents: read`, and `attestations: write`. They bind artifact digests to repository, commit, workflow, trigger, and other OIDC claims; for public repositories they use Sigstore's public-good instance and its public transparency log. GitHub describes this as SLSA Build Level 2 provenance. ([GitHub artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations), [generating build provenance](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations))
- SLSA Build Level 1 only requires provenance describing how the package was built; it may be unsigned and is trivial to forge. Build Level 2 requires a hosted build platform and authenticated provenance tied to it. A release built on an individual workstation therefore cannot honestly claim Build Level 2 merely by writing or signing a provenance document afterward. ([SLSA security levels](https://slsa.dev/spec/v1.0/levels), [SLSA build requirements](https://slsa.dev/spec/v1.2-rc1/build-requirements))
- GitHub immutable releases are independent of build attestations. Once published, their tag and assets cannot be moved, replaced, or deleted while the release exists. GitHub automatically creates a release attestation binding the tag, commit SHA, and attached assets. The recommended flow is draft → attach every asset → publish. ([GitHub immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases), [managing releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository))
- As of this research, `MihaFriskovec/komust` is public but its immutable-release setting is disabled, as reported by GitHub's repository and immutable-release APIs. Enabling the setting affects only future releases. ([GitHub immutable-release API](https://docs.github.com/en/rest/repos/repos#check-if-immutable-releases-are-enabled-for-a-repository), [enabling immutable releases](https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/establish-provenance-and-integrity/prevent-release-changes))

Because the locked release process intentionally builds and publishes from a clean local machine, GitHub Actions build provenance would describe a different build unless the build authority moves to hosted automation. Immutable-release attestation is still compatible with local publishing: it attests the public release record and its assets, not the workstation build process.

## Retention and discovery

- Maven Central is the durable public location for the Maven artifacts and their sidecars because published components are immutable. The Gradle Plugin Portal remains the durable discovery location for the plugin entrypoint under the already locked topology. ([Central component immutability](https://central.sonatype.org/publish/requirements/immutability/))
- A GitHub Release can carry up to 1,000 assets, each smaller than 2 GiB, with no documented aggregate size or bandwidth limit. Immutable releases prevent attached assets from being changed or removed while the release exists. ([About GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), [GitHub immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases))
- GitHub Actions artifacts and logs are not durable public evidence: public-repository retention is configurable only up to 90 days. Any qualification result needed for the lifetime of a release must therefore be copied into a durable release asset or another durable store rather than referenced only as an Actions artifact. ([GitHub Actions retention](https://docs.github.com/en/organizations/managing-organization-settings/configuring-the-retention-period-for-github-actions-artifacts-and-logs-in-your-organization))

The GitHub Release is consequently the natural human-facing evidence index: its notes can link to Central and the Plugin Portal, while immutable assets can retain the Qualification manifest, checksum inventory, SBOM, and any verification report for the release's lifetime.

## Feasible policy boundary for the first alpha

Current platforms support all of the following without moving publishing into hosted automation:

1. Central-required PGP signatures and MD5/SHA-1 checksums, plus SHA-256 sidecars aligned with the Qualification manifest.
2. One aggregate CycloneDX JSON SBOM generated from the resolved shipped dependency graph.
3. An immutable GitHub Release whose automatic release attestation binds the tag, commit, and attached evidence assets.
4. Durable release assets containing the Qualification manifest and public verification results, with release notes linking every registry surface.

Authenticated build provenance at SLSA Build Level 2 is not compatible with the current local-build authority. A locally written SLSA Level 1-style statement is possible but provides documentation rather than meaningful tamper resistance. Per-module SBOMs, SBOM signatures, vulnerability scan results, and stronger provenance are likewise optional policy additions rather than registry requirements.
