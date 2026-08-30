package io.komust.compiler

import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

/**
 * The `-Xplugin` load path a real Kotlin build uses: the compiler finds the
 * registrar and the command-line processor purely through `META-INF/services`
 * ServiceLoader files on the plugin classpath. [FixtureCompiler] instantiates
 * them directly (kctfork's API), so this test is what actually guards the two
 * service files this module ships.
 */
class PluginDiscoveryTest {

    @Test
    fun `the compiler-plugin registrar is ServiceLoader-discoverable`() {
        val registrars = ServiceLoader.load(
            CompilerPluginRegistrar::class.java,
            javaClass.classLoader,
        ).toList()

        assertTrue(
            registrars.any { it is KomustCompilerPluginRegistrar },
            "META-INF/services/…CompilerPluginRegistrar must list KomustCompilerPluginRegistrar; found $registrars",
        )
    }

    @Test
    fun `the command-line processor is ServiceLoader-discoverable`() {
        val processors = ServiceLoader.load(
            CommandLineProcessor::class.java,
            javaClass.classLoader,
        ).toList()

        val komust = processors.filterIsInstance<KomustCommandLineProcessor>().singleOrNull()
        assertTrue(komust != null, "KomustCommandLineProcessor must be ServiceLoader-discoverable; found $processors")
        assertTrue(komust!!.pluginId == "io.komust.compiler")
    }

    @Test
    fun `the in-process compiler is the exact pinned Kotlin version`() {
        // AC5: the kotlin-compile-testing harness runs "the exact supported
        // Kotlin version". kctfork resolves the compiler off this test
        // classpath, where the catalog pins kotlin-compiler-embeddable to the
        // same `kotlin` version as the whole build.
        assertTrue(
            KotlinCompilerVersion.VERSION.startsWith("2.2."),
            "expected the catalog-pinned Kotlin 2.2.x, got ${KotlinCompilerVersion.VERSION}",
        )
    }
}
