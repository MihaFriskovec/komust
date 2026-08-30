package io.komust.compiler

import io.komust.compiler.ir.KotlinIrCompat
import io.komust.compiler.ir.PluginDiagnostics
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * The mutation surface's IR pass.
 *
 * ## This slice (#28): the first operator
 *
 * It weaves komust's first real operator — the **arithmetic additive swap**
 * ([ArithmeticMutation]) — into the module with the compile-once model: each
 * site `a + b` becomes `if (mutantActive("<id>")) a - b else a + b`, both
 * branches in one compilation, flipped at run time through
 * [io.komust.runtime.MutantRegistry]. Every woven site is recorded as a
 * [WovenMutant] keyed `(file, line, col, operator, ordinal)` and carrying its
 * enclosing binary class name.
 *
 * The traversal, operand-kind lookup and IR construction all live behind the
 * compat-shim seam ([KotlinIrCompat.weaveArithmeticOperators]); this class only
 * orchestrates and reports. Per the seam rule it imports only the SPI interface
 * it implements and its `generate` signature.
 *
 * The wider catalog (multiplicative / remainder rewrites, the skip-list guards)
 * and the enabled/disabled-operator option land with #29.
 */
internal class KomustIrGenerationExtension(
    private val diagnostics: PluginDiagnostics,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val module = KotlinIrCompat.moduleName(moduleFragment)
        val woven = KotlinIrCompat.weaveArithmeticOperators(moduleFragment, pluginContext, diagnostics)

        for (mutant in woven) {
            diagnostics.info(
                "komust-mutant id=${mutant.id} class=${mutant.binaryClassName} " +
                    "startOffset=${mutant.startOffset} path=${mutant.filePath}",
            )
        }
        diagnostics.info(
            "komust: arithmetic operator woven over module '$module' — " +
                "${woven.size} mutant(s), compile-once / runtime-switched",
        )
    }
}
