// Imported rather than fully qualified: inside the `unitTests.all { }` scope,
// `java` resolves to the Java plugin extension, so `java.util.concurrent...`
// fails to compile.
import java.util.concurrent.atomic.AtomicLong

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.memoria.caregiver"
    compileSdk = 35

    defaultConfig {
        // Deliberately NOT co.sunny.* — see DECISIONS.md D-006.
        // An applicationId can never change after the first Play upload.
        applicationId = "com.memoria.caregiver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs merged resources to inflate themes and resolve fonts.
            isIncludeAndroidResources = true

            all {
                it.testLogging {
                    events("passed", "skipped", "failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
                // HARNESS.md §7 requires a stated pass count, not just exit 0.
                // Gradle prints nothing on success by default, which makes "all
                // tests pass" unfalsifiable.
                //
                // On its own this does NOT cover the empty-source-set case, which
                // is the case it was written for: Gradle skips the root-suite
                // callback entirely when a task discovers no tests, so an empty
                // source set prints nothing and exits 0 — indistinguishable from
                // green. The counter plus the doLast below is what closes it.
                // (Gradle's own failOnNoDiscoveredTests would be the one-liner,
                // but it does not exist in Gradle 8.9.)
                val discovered = AtomicLong(-1)
                it.afterSuite(
                    KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
                        if (desc.parent == null) {
                            discovered.set(result.testCount)
                            println(
                                "TEST SUMMARY: ${result.testCount} tests, " +
                                    "${result.successfulTestCount} passed, " +
                                    "${result.failedTestCount} failed, " +
                                    "${result.skippedTestCount} skipped",
                            )
                        }
                    }),
                )
                // `discovered` is read by the verifyUnitTestsRan finalizer below
                // rather than a doLast here: with no test sources at all the task
                // is NO-SOURCE and doLast never executes, which is exactly the
                // case being guarded. Verified by mutation.
                discovered.get()
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Release unit tests are disabled, so `./gradlew test` means the debug suite.
//
// Compose UI tests need an activity to host the composition, supplied by
// ui-test-manifest — a manifest-only artifact that must reach the variant's
// manifest merge. It is debugImplementation by design, because shipping a test
// activity in a release build is exactly what nobody wants. The release variant
// therefore has no ComponentActivity, and every createComposeRule() test dies
// with "Unable to resolve activity for Intent".
//
// The alternative, putting ui-test-manifest on release too, pollutes the
// shipped app to satisfy a suite that adds no coverage: same Kotlin, same
// resources, minification off. It also ran the whole suite twice, doubling
// gate wall time for nothing.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

/**
 * Fails the build if the unit test task produced no test cases.
 *
 * HARNESS.md §4 rests on "a green run means tests actually ran". Without this,
 * deleting or misconfiguring the test source set makes `./gradlew test` print
 * BUILD SUCCESSFUL and exit 0 — the task goes NO-SOURCE and is skipped silently.
 * Verified by mutation: moving app/src/test aside produced exit 0 before this
 * task existed.
 *
 * A finalizer rather than a doLast on the test task itself, because a skipped
 * task never runs its own actions. Finalizers still run.
 */
val verifyUnitTestsRan = tasks.register("verifyUnitTestsRan") {
    val resultsDir = layout.buildDirectory.dir("test-results/testDebugUnitTest")
    doLast {
        val files = resultsDir.get().asFile
            .listFiles { f: File -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
            ?: emptyArray()
        val cases = files.sumOf { f -> Regex("<testcase\\s").findAll(f.readText()).count() }
        if (cases == 0) {
            throw GradleException(
                "No unit tests ran. An empty or misconfigured test source set " +
                    "otherwise reports BUILD SUCCESSFUL, which HARNESS.md §4 " +
                    "relies on being impossible.",
            )
        }
    }
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    finalizedBy(verifyUnitTestsRan)
}

dependencies {
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Test-only: lets ThemeTest walk every Material3 color role by reflection,
    // so a Material version that ADDS a role fails the palette test instead of
    // silently shipping a baseline lavender.
    testImplementation(libs.kotlin.reflect)

    // Manifest-only artifact supplying the ComponentActivity that
    // createComposeRule() hosts the composition in. Debug only — see above.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
