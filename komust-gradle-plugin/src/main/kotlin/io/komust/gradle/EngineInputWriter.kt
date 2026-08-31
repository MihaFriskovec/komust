package io.komust.gradle

import java.io.File

/**
 * Serialises the **engine input contract** to the JSON file the forked engine
 * reads as its single argument (ADR-0005 §2).
 *
 * Hand-rolled rather than depending on `komust-engine` at plugin runtime — the
 * engine is *forked*, never loaded into the Gradle daemon. The shape here must
 * stay in step with `io.komust.engine.EngineInput` (`komust-engine`); that class
 * decodes with `ignoreUnknownKeys`, so an additive field on either side is safe.
 */
internal object EngineInputWriter {

    data class Model(
        val classesUnderTest: List<String>,
        val testClassRoots: List<String>,
        val mutationManifests: List<String>,
        val workerClasspath: List<String>,
        val reloadableRoots: List<String>,
        val outputDir: String,
        val workers: Int,
        val timeoutFactor: Double,
        val cache: Boolean,
        val humanReport: Boolean,
        val consoleSurvivorsOnly: Boolean,
        val testOverrideGlobal: List<String>,
        val testOverridePerFile: Map<String, List<String>>,
        val komustVersion: String,
        val kotlinVersion: String,
        val jdkVersion: String,
    )

    fun write(target: File, model: Model) {
        target.absoluteFile.parentFile?.mkdirs()
        target.writeText(render(model))
    }

    fun render(m: Model): String = buildString {
        append("{\n")
        append("  ").kv("classesUnderTest", array(m.classesUnderTest)).append(",\n")
        append("  ").kv("testClassRoots", array(m.testClassRoots)).append(",\n")
        append("  ").kv("mutationManifests", array(m.mutationManifests)).append(",\n")
        append("  ").kv("workerClasspath", array(m.workerClasspath)).append(",\n")
        append("  ").kv("reloadableRoots", array(m.reloadableRoots)).append(",\n")
        append("  ").kv("outputDir", str(m.outputDir)).append(",\n")
        append("  \"config\": {\n")
        append("    ").kv("workers", m.workers.toString()).append(",\n")
        append("    ").kv("timeoutFactor", m.timeoutFactor.toString()).append(",\n")
        append("    ").kv("cache", m.cache.toString()).append(",\n")
        append("    ").kv("humanReport", m.humanReport.toString()).append(",\n")
        append("    ").kv("consoleSurvivorsOnly", m.consoleSurvivorsOnly.toString())
        if (m.testOverrideGlobal.isNotEmpty() || m.testOverridePerFile.isNotEmpty()) {
            append(",\n    \"testOverride\": {\n")
            append("      ").kv("global", array(m.testOverrideGlobal)).append(",\n")
            append("      \"perFile\": {")
            m.testOverridePerFile.entries.forEachIndexed { i, (path, ids) ->
                append(if (i == 0) "\n" else ",\n")
                append("        ").kv(path, array(ids))
            }
            if (m.testOverridePerFile.isNotEmpty()) append("\n      ")
            append("}\n    }\n")
        } else {
            append("\n")
        }
        append("  },\n")
        append("  ").kv("komustVersion", str(m.komustVersion)).append(",\n")
        append("  ").kv("kotlinVersion", str(m.kotlinVersion)).append(",\n")
        append("  ").kv("jdkVersion", str(m.jdkVersion)).append("\n")
        append("}\n")
    }

    private fun StringBuilder.kv(key: String, rawValue: String): StringBuilder =
        append(str(key)).append(": ").append(rawValue)

    private fun array(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { str(it) }

    private fun str(value: String): String = buildString {
        append('"')
        for (ch in value) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
        append('"')
    }
}
