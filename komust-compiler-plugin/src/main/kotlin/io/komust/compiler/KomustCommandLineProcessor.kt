package io.komust.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * The plugin's compile-time option surface.
 *
 * The #2 spike found a plugin taking **no** options needs no
 * `CommandLineProcessor` at all. ADR-0005 §3 reverses that: the Gradle plugin
 * passes resolved policy as `SubpluginOption`s, which the compiler only accepts
 * when a `CommandLineProcessor` with a matching [pluginId] is registered.
 *
 * Options (all optional; absent → default catalog, whole module in scope):
 *
 *  - `disabledOperators` — comma-separated operator slugs removed from the
 *    default-on set (the `operators { disable(...) }` DSL, ADR-0005).
 *  - `enabledOperators` — comma-separated slugs added on top (the opt-in path
 *    for experimental-tier operators; `operators { enable(...) }`).
 *
 * #30 adds the `scope` option (the `scope.json` path for enclosing-symbol
 * expansion) on the same surface.
 *
 * [pluginId] is the namespace every option is addressed under
 * (`-P plugin:io.komust.compiler:<key>=<value>`); it must match the
 * `SubpluginOption` keys the Gradle plugin emits.
 */
public class KomustCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = OPTION_DISABLED_OPERATORS,
            valueDescription = "slug[,slug...]",
            description = "Operator slugs to disable within their tier",
            required = false,
            allowMultipleOccurrences = true,
        ),
        CliOption(
            optionName = OPTION_ENABLED_OPERATORS,
            valueDescription = "slug[,slug...]",
            description = "Operator slugs to enable on top of the default tier (e.g. experimental operators)",
            required = false,
            allowMultipleOccurrences = true,
        ),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            OPTION_DISABLED_OPERATORS ->
                configuration.appendCsv(KEY_DISABLED_OPERATORS, value)
            OPTION_ENABLED_OPERATORS ->
                configuration.appendCsv(KEY_ENABLED_OPERATORS, value)
            else -> Unit
        }
    }

    private fun CompilerConfiguration.appendCsv(key: CompilerConfigurationKey<List<String>>, value: String) {
        val existing = get(key).orEmpty()
        val added = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        put(key, existing + added)
    }

    public companion object {
        public const val PLUGIN_ID: String = "io.komust.compiler"

        internal const val OPTION_DISABLED_OPERATORS: String = "disabledOperators"
        internal const val OPTION_ENABLED_OPERATORS: String = "enabledOperators"

        internal val KEY_DISABLED_OPERATORS: CompilerConfigurationKey<List<String>> =
            CompilerConfigurationKey.create("komust disabled operator slugs")
        internal val KEY_ENABLED_OPERATORS: CompilerConfigurationKey<List<String>> =
            CompilerConfigurationKey.create("komust enabled operator slugs")
    }
}
