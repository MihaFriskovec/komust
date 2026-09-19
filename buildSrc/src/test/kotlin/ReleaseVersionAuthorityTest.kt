import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseVersionAuthorityTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `qualification rejects the checked in development snapshot`() {
        fixture("0.1.0-SNAPSHOT")

        val result = runner().buildAndFail()

        assertTrue(result.output.contains("must be fixed, not SNAPSHOT"))
    }

    @Test
    fun `qualification rejects absent and malformed version authority`() {
        fixture(null)
        assertTrue(runner().buildAndFail().output.contains("requires checked-in komustVersion"))

        fixture("release-one")
        assertTrue(runner().buildAndFail().output.contains("is not valid SemVer"))
    }

    @Test
    fun `qualification rejects a command line version override`() {
        fixture("0.1.0-alpha.1")

        val result = runner("-PkomustVersion=0.1.0-alpha.2").buildAndFail()

        assertTrue(result.output.contains("must come unchanged from gradle.properties"))
    }

    @Test
    fun `qualification accepts a fixed checked in release candidate`() {
        fixture("0.1.0-alpha.1")

        val result = runner().build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":$qualificationTask")?.outcome)
    }

    private fun fixture(version: String?) {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"publication-fixture\"\n")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("komust.publication") }
            publishing {
                publications.register<MavenPublication>("maven") { from(components["java"]) }
            }
            """.trimIndent(),
        )
        projectDir.resolve("src/main/java").createDirectories()
        projectDir.resolve("src/main/java/Fixture.java").writeText("public final class Fixture {}\n")
        projectDir.resolve("gradle.properties").writeText(
            buildString {
                appendLine("komustPublicIdentity=preferred")
                version?.let { appendLine("komustVersion=$it") }
            },
        )
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withPluginClasspath()
        .withArguments(qualificationTask, "--stacktrace", *arguments)

    private companion object {
        const val qualificationTask = "publishMavenPublicationToQualificationRepository"
    }
}
