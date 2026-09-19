# Qualification manifest

`generateQualificationManifest` stages the complete public release candidate and
writes `build/qualification/qualification-manifest.json`. The schema-versioned
document identifies the candidate's full Git commit and fixed SemVer version,
then inventories the plugin marker and four implementation coordinates. Every
file in each coordinate/version directory is bound by filename, byte size, and
SHA-256.

Run the verifier against the staged repository:

```shell
./gradlew verifyQualificationManifest
```

To compare the same manifest with a downloaded production repository tree, pass
its project-relative directory:

```shell
./gradlew verifyQualificationManifest -PqualificationRepository=production-repository
```

Detached signatures and checksum sidecars (`.asc`, `.md5`, `.sha1`, `.sha256`,
and `.sha512`) are transport-derived and may be regenerated in production. They
are not candidate files. Adding, removing, renaming, resizing, or changing any
other staged file fails verification, including every JAR, POM, and Gradle module
metadata file.
