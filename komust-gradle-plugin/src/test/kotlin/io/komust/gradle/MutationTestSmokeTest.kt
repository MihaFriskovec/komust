package io.komust.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The single Gradle TestKit **assembly smoke test** (#38, walking-skeleton story
 * 22). It proves the whole product wires together in a real build: apply
 * `io.komust` to a fixture project with a git-modified production file, run
 * `mutationTest`, and assert
 *
 *  - `report.json` / `survivors.json` land under `build/komust/` for the
 *    fixture's diff,
 *  - the ordinary `compileKotlin` output carries no woven guard,
 *  - `mutationTest` is not part of `check`.
 *
 * Depth lives in the compiler-plugin and engine seams; this test is deliberately
 * thin (it forks a Gradle build that forks compiler + engine JVMs).
 */
class MutationTestSmokeTest {

    private val testMavenRepo = System.getProperty("komust.testMavenRepo")
        ?: error("komust.testMavenRepo system property not set — see komust-gradle-plugin/build.gradle.kts")
    private val komustVersion = System.getProperty("komust.version") ?: error("komust.version not set")
    private val kotlinVersion = System.getProperty("komust.kotlinVersion") ?: error("komust.kotlinVersion not set")

    @Test
    fun `mutationTest produces report_json and survivors_json for the git diff, and is absent from check`(
        @TempDir dir: File,
    ) {
        writeFixture(dir)

        val run = gradle(dir, "mutationTest", "--refresh-dependencies", "--stacktrace")
        assertEquals(
            TaskOutcome.SUCCESS,
            run.task(":mutationTest")?.outcome,
            "mutationTest did not succeed:\n${run.output}",
        )

        val komustDir = dir.resolve("build/komust")
        val report = komustDir.resolve("report.json")
        val survivors = komustDir.resolve("survivors.json")
        assertTrue(report.isFile, "no report.json under build/komust/\n${run.output}")
        assertTrue(survivors.isFile, "no survivors.json under build/komust/")
        assertTrue(komustDir.resolve("mutants-main.json").isFile, "no mutation manifest")

        val reportText = report.readText()
        assertTrue(reportText.contains("\"schemaVersion\""), reportText)
        // The edited method is `scale`; only its mutants are in scope for this diff.
        assertTrue(reportText.contains("fixture/Calc.kt"), reportText)
        assertTrue(reportText.contains("\"enclosingSymbol\": \"scale\""), reportText)
        assertFalse(reportText.contains("\"enclosingSymbol\": \"add\""), "add() was not edited — it must be out of scope")
        // `scale` is only loosely tested → at least one survivor.
        assertTrue(survivors.readText().contains("\"enclosingSymbol\": \"scale\""), survivors.readText())

        // The ordinary compile is mutant-free; the dedicated one is not.
        val ordinary = dir.resolve("build/classes/kotlin/main/fixture/Calc.class")
        val mutation = dir.resolve("build/komust/classes/main/fixture/Calc.class")
        assertTrue(ordinary.isFile && mutation.isFile, "expected both compiles to have run")
        assertFalse(ordinary.readBytes().containsUtf8("mutantActive"), "compileKotlin output carries a woven guard")
        assertTrue(mutation.readBytes().containsUtf8("mutantActive"), "the mutation compile did not weave")

        // mutationTest is not wired into check.
        val dryRun = gradle(dir, "check", "--dry-run")
        assertFalse(dryRun.output.contains(":mutationTest "), dryRun.output)
        assertFalse(dryRun.output.contains(":compileKomustKotlin "), dryRun.output)
    }

    private fun gradle(dir: File, vararg args: String) =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*args)
            .forwardOutput()
            .build()

    private fun writeFixture(dir: File) {
        dir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$testMavenRepo") }
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("$testMavenRepo") }
                    mavenCentral()
                }
            }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$kotlinVersion"
                id("io.komust") version "$komustVersion"
            }
            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            kotlin { jvmToolchain(21) }
            tasks.test { useJUnitPlatform() }
            """.trimIndent(),
        )

        val main = dir.resolve("src/main/kotlin/fixture").apply { mkdirs() }
        val test = dir.resolve("src/test/kotlin/fixture").apply { mkdirs() }
        main.resolve("Calc.kt").writeText(
            """
            package fixture

            class Calc {
                fun add(a: Int, b: Int): Int = a + b
                fun scale(a: Int, b: Int): Int = a * b
            }
            """.trimIndent(),
        )
        test.resolve("CalcTest.kt").writeText(
            """
            package fixture

            import org.junit.jupiter.api.Test
            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Assertions.assertTrue

            class CalcTest {
                @Test fun addExact() { assertEquals(5, Calc().add(2, 3)) }
                @Test fun scaleLoose() { assertTrue(Calc().scale(3, 4) > 0) }
            }
            """.trimIndent(),
        )

        git(dir, "init", "--initial-branch=main")
        git(dir, "config", "user.email", "smoke@komust.test")
        git(dir, "config", "user.name", "komust smoke")
        git(dir, "add", "-A")
        git(dir, "commit", "-m", "fixture baseline")

        // The modified-files diff: edit `scale` only. `add` stays out of scope.
        main.resolve("Calc.kt").writeText(
            """
            package fixture

            class Calc {
                fun add(a: Int, b: Int): Int = a + b
                fun scale(a: Int, b: Int): Int = b * a
            }
            """.trimIndent(),
        )
    }

    private fun git(dir: File, vararg args: String) {
        val code = ProcessBuilder(listOf("git", *args))
            .directory(dir)
            .inheritIO()
            .start()
            .waitFor()
        check(code == 0) { "git ${args.joinToString(" ")} failed ($code)" }
    }

    private fun ByteArray.containsUtf8(needle: String): Boolean =
        String(this, Charsets.ISO_8859_1).contains(needle)
}
