package io.komust.conventions

import org.gradle.api.GradleException

/** The two approved, atomic public coordinate sets. */
data class PublicIdentity(
    val group: String,
    val pluginId: String,
) {
    companion object {
        fun named(name: String): PublicIdentity = when (name) {
            "preferred" -> PublicIdentity(group = "io.komust", pluginId = "io.komust")
            "github-fallback" -> PublicIdentity(
                group = "io.github.mihafriskovec",
                pluginId = "io.github.mihafriskovec.komust",
            )
            else -> throw GradleException(
                "Unknown komustPublicIdentity '$name'; expected preferred or github-fallback",
            )
        }
    }
}
