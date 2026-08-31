package io.komust.compiler

import io.komust.compiler.ir.KotlinIrCompat
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * K2 entry point for `komust-compiler-plugin`.
 *
 * Discovered by the compiler via the `META-INF/services` ServiceLoader when this
 * jar is on the `-Xplugin` classpath — the Kotlin Gradle plugin's
 * `kotlinCompilerPluginClasspath` in a real build, or `kotlin-compile-testing`
 * in this module's tests.
 *
 * It resolves the [OperatorConfig] and the [MutationScopeFilter] from the plugin
 * options the [KomustCommandLineProcessor] parsed, then registers the
 * [KomustIrGenerationExtension] that weaves the default operator catalog
 * (ADR-0001), filtered to the Mutation Scope (#30), with the compile-once model.
 *
 * Per the compat-shim seam rule, this class imports only the SPI types it
 * subclasses and its override signature; message plumbing, extension
 * registration and IR work go through [KotlinIrCompat].
 */
public class KomustCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val diagnostics = KotlinIrCompat.pluginDiagnostics(configuration)
        val config = OperatorConfig.resolve(
            disabledSlugs = configuration.get(KomustCommandLineProcessor.KEY_DISABLED_OPERATORS).orEmpty(),
            enabledSlugs = configuration.get(KomustCommandLineProcessor.KEY_ENABLED_OPERATORS).orEmpty(),
            experimentalTier = configuration.get(KomustCommandLineProcessor.KEY_EXPERIMENTAL_TIER) ?: false,
            onUnknownSlug = { slug ->
                diagnostics.warn("komust: unknown operator slug '$slug' in the enabled/disabled-operators option — ignoring.")
            },
        )
        val scopeFilter = MutationScopeFilter.from(
            configuration.get(KomustCommandLineProcessor.KEY_SCOPE_PATH),
            diagnostics,
        )
        KotlinIrCompat.registerIrExtension(
            this,
            KomustIrGenerationExtension(
                diagnostics,
                config,
                scopeFilter,
                configuration.get(KomustCommandLineProcessor.KEY_MANIFEST_PATH),
                configuration.get(KomustCommandLineProcessor.KEY_PROJECT_DIR),
            ),
        )
    }
}
