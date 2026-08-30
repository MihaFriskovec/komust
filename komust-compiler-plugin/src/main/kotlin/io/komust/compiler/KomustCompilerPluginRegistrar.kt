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
 * It registers the [KomustIrGenerationExtension] and nothing else. As of #28
 * that extension weaves the first operator — the arithmetic additive swap — into
 * the module with the compile-once model.
 *
 * Per the compat-shim seam rule, this class imports only the SPI type it
 * subclasses and its override signature; message and registration plumbing go
 * through [KotlinIrCompat].
 */
public class KomustCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val diagnostics = KotlinIrCompat.pluginDiagnostics(configuration)
        KotlinIrCompat.registerIrExtension(this, KomustIrGenerationExtension(diagnostics))
    }
}
