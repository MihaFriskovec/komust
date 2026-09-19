// Root build. Per-module configuration — coordinates, publishing, the Kotlin
// toolchain — lives in the `komust.kotlin-module` convention plugin (buildSrc/),
// which reads the atomic public identity / komustVersion from gradle.properties.

tasks.register("stagePublications") {
    group = "publishing"
    description = "Stages the marker and all four implementation publications for release inspection."
    dependsOn(
        ":komust-compiler-plugin:publishAllPublicationsToQualificationRepository",
        ":komust-scope:publishAllPublicationsToQualificationRepository",
        ":komust-engine:publishAllPublicationsToQualificationRepository",
        ":komust-gradle-plugin:publishAllPublicationsToQualificationRepository",
    )
}
