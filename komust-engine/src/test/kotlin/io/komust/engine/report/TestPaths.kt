package io.komust.engine.report

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Resolves repo locations from wherever the test JVM's working directory
 * happens to be (`komust-engine/` under Gradle, the repo root under some IDE
 * runners) by walking up until a landmark is found.
 */
object TestPaths {

    /** The `komust-engine` module directory. */
    val moduleDir: Path by lazy {
        walkUp { it.fileName?.toString() == "komust-engine" && it.resolve("build.gradle.kts").exists() }
            ?: walkUp { it.resolve("komust-engine/build.gradle.kts").exists() }?.resolve("komust-engine")
            ?: error("could not locate the komust-engine module dir from $start")
    }

    /** The repo root (holds `settings.gradle.kts` and `schema/`). */
    val repoRoot: Path by lazy {
        walkUp { it.resolve("settings.gradle.kts").exists() && it.resolve("schema").exists() }
            ?: error("could not locate the repo root from $start")
    }

    val goldenDir: Path get() = moduleDir.resolve("src/test/resources/golden")
    val schemaDir: Path get() = repoRoot.resolve("schema")

    private val start: Path get() = Path.of("").toAbsolutePath()

    private fun walkUp(match: (Path) -> Boolean): Path? {
        var p: Path? = start
        while (p != null) {
            if (match(p)) return p
            p = p.parent
        }
        return null
    }
}
