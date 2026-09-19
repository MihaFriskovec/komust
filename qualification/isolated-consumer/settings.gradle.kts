val candidateRepository = providers.gradleProperty("komustCandidateRepository").get()
val candidateVersion = providers.gradleProperty("komustCandidateVersion").get()
val candidateGroup = providers.gradleProperty("komustCandidateGroup").get()

require(Regex("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?").matches(candidateVersion)) {
    "The isolated consumer requires a fixed SemVer candidate, got '$candidateVersion'"
}
require(!candidateVersion.endsWith("-SNAPSHOT")) {
    "The isolated consumer refuses SNAPSHOT candidates: $candidateVersion"
}

pluginManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "komustCandidatePlugins"
                    url = uri(providers.gradleProperty("komustCandidateRepository").get())
                }
            }
            filter {
                includeGroup(providers.gradleProperty("komustCandidatePluginId").get())
                includeGroup(providers.gradleProperty("komustCandidateGroup").get())
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            plugin("komust", providers.gradleProperty("komustCandidatePluginId").get())
                .version(providers.gradleProperty("komustCandidateVersion").get())
        }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "komustCandidateModules"
                    url = uri(candidateRepository)
                }
            }
            filter {
                includeGroup(candidateGroup)
                includeGroup(providers.gradleProperty("komustCandidatePluginId").get())
            }
        }
        mavenCentral()
    }
}

rootProject.name = "komust-isolated-consumer"
