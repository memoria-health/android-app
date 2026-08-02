plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Build output lives OUTSIDE the repo, because the repo sits under a
// cloud-synced tree (~/Desktop) and the sync layer duplicates files it sees
// churning. That produced real build failures, not just noise: the sync created
// "fraunces_144_soft_medium 2.ttf" inside packaged_res and AAPT rejected it with
// "Failed file name validation" — a resource the source tree never contained.
// The merged_res directory had picked up dozens of " 2" copies of .flat files
// the same way.
//
// The workspace CLAUDE.md already documents cloud sync aborting git operations;
// this is the same hazard reaching the build. Redirecting the build directory
// removes the churn from the synced tree entirely, which is more reliable than
// asking every developer to configure a sync exclusion.
//
// Override with -Pmemoria.buildDir=/some/path if this location is unsuitable.
val buildRoot: File = providers.gradleProperty("memoria.buildDir")
    .map { File(it) }
    .getOrElse(File(System.getProperty("user.home"), ".gradle-builds/memoria-caregiver-android"))

allprojects {
    val relative = project.path.removePrefix(":").replace(':', '/').ifEmpty { "root" }
    layout.buildDirectory.set(File(buildRoot, relative))
}
