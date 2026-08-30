package io.komust.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The thin Gradle adapter over `komust-engine` (ADR-0005).
 *
 * Scaffold placeholder: registration wiring only. The `komust {}` DSL, the
 * `mutationTest` task, the mutation compilation, and the engine fork land in
 * #38 and the engine tickets.
 */
public class KomustGradlePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Intentionally empty until #38.
    }
}
