package io.komust.compiler

import io.komust.compiler.ir.KotlinIrCompat
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * The plugin's compile-time option surface.
 *
 * The #2 spike found that a plugin taking **no** options needs no
 * `CommandLineProcessor` at all. ADR-0005 §3 reverses that: the Gradle plugin
 * passes the resolved `scope.json` path as a `SubpluginOption`, which the
 * compiler only accepts when a `CommandLineProcessor` with a matching
 * [pluginId] is registered.
 *
 * As of #30 that option exists — [`scope`][KotlinIrCompat.SCOPE_OPTION], the
 * `scope.json` path driving enclosing-symbol expansion. #29 adds the
 * enabled/disabled-operators option alongside it.
 *
 * [pluginId] is the namespace every option is addressed under
 * (`-P plugin:io.komust.compiler:<key>=<value>`); it must match the
 * `SubpluginOption` keys the Gradle plugin emits.
 *
 * Per the compat-shim seam rule this class names only the SPI type it implements
 * and its override-signature types; option construction and routing live behind
 * [KotlinIrCompat].
 */
public class KomustCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = KotlinIrCompat.komustCliOptions()

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ): Unit = KotlinIrCompat.applyCliOption(option.optionName, value, configuration)

    public companion object {
        public const val PLUGIN_ID: String = "io.komust.compiler"
    }
}
