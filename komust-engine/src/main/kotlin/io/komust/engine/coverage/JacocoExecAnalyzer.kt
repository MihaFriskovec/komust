package io.komust.engine.coverage

import org.jacoco.core.analysis.Analyzer
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.analysis.ICounter
import org.jacoco.core.data.ExecutionDataReader
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import org.objectweb.asm.ClassReader
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.walk

/**
 * Turns a test's JaCoCo `.exec` chunks into covered source lines per class, by
 * replaying them against the compiled classes the coverage pass observed
 * (ADR-0004 §1 — one shared compile).
 *
 * The line side of the join is exactly what JaCoCo's `Analyzer` reports: probe
 * hits mapped back through each class's `LineNumberTable`, with JaCoCo 0.8.15's
 * Kotlin bytecode-filter set already applied (`when`, `data class`, `suspend`,
 * synthetic bridges). Inline-function lines still need [InlineLineNormalizer] on
 * top — that lives in [CoveragePass], not here.
 *
 * Class bytes are read from disk **once** at construction and each per-test call
 * analyses only the classes that snapshot actually touched, so the cost is
 * linear in (tests × classes *touched*), not (tests × all classes).
 */
public class JacocoExecAnalyzer(classesDirs: List<Path>) {

    /** VM class name (`pkg/Name`) -> its `.class` bytes. */
    private val classBytes: Map<String, ByteArray> = loadClasses(classesDirs)

    init {
        require(classBytes.isNotEmpty()) {
            "no readable .class files to analyse coverage against under: $classesDirs"
        }
    }

    /** Every VM class name whose bytes were loaded. */
    internal val knownVmNames: Set<String> get() = classBytes.keys

    /**
     * Covered 1-based source lines per binary class name for one test's chunks.
     *
     * [alsoAnalyse] names extra VM classes to feed the analyser even when this
     * snapshot has no direct probe hits on them — used for inline callees, whose
     * coverage JaCoCo only attributes when the callee class is in the set.
     */
    public fun coveredLines(
        execChunks: List<ByteArray>,
        alsoAnalyse: Set<String> = emptySet(),
    ): Map<String, Set<Int>> {
        val executionData = ExecutionDataStore()
        val sessionInfo = SessionInfoStore()
        for (chunk in execChunks) {
            try {
                ExecutionDataReader(ByteArrayInputStream(chunk)).apply {
                    setExecutionDataVisitor(executionData::put)
                    setSessionInfoVisitor(sessionInfo::visitSessionInfo)
                    read()
                }
            } catch (_: Exception) {
                // A malformed chunk loses that slice of coverage, never the pass.
            }
        }

        val builder = CoverageBuilder()
        val analyzer = Analyzer(executionData, builder)
        val toAnalyse = LinkedHashSet<String>()
        executionData.contents.forEach { if (it.hasHits()) toAnalyse += it.name }
        toAnalyse += alsoAnalyse
        for (vmName in toAnalyse) {
            val bytes = classBytes[vmName] ?: continue
            try {
                analyzer.analyzeClass(bytes, vmName)
            } catch (_: Exception) {
                // One unreadable / too-new class must not abort the pass — the
                // same tolerance InlineLineNormalizer applies to SMAP reads.
            }
        }

        val result = HashMap<String, MutableSet<Int>>()
        for (cc in builder.classes) {
            if (cc.firstLine < 0) continue // no line info
            val binaryName = cc.name.replace('/', '.')
            val lines = result.getOrPut(binaryName) { LinkedHashSet() }
            for (line in cc.firstLine..cc.lastLine) {
                if (cc.getLine(line).status.isCovered()) lines += line
            }
            if (lines.isEmpty()) result.remove(binaryName)
        }
        return result
    }

    private fun Int.isCovered(): Boolean =
        this == ICounter.FULLY_COVERED || this == ICounter.PARTLY_COVERED

    private companion object {
        @OptIn(ExperimentalPathApi::class)
        fun loadClasses(dirs: List<Path>): Map<String, ByteArray> {
            val out = HashMap<String, ByteArray>()
            for (dir in dirs.distinct()) {
                if (!dir.exists() || !dir.isDirectory()) continue
                dir.walk()
                    .filter { it.extension == "class" }
                    .forEach { file ->
                        try {
                            val bytes = file.toFile().readBytes()
                            out[ClassReader(bytes).className] = bytes
                        } catch (_: Exception) {
                            // skip an unreadable class file
                        }
                    }
            }
            return out
        }
    }
}
