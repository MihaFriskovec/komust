package io.komust.engine.report

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Golden-file helper. [check] compares [actual] against the committed golden at
 * `src/test/resources/golden/<name>`. When the golden is missing it is written
 * and the assertion fails with a note — so adding a case is one run, and a
 * deliberate contract change is `rm` the golden + re-run + review the diff.
 */
object GoldenFiles {

    fun check(name: String, actual: String) {
        val golden = TestPaths.goldenDir.resolve(name)
        if (!golden.exists()) {
            Files.createDirectories(golden.parent)
            golden.writeText(actual)
            error("golden '$name' did not exist — wrote it from actual output; review the diff and re-run")
        }
        assertEquals(golden.readText(), actual, "output drifted from golden '$name' (schema/contract change?)")
    }
}
