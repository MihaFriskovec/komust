package io.komust.compiler

import io.komust.compiler.ir.KotlinIrCompat
import io.komust.compiler.ir.PluginDiagnostics
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * The mutation surface's IR pass.
 *
 * ## This skeleton (#27): identity transform
 *
 * It inspects the module IR and **weaves nothing** — a fixture compiles and
 * behaves byte-for-byte as it would without the plugin. The point of the pass
 * existing now is to prove the load path end to end: the ServiceLoader finds the
 * [KomustCompilerPluginRegistrar], the registrar registers this extension, the
 * compiler hands it a live [IrModuleFragment] + [IrPluginContext], and every
 * touch of the unstable IR API goes through [KotlinIrCompat].
 *
 * #28 replaces the no-op body with the first operator: a call site `a + b`
 * becomes `if (mutantActive("<id>")) a - b else a + b`, both branches in one
 * compile, flipped at runtime through [io.komust.runtime.MutantRegistry].
 *
 * Per the compat-shim seam rule, this class imports only the SPI interface it
 * implements and its `generate` signature; everything else goes through
 * [KotlinIrCompat].
 */
internal class KomustIrGenerationExtension(
    private val diagnostics: PluginDiagnostics,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val fileCount = KotlinIrCompat.filesOf(moduleFragment).size

        diagnostics.info(
            "komust: identity transform over module '${KotlinIrCompat.moduleName(moduleFragment)}' " +
                "($fileCount file(s), 0 mutants woven — skeleton)",
        )
    }
}
