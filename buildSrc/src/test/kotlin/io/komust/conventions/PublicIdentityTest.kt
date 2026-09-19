package io.komust.conventions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicIdentityTest {
    @Test
    fun `preferred identity selects matching group and plugin namespace`() {
        assertEquals(PublicIdentity("io.komust", "io.komust"), PublicIdentity.named("preferred"))
    }

    @Test
    fun `fallback identity switches group and plugin namespace together`() {
        assertEquals(
            PublicIdentity("io.github.mihafriskovec", "io.github.mihafriskovec.komust"),
            PublicIdentity.named("github-fallback"),
        )
    }
}
