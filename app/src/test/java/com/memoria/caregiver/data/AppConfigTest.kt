package com.memoria.caregiver.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Configuration resolution, spec at PARITY.md §4 and Networking/AppConfig.swift.
 *
 * Release constants and debug fallbacks are asserted as literals rather than
 * against the constants in the source, because the literal string is the thing
 * being ported. Comparing the code to itself would pass with a typo in it.
 */
class AppConfigTest {

    private val noEnv: (String) -> String? = { null }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    private val fullOverrides = ConfigOverrides(
        supabaseUrl = "http://192.168.1.42:54341",
        supabaseAnonKey = "sb_publishable_localdev",
        backendUrl = "http://192.168.1.42:8002",
        enableClipCapture = true,
    )

    // ---------------------------------------------------------------- step 4

    @Test fun `release uses the production constants`() {
        val c = AppConfigResolver.resolve(isDebug = false, ConfigOverrides.NONE, noEnv)

        assertEquals("https://zgifmvjnggmgzvtnosnu.supabase.co", c.supabaseUrl)
        assertEquals("sb_publishable_OsfCMOdEIb5gvhRWDyNivg_7GriAmyP", c.supabaseAnonKey)
        assertEquals("https://nemara-backend-production.up.railway.app", c.backendUrl)
        assertFalse(c.enableClipCapture)
    }

    /**
     * The rule that matters most in this file. The per-developer override lives
     * only on the machine that cuts the build, so a release build that honoured
     * it would ship pointing at someone's home network.
     */
    @Test fun `release ignores an override that debug honours`() {
        val debug = AppConfigResolver.resolve(isDebug = true, fullOverrides, noEnv)
        val release = AppConfigResolver.resolve(isDebug = false, fullOverrides, noEnv)

        // Debug takes every one of them.
        assertEquals("http://192.168.1.42:54341", debug.supabaseUrl)
        assertEquals("sb_publishable_localdev", debug.supabaseAnonKey)
        assertEquals("http://192.168.1.42:8002", debug.backendUrl)
        assertTrue(debug.enableClipCapture)

        // Release takes none of them.
        assertEquals("https://zgifmvjnggmgzvtnosnu.supabase.co", release.supabaseUrl)
        assertEquals("sb_publishable_OsfCMOdEIb5gvhRWDyNivg_7GriAmyP", release.supabaseAnonKey)
        assertEquals("https://nemara-backend-production.up.railway.app", release.backendUrl)
        assertFalse(release.enableClipCapture)
    }

    @Test fun `release ignores the environment that debug honours`() {
        val e = env(
            "SUPABASE_URL" to "http://ci-host:54341",
            "SUPABASE_ANON_KEY" to "sb_publishable_ci",
            "NEMARA_BACKEND_URL" to "http://ci-host:8002",
            "NEMARA_ENABLE_CLIP_CAPTURE" to "YES",
        )

        val debug = AppConfigResolver.resolve(isDebug = true, ConfigOverrides.NONE, e)
        assertEquals("http://ci-host:54341", debug.supabaseUrl)
        assertEquals("sb_publishable_ci", debug.supabaseAnonKey)
        assertEquals("http://ci-host:8002", debug.backendUrl)
        assertTrue(debug.enableClipCapture)

        val release = AppConfigResolver.resolve(isDebug = false, ConfigOverrides.NONE, e)
        assertEquals("https://zgifmvjnggmgzvtnosnu.supabase.co", release.supabaseUrl)
        assertEquals("sb_publishable_OsfCMOdEIb5gvhRWDyNivg_7GriAmyP", release.supabaseAnonKey)
        assertEquals("https://nemara-backend-production.up.railway.app", release.backendUrl)
        assertFalse(release.enableClipCapture)
    }

    /**
     * Clip capture is the single choke point for the whole data-collection
     * chain, so release forces it off even when both earlier steps say on.
     */
    @Test fun `release forces clip capture off with override and environment both on`() {
        val c = AppConfigResolver.resolve(
            isDebug = false,
            ConfigOverrides(enableClipCapture = true),
            env("NEMARA_ENABLE_CLIP_CAPTURE" to "1"),
        )
        assertFalse(c.enableClipCapture)
    }

    // ---------------------------------------------------------------- step 3

    @Test fun `debug with nothing set uses the debug fallbacks`() {
        val c = AppConfigResolver.resolve(isDebug = true, ConfigOverrides.NONE, noEnv)

        assertEquals("http://10.0.2.2:54341", c.supabaseUrl)
        assertEquals("sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH", c.supabaseAnonKey)
        assertEquals("http://10.0.2.2:8002", c.backendUrl)
        assertFalse(c.enableClipCapture)
    }

    /** Bonjour `.local` names do not resolve on Android — DIFFERENCES.md D4. */
    @Test fun `debug fallback hosts are IP-based`() {
        val c = AppConfigResolver.resolve(isDebug = true, ConfigOverrides.NONE, noEnv)
        assertFalse(c.supabaseUrl!!.contains(".local"))
        assertFalse(c.backendUrl.contains(".local"))
    }

    // ------------------------------------------------------ order, steps 1-3

    @Test fun `the override beats the environment`() {
        val c = AppConfigResolver.resolve(
            isDebug = true,
            fullOverrides,
            env(
                "SUPABASE_URL" to "http://ci-host:54341",
                "SUPABASE_ANON_KEY" to "sb_publishable_ci",
                "NEMARA_BACKEND_URL" to "http://ci-host:8002",
                "NEMARA_ENABLE_CLIP_CAPTURE" to "0",
            ),
        )

        assertEquals("http://192.168.1.42:54341", c.supabaseUrl)
        assertEquals("sb_publishable_localdev", c.supabaseAnonKey)
        assertEquals("http://192.168.1.42:8002", c.backendUrl)
        assertTrue(c.enableClipCapture)
    }

    @Test fun `the environment beats the debug fallback`() {
        val c = AppConfigResolver.resolve(
            isDebug = true,
            ConfigOverrides.NONE,
            env("NEMARA_BACKEND_URL" to "https://dev.ngrok-free.dev"),
        )

        assertEquals("https://dev.ngrok-free.dev", c.backendUrl)
        // Untouched fields still fall all the way through.
        assertEquals("http://10.0.2.2:54341", c.supabaseUrl)
    }

    @Test fun `each field resolves independently`() {
        val c = AppConfigResolver.resolve(
            isDebug = true,
            ConfigOverrides(backendUrl = "http://192.168.1.42:8002"),
            env("SUPABASE_ANON_KEY" to "sb_publishable_ci"),
        )

        assertEquals("http://192.168.1.42:8002", c.backendUrl) // step 1
        assertEquals("sb_publishable_ci", c.supabaseAnonKey) // step 2
        assertEquals("http://10.0.2.2:54341", c.supabaseUrl) // step 3
    }

    /**
     * A false override must be honoured, not read as "unset". This is why the
     * override type is a nullable Boolean rather than a plain one.
     */
    @Test fun `an override of false beats an environment variable of true`() {
        val c = AppConfigResolver.resolve(
            isDebug = true,
            ConfigOverrides(enableClipCapture = false),
            env("NEMARA_ENABLE_CLIP_CAPTURE" to "YES"),
        )
        assertFalse(c.enableClipCapture)
    }

    // ---------------------------------------------------------- empty values

    /**
     * iOS checks the anon key for emptiness explicitly at both steps, because
     * an empty string is a configured-but-useless value.
     */
    @Test fun `an empty anon key falls through at every step`() {
        val overrideEmpty = AppConfigResolver.resolve(
            isDebug = true,
            ConfigOverrides(supabaseAnonKey = ""),
            env("SUPABASE_ANON_KEY" to "sb_publishable_ci"),
        )
        assertEquals("sb_publishable_ci", overrideEmpty.supabaseAnonKey)

        val bothEmpty = AppConfigResolver.resolve(
            isDebug = true,
            ConfigOverrides(supabaseAnonKey = ""),
            env("SUPABASE_ANON_KEY" to ""),
        )
        assertEquals("sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH", bothEmpty.supabaseAnonKey)
    }

    /**
     * iOS builds URLs with `URL(string:)`, which returns nil for an empty or
     * unparseable string, and nil falls through to the next step.
     */
    @Test fun `an unusable URL falls through instead of being used`() {
        val c = AppConfigResolver.resolve(
            isDebug = true,
            ConfigOverrides(backendUrl = "", supabaseUrl = "http://has a space:8002"),
            noEnv,
        )
        assertEquals("http://10.0.2.2:8002", c.backendUrl)
        assertEquals("http://10.0.2.2:54341", c.supabaseUrl)
    }

    @Test fun `URL acceptance matches URL(string) rather than a stricter rule`() {
        assertNull(AppConfigResolver.url(null))
        assertNull(AppConfigResolver.url(""))
        assertNull(AppConfigResolver.url("http://has a space"))
        assertNull(AppConfigResolver.url("http://exa^mple.com"))

        assertEquals("http://10.0.2.2:8002", AppConfigResolver.url("http://10.0.2.2:8002"))
        assertEquals(
            "https://nemara-backend-production.up.railway.app",
            AppConfigResolver.url("https://nemara-backend-production.up.railway.app"),
        )
        // Swift's URL(string:) accepts a relative string too, so this does not
        // reject one. Being stricter than iOS would be a behaviour change
        // dressed up as validation.
        assertEquals("backend", AppConfigResolver.url("backend"))
    }

    // -------------------------------------------------- NSString.boolValue

    @Test fun `boolValue reads the affirmative spellings iOS accepts`() {
        listOf("1", "2", "9", "true", "TRUE", "True", "t", "T", "yes", "YES", "y", "Y")
            .forEach { assertTrue(it, AppConfigResolver.objcBoolValue(it)) }
    }

    @Test fun `boolValue reads everything else as false`() {
        listOf("", "0", "00", "false", "FALSE", "no", "NO", "off", "0.5", "-", "+", "x")
            .forEach { assertFalse(it, AppConfigResolver.objcBoolValue(it)) }
    }

    /** Leading whitespace, then an optional sign, then any run of zeroes. */
    @Test fun `boolValue skips whitespace then a sign then leading zeroes`() {
        assertTrue(AppConfigResolver.objcBoolValue(" 1"))
        assertTrue(AppConfigResolver.objcBoolValue("\t1"))
        assertTrue(AppConfigResolver.objcBoolValue("   007"))
        assertTrue(AppConfigResolver.objcBoolValue("-1"))
        assertTrue(AppConfigResolver.objcBoolValue("+1"))
        assertTrue(AppConfigResolver.objcBoolValue(" -007"))
        // The sign and the zeroes are skipped before the letter check too.
        assertTrue(AppConfigResolver.objcBoolValue("-yes"))
        assertTrue(AppConfigResolver.objcBoolValue("0true"))
    }

    /**
     * Foundation's whitespace set is not Kotlin's `Char.isWhitespace()`.
     *
     * The two AGREE on the non-breaking space: Kotlin's predicate ORs in
     * `Character.isSpaceChar`, so U+00A0 counts on both sides. (Java's bare
     * `Character.isWhitespace` would say no — that is the trap, and Kotlin
     * avoids it.)
     *
     * They DISAGREE on newlines: Kotlin counts them, Foundation's
     * `CharacterSet.whitespaces` does not. That single difference is why this
     * spells the set out rather than using the convenient predicate — a
     * value arriving with a trailing newline, which is exactly what reading a
     * config file or an env var tends to produce, would parse differently.
     */
    @Test fun `boolValue uses Foundation's whitespace set, not Kotlin's`() {
        // Space separator: both sets agree, and Foundation skips it.
        assertTrue('\u00A0'.isWhitespace())
        assertTrue(AppConfigResolver.objcBoolValue("\u00A01"))

        // The real divergence: Kotlin counts newlines, Foundation does not.
        assertTrue('\n'.isWhitespace())
        assertFalse(AppConfigResolver.objcBoolValue("\n1"))
        assertFalse(AppConfigResolver.objcBoolValue("\r1"))
    }

    @Test fun `an environment variable that is present but not affirmative is false`() {
        val c = AppConfigResolver.resolve(
            isDebug = true,
            ConfigOverrides.NONE,
            env("NEMARA_ENABLE_CLIP_CAPTURE" to "no"),
        )
        assertFalse(c.enableClipCapture)
    }

    // ------------------------------------------------ the per-developer file

    /** The normal case: a fresh clone and CI have no local file at all. */
    @Test fun `a missing local config file means no overrides`() {
        assertSame(
            ConfigOverrides.NONE,
            LocalConfigLoader.load("com.memoria.caregiver.data.NoSuchLocalConfig"),
        )
    }

    @Test fun `a local config object supplies every field`() {
        val overrides = LocalConfigLoader.load(FakeFullLocalConfig::class.java.name)

        assertEquals("http://192.168.1.42:54341", overrides.supabaseUrl)
        assertEquals("sb_publishable_fake", overrides.supabaseAnonKey)
        assertEquals("http://192.168.1.42:8002", overrides.backendUrl)
        assertEquals(true, overrides.enableClipCapture)
    }

    /**
     * A developer whose local copy predates a newly added field still gets a
     * working app: the missing field reads as "no override", not as a failure.
     */
    @Test fun `a local config missing newer fields still supplies the ones it has`() {
        val overrides = LocalConfigLoader.load(FakeSparseLocalConfig::class.java.name)

        assertEquals("http://192.168.1.42:8002", overrides.backendUrl)
        assertNull(overrides.supabaseUrl)
        assertNull(overrides.supabaseAnonKey)
        assertNull(overrides.enableClipCapture)
    }

    /** Written as a `class` rather than an `object`, so there is no singleton. */
    @Test fun `a local config that is not an object means no overrides`() {
        assertSame(
            ConfigOverrides.NONE,
            LocalConfigLoader.load(FakeNotAnObjectLocalConfig::class.java.name),
        )
    }

    @Test fun `the loader looks for the file beside AppConfig`() {
        assertEquals(
            "com.memoria.caregiver.data.LocalAppConfig",
            LocalConfigLoader.LOCAL_CONFIG_CLASS,
        )
    }

    // ------------------------------------------------------- the object itself

    @Test fun `the password reset redirect is the app's deep link`() {
        assertEquals("nemara://reset-password", AppConfig.supabasePasswordResetRedirect)
    }

    @Test fun `supabase counts as configured only when both values are present`() {
        assertTrue(configured(url = "http://10.0.2.2:54341", key = "sb_publishable_x"))
        assertFalse(configured(url = null, key = "sb_publishable_x"))
        assertFalse(configured(url = "http://10.0.2.2:54341", key = null))
        assertFalse(configured(url = null, key = null))
    }

    private fun configured(url: String?, key: String?): Boolean =
        ResolvedConfig(
            supabaseUrl = url,
            supabaseAnonKey = key,
            backendUrl = "http://10.0.2.2:8002",
            enableClipCapture = false,
        ).isSupabaseConfigured
}

/** Stands in for a developer's `LocalAppConfig.kt`, with every field set. */
object FakeFullLocalConfig {
    val supabaseUrl: String? = "http://192.168.1.42:54341"
    val supabaseAnonKey: String? = "sb_publishable_fake"
    val backendUrl: String? = "http://192.168.1.42:8002"
    val enableClipCapture: Boolean? = true
}

/** An older local copy that predates three of the four fields. */
object FakeSparseLocalConfig {
    val backendUrl: String? = "http://192.168.1.42:8002"
}

/** A local copy written as a class, so it has no INSTANCE to read. */
class FakeNotAnObjectLocalConfig {
    val backendUrl: String? = "http://192.168.1.42:8002"
}
