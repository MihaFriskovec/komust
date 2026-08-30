package io.komust.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor

/**
 * The plugin's compile-time option surface.
 *
 * The #2 spike found that a plugin taking **no** options needs no
 * `CommandLineProcessor` at all. ADR-0005 §3 reverses that: the Gradle plugin
 * passes the resolved `scope.json` path as a `SubpluginOption`, which the
 * compiler only accepts when a `CommandLineProcessor` with a matching
 * [pluginId] is registered. So the processor exists from this skeleton on,
 * even though it declares no options yet:
 *
 *  - #30 adds the `scope` option (the `scope.json` path for enclosing-symbol
 *    expansion),
 *  - #29 adds the enabled/disabled-operators option.
 *
 * [pluginId] is the namespace every option is addressed under
 * (`-P plugin:io.komust.compiler:<key>=<value>`); it must match the
 * `SubpluginOption` keys the Gradle plugin emits.
 */
public class KomustCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = emptyList()

    public companion object {
        public const val PLUGIN_ID: String = "io.komust.compiler"
    }
}
