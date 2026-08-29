package io.komust.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * PROTOTYPE — komust #2. K2 entry point.
 *
 * Discovered by the compiler via META-INF/services ServiceLoader when this jar
 * is on the -Xplugin classpath (KGP's `kotlinCompilerPluginClasspath`). No
 * CommandLineProcessor needed because the spike takes no compile-time options.
 */
class KomustPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector =
            configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        IrGenerationExtension.registerExtension(KomustIrExtension(messageCollector))
    }
}
