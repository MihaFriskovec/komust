package io.komust.engine.coverage

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.walk

/**
 * Concentrates all Kotlin inline-function line knowledge in one place, so the
 * coverage index the mutant selector consumes is exact (ADR-0004 §3).
 *
 * When the Kotlin compiler inlines a function, the callee's body bytecode is
 * copied into every call site and given synthetic output line numbers in the
 * *caller* class. JaCoCo therefore records probe hits against those synthetic
 * lines of the caller, not against the callee's real source lines — where the
 * mutant that lives inside the inline function is keyed (its enclosing binary
 * class name is the callee's).
 *
 * This normaliser reads each class's `SourceDebugExtension` (SMAP) and, for a
 * covered `(callerClass, outputLine)`, yields the `(calleeClass, sourceLine)`
 * the compiler copied that line from. The index builder adds **both** — the
 * normalisation is purely additive, so it can only *prevent* a false
 * `NO_COVERAGE`, never introduce a wrong kill.
 *
 * Build one via [fromClassesDirs] over the same compiled classes
 * [JacocoExecAnalyzer] scans.
 */
public class InlineLineNormalizer internal constructor(
    private val perClass: Map<String, ParsedSmap>,
) {
    /**
     * The `(calleeBinaryClass, calleeSourceLine)` that [outputLine] of
     * [callerBinaryClass] was inlined from, or `null` when that line is the
     * caller's own code (no inline expansion there).
     */
    internal fun calleeSite(callerBinaryClass: String, outputLine: Int): CoverageKey? {
        val smap = perClass[callerBinaryClass] ?: return null
        for (mapping in smap.lineMappings) {
            val resolved = mapping.resolve(outputLine) ?: continue
            val (fileId, inputLine) = resolved
            val calleeClass = smap.files[fileId]?.binaryClassName ?: continue
            if (calleeClass == callerBinaryClass) continue // self-mapping, not an inline callee
            if (inputLine < 1) continue
            return CoverageKey(calleeClass, inputLine)
        }
        return null
    }

    /** Whether any class carried inline SMAP data — for logging / tests. */
    public val hasInlineData: Boolean get() = perClass.values.any { it.lineMappings.isNotEmpty() }

    /**
     * The JVM names of every class an inline body was copied *from*. JaCoCo's own
     * inline handling only attributes those bodies' coverage if the callee class
     * is in the analysis set, so [JacocoExecAnalyzer] is told to include these.
     */
    internal val calleeVmNames: Set<String> = perClass.values
        .flatMap { smap -> smap.lineMappings.mapNotNull { smap.files[it.fileId]?.vmClassName } }
        .toSet()

    public companion object {
        public val EMPTY: InlineLineNormalizer = InlineLineNormalizer(emptyMap())

        /** Scan every `.class` under [classesDirs], reading its SMAP if present. */
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        public fun fromClassesDirs(classesDirs: List<Path>): InlineLineNormalizer {
            val perClass = HashMap<String, ParsedSmap>()
            for (dir in classesDirs) {
                if (!dir.exists() || !dir.isDirectory()) continue
                dir.walk()
                    .filter { it.extension == "class" }
                    .forEach { classFile ->
                        val (name, smap) = readSmap(classFile) ?: return@forEach
                        if (smap.lineMappings.isNotEmpty()) perClass[name] = smap
                    }
            }
            return InlineLineNormalizer(perClass)
        }

        private fun readSmap(classFile: Path): Pair<String, ParsedSmap>? =
            try {
                var binaryName: String? = null
                var parsed: ParsedSmap = ParsedSmap.EMPTY
                val reader = ClassReader(classFile.toFile().readBytes())
                reader.accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visit(
                            version: Int,
                            access: Int,
                            name: String,
                            signature: String?,
                            superName: String?,
                            interfaces: Array<out String>?,
                        ) {
                            binaryName = name.replace('/', '.')
                        }

                        override fun visitSource(source: String?, debug: String?) {
                            parsed = SmapParser.parse(debug)
                        }
                    },
                    // NB: not SKIP_DEBUG — that would drop SourceDebugExtension,
                    // which is exactly the attribute carrying the SMAP.
                    ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
                )
                binaryName?.let { it to parsed }
            } catch (_: Exception) {
                null
            }
    }
}
