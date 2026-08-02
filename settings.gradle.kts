pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // livekit-android depends on com.github.davidliu:audioswitch, which is
        // published only on JitPack. Scoped to that one group so JitPack cannot
        // silently become a source for anything else — it serves arbitrary
        // GitHub repos, so an unrestricted entry is a supply-chain surface.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.davidliu") }
        }
    }
}

rootProject.name = "MemoriaCaregiver"
include(":app")
