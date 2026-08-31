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
 *  - `scope` — path to the resolved `scope.json` for enclosing-symbol expansion
 *    (#30, ADR-0002 §3). Single-valued; absent ⇒ the whole module is woven
 *    (the `--all` run).
 *  - `manifest` — path to write the **mutation manifest** JSON (#38): every
 *    woven mutant's compile-time facts, the engine's slice of the input
 *    contract (ADR-0005). Single-valued; absent ⇒ no manifest is written.
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
        CliOption(
            optionName = OPTION_EXPERIMENTAL_TIER,
            valueDescription = "true|false",
            description = "Turn the whole experimental operator tier on (komust { operators { experimental } })",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = OPTION_SCOPE,
            valueDescription = "<path>",
            description = "Path to the resolved scope.json for enclosing-symbol expansion",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = OPTION_MANIFEST,
            valueDescription = "<path>",
            description = "Path to write the mutation manifest JSON (the engine input contract, #38)",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = OPTION_PROJECT_DIR,
            valueDescription = "<path>",
            description = "Project directory — mutation-manifest source paths are made relative to it",
            required = false,
            allowMultipleOccurrences = false,
        ),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            OPTION_DISABLED_OPERATORS ->
                configuration.appendCsv(KEY_DISABLED_OPERATORS, value)
            OPTION_ENABLED_OPERATORS ->
                configuration.appendCsv(KEY_ENABLED_OPERATORS, value)
            OPTION_EXPERIMENTAL_TIER ->
                configuration.put(KEY_EXPERIMENTAL_TIER, value.toBoolean())
            OPTION_SCOPE ->
                configuration.put(KEY_SCOPE_PATH, value)
            OPTION_MANIFEST ->
                configuration.put(KEY_MANIFEST_PATH, value)
            OPTION_PROJECT_DIR ->
                configuration.put(KEY_PROJECT_DIR, value)
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
        internal const val OPTION_EXPERIMENTAL_TIER: String = "experimentalTier"
        internal const val OPTION_SCOPE: String = "scope"
        internal const val OPTION_MANIFEST: String = "manifest"
        internal const val OPTION_PROJECT_DIR: String = "projectDir"

        internal val KEY_DISABLED_OPERATORS: CompilerConfigurationKey<List<String>> =
            CompilerConfigurationKey.create("komust disabled operator slugs")
        internal val KEY_ENABLED_OPERATORS: CompilerConfigurationKey<List<String>> =
            CompilerConfigurationKey.create("komust enabled operator slugs")
        internal val KEY_EXPERIMENTAL_TIER: CompilerConfigurationKey<Boolean> =
            CompilerConfigurationKey.create("komust experimental tier on")
        internal val KEY_SCOPE_PATH: CompilerConfigurationKey<String> =
            CompilerConfigurationKey.create("komust scope.json path")
        internal val KEY_MANIFEST_PATH: CompilerConfigurationKey<String> =
            CompilerConfigurationKey.create("komust mutation manifest path")
        internal val KEY_PROJECT_DIR: CompilerConfigurationKey<String> =
            CompilerConfigurationKey.create("komust project directory")
    }
}
