package io.komust.compiler

import io.komust.compiler.ir.KotlinIrCompat
import io.komust.compiler.ir.PluginDiagnostics
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * The mutation surface's IR pass.
 *
 * It weaves komust's **full default operator catalog** (ADR-0001, #29) into the
 * module with the compile-once model: each site becomes
 * `if (mutantActive("<id>")) <mutant> else <original>`, both branches in one
 * compilation, flipped at run time through [io.komust.runtime.MutantRegistry].
 * Every woven site is recorded as a [WovenMutant] keyed
 * `(file, line, col, token, ordinal)` and carrying its enclosing binary class
 * name.
 *
 * The [config] narrows the catalog to the operators the Gradle plugin's
 * `operators { enable / disable }` DSL left on.
 *
 * The traversal, operand-kind lookups, skip-list guards and IR construction all
 * live behind the compat-shim seam ([KotlinIrCompat.weaveMutations]); this class
 * only orchestrates and reports. Per the seam rule it imports only the SPI
 * interface it implements and its `generate` signature.
 */
internal class KomustIrGenerationExtension(
    private val diagnostics: PluginDiagnostics,
    private val config: OperatorConfig,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val module = KotlinIrCompat.moduleName(moduleFragment)
        val woven = KotlinIrCompat.weaveMutations(moduleFragment, pluginContext, diagnostics, config)

        for (mutant in woven) {
            diagnostics.info(
                "komust-mutant id=${mutant.id} op=${mutant.operator.slug} class=${mutant.binaryClassName} " +
                    "startOffset=${mutant.startOffset} path=${mutant.filePath} desc=${mutant.description}",
            )
        }
        diagnostics.info(
            "komust: woven ${woven.size} mutant(s) over module '$module' — compile-once / runtime-switched",
        )
    }
}
