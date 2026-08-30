package io.komust.scope

import java.nio.file.Files
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScopeJsonTest {

    @Test
    fun `encodes a stable documented shape`() {
        val scope = MutationScope.of(
            mapOf(
                "src/main/kotlin/com/example/Foo.kt" to listOf(LineRange(10, 14), LineRange(20, 20)),
                "src/main/kotlin/com/example/Bar.kt" to listOf(LineRange.WHOLE_FILE),
            ),
        )

        val expected = """
            {
              "version": 1,
              "files": [
                {
                  "path": "src/main/kotlin/com/example/Bar.kt",
                  "wholeFile": true
                },
                {
                  "path": "src/main/kotlin/com/example/Foo.kt",
                  "ranges": [
                    {
                      "start": 10,
                      "end": 14
                    },
                    {
                      "start": 20,
                      "end": 20
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        assertEquals(expected, ScopeJson.encode(scope))
    }

    @Test
    fun `empty scope encodes to an empty files array`() {
        assertEquals(
            """
                {
                  "version": 1,
                  "files": []
                }
            """.trimIndent(),
            ScopeJson.encode(MutationScope.EMPTY),
        )
    }

    @Test
    fun `round-trips through encode and decode`() {
        val scope = MutationScope.of(
            mapOf(
                "A.kt" to listOf(LineRange(1, 2), LineRange(7, 9)),
                "B.kt" to listOf(LineRange.WHOLE_FILE),
            ),
        )
        assertEquals(scope, ScopeJson.decode(ScopeJson.encode(scope)))
    }

    @Test
    fun `write creates parent directories and a trailing newline`() {
        val target = Files.createTempDirectory("scope-json").resolve("build/komust/scope.json")
        ScopeJson.write(MutationScope.EMPTY, target)
        assertEquals(ScopeJson.encode(MutationScope.EMPTY) + "\n", target.readText())
    }

    @Test
    fun `rejects an unknown version`() {
        val ex = assertThrows<ScopeResolutionException> {
            ScopeJson.decode("""{ "version": 2, "files": [] }""")
        }
        assertEquals(true, ex.message!!.contains("version 2"))
    }

    @Test
    fun `rejects an entry with neither wholeFile nor ranges`() {
        assertThrows<ScopeResolutionException> {
            ScopeJson.decode("""{ "version": 1, "files": [ { "path": "A.kt" } ] }""")
        }
    }

    @Test
    fun `rejects malformed json`() {
        assertThrows<ScopeResolutionException> { ScopeJson.decode("not json") }
    }
}
