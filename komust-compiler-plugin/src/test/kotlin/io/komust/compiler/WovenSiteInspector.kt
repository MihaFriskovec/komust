package io.komust.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * A test-only second compiler plugin. Registered *after* komust's, it walks the
 * already-woven IR and records the `startOffset` of every runtime-switch
 * `if/else` — keyed by the mutant id lifted from its `mutantActive("<id>")`
 * guard.
 *
 * The startOffset-preservation check (issue #28, AC3) cross-references these
 * against the offsets komust itself reported for the same ids: the injected
 * `if/else` must sit at exactly the source position of the operator it replaced.
 */
class WovenSiteOffsets {
    val startOffsetById: MutableMap<String, Int> = LinkedHashMap()
}

class WovenSiteInspectorRegistrar(private val sink: WovenSiteOffsets) : CompilerPluginRegistrar() {

    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(InspectorExtension(sink))
    }

    private class InspectorExtension(private val sink: WovenSiteOffsets) : IrGenerationExtension {
        override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
            moduleFragment.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
                override fun visitWhen(expression: IrWhen): IrExpression {
                    val condition = expression.branches.firstOrNull()?.condition
                    if (condition is IrCall && condition.symbol.owner.name.asString() == "mutantActive") {
                        val idArg = condition.arguments.firstOrNull()
                        if (idArg is IrConst && idArg.value is String) {
                            sink.startOffsetById[idArg.value as String] = expression.startOffset
                        }
                    }
                    return super.visitWhen(expression)
                }
            })
        }
    }
}
