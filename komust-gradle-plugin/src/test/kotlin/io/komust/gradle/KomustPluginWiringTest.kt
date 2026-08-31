package io.komust.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fast configuration-phase checks: the `komust {}` DSL, its defaults, and the
 * `mutationTest` task shape — no real build. The end-to-end wiring (mutation
 * compilation, engine fork, scope resolution) is the TestKit smoke test's job.
 */
class KomustPluginWiringTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.pluginManager.apply(KomustGradlePlugin::class.java)
    }

    @Test fun `registers the komust extension with the ADR-0005 defaults`() {
        val komust = project().extensions.getByType(KomustExtension::class.java)
        assertFalse(komust.baseRef.isPresent, "baseRef unset by default → scope resolver auto-detects")
        assertEquals(true, komust.cache.get())
        assertEquals(3.0, komust.timeoutFactor.get())
        assertEquals(listOf("main"), komust.sourceSets.get())
        assertFalse(komust.operators.experimental.get())
        assertTrue(komust.output.humanReport.get())
        assertFalse(komust.output.consoleSurvivorsOnly.get())
    }

    @Test fun `operators enable_disable accumulate into the sets`() {
        val komust = project().extensions.getByType(KomustExtension::class.java)
        komust.operators { ops ->
            ops.disable("increment", "void-call")
            ops.enable("some-experimental")
        }
        assertEquals(setOf("increment", "void-call"), komust.operators.disabled.get())
        assertEquals(setOf("some-experimental"), komust.operators.enabled.get())
    }

    @Test fun `baseRef DSL value flows into the scope-resolution task`() {
        val project = project()
        project.extensions.getByType(KomustExtension::class.java).baseRef.set("origin/develop")
        val scope = project.tasks.named("komustResolveScope", KomustResolveScopeTask::class.java).get()
        assertEquals("origin/develop", scope.baseRef.get())
    }

    @Test fun `registers mutationTest with every per-run option`() {
        assertNotNull(project().tasks.findByName("mutationTest"), "mutationTest not registered")

        val declared = MutationTestTask::class.java.methods.mapNotNull { m ->
            m.getAnnotation(org.gradle.api.tasks.options.Option::class.java)?.option
        }.toSet()
        assertEquals(setOf("all", "since", "files", "scope", "tests", "no-cache"), declared)
    }

    @Test fun `getPluginArtifact and compilerPluginId point at the compiler plugin`() {
        val plugin = KomustGradlePlugin()
        assertEquals("io.komust", plugin.getPluginArtifact().groupId)
        assertEquals("komust-compiler-plugin", plugin.getPluginArtifact().artifactId)
        assertEquals("io.komust.compiler", plugin.getCompilerPluginId())
    }
}
