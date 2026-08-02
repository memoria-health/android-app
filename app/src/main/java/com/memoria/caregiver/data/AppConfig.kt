package com.memoria.caregiver.data

import com.memoria.caregiver.BuildConfig
import java.net.URI
import java.net.URISyntaxException

/**
 * `AppConfig` — the single static configuration the app reads at launch.
 *
 * Port of `Networking/AppConfig.swift`. Spec: `PARITY.md §4`.
 *
 * Resolution order for every field:
 *  1. `LocalAppConfig.<field>` — per-developer override. The right place to
 *     point YOUR debug build at YOUR stack (your Mac's local Supabase, your
 *     ngrok URL). It wins because it travels with the binary, so the app
 *     behaves the same whether launched from the IDE or from the launcher.
 *  2. `System.getenv(<KEY>)` — runtime environment variable, useful for CI or
 *     a one-off invocation. Android almost never populates these, but the step
 *     is kept so the order matches iOS.
 *  3. Debug fallback — the canonical dev stack, so a fresh clone runs with no
 *     per-developer setup.
 *  4. Release constant — production Supabase and the Railway backend. What
 *     real users hit.
 *
 * **Release builds never consult steps 1 or 2.** That is deliberate, not
 * defensive: the per-developer file is gitignored and lives only on the
 * machine that cuts the build, so a release build that honoured it would ship
 * pointing at someone's home network.
 *
 * Two differences from iOS, both already decided:
 *  - The per-developer file is gitignored from creation with a committed
 *    `.template` beside it, instead of iOS's commit-then-skip-worktree
 *    (`DIFFERENCES.md D3`).
 *  - Debug hosts are IP-based, because Android does not resolve Bonjour
 *    `.local` names without `NsdManager` (`DIFFERENCES.md D4`).
 */
object AppConfig {

    private val resolved: ResolvedConfig = AppConfigResolver.resolve(
        isDebug = BuildConfig.DEBUG,
        // Release does not even load the per-developer file. The resolver
        // ignores overrides in release regardless; this is the second lock on
        // the same door, and it also keeps release off the reflection path.
        overrides = if (BuildConfig.DEBUG) LocalConfigLoader.load() else ConfigOverrides.NONE,
        env = { name -> System.getenv(name) },
    )

    /**
     * Supabase project URL. Override with `LocalAppConfig.supabaseUrl` or the
     * `SUPABASE_URL` environment variable. Release always uses prod Supabase.
     */
    val supabaseUrl: String? get() = resolved.supabaseUrl

    /**
     * Supabase anon (publishable) key. Override with
     * `LocalAppConfig.supabaseAnonKey` or the `SUPABASE_ANON_KEY` environment
     * variable. Publishable keys are designed to ship inside client apps, so
     * embedding this one is safe.
     */
    val supabaseAnonKey: String? get() = resolved.supabaseAnonKey

    /**
     * Where Supabase sends users after they tap a password-reset email. Must
     * be on the project's redirect allow-list.
     */
    val supabasePasswordResetRedirect: String = AppConfigResolver.PASSWORD_RESET_REDIRECT

    /**
     * nemara-backend API base URL. Override with `LocalAppConfig.backendUrl`
     * or the `NEMARA_BACKEND_URL` environment variable. Release always uses
     * the Railway production deployment.
     */
    val backendUrl: String get() = resolved.backendUrl

    /**
     * True when both Supabase values are present. The auth service refuses to
     * build its client without them, so a missing value produces a clear error
     * instead of a crash later.
     */
    val isSupabaseConfigured: Boolean get() = resolved.isSupabaseConfigured

    /**
     * Dev-only flag for the data-collection "Capture 15s clip" button on the
     * live camera tile. Not a product feature: it tells the wall camera to
     * record a short clip and ship it to a laptop receiver for detector test
     * data. Off by default so it never shows up in a normal build.
     *
     * Release hard-compiles this to false. It is the single choke point for
     * the whole clip chain — button, then backend, then the camera's record
     * trigger — so false here means the camera is never told to record in
     * production, no matter what a developer left in their local file.
     */
    val enableClipCapture: Boolean get() = resolved.enableClipCapture
}

/** The four values `AppConfig` exposes, after resolution. */
internal data class ResolvedConfig(
    val supabaseUrl: String?,
    val supabaseAnonKey: String?,
    val backendUrl: String,
    val enableClipCapture: Boolean,
) {
    /** Both Supabase values present. See [AppConfig.isSupabaseConfigured]. */
    val isSupabaseConfigured: Boolean get() = supabaseUrl != null && supabaseAnonKey != null
}

/**
 * Whatever the per-developer file supplied. Every field null means "no
 * override", which is also what a fresh clone with no local file produces.
 */
internal data class ConfigOverrides(
    val supabaseUrl: String? = null,
    val supabaseAnonKey: String? = null,
    val backendUrl: String? = null,
    val enableClipCapture: Boolean? = null,
) {
    companion object {
        /** No overrides at all. */
        val NONE = ConfigOverrides()
    }
}

/**
 * Reads the per-developer `LocalAppConfig` object by name, if it exists.
 *
 * By name, because the file is gitignored: a fresh clone does not have it, and
 * a direct reference would stop the project compiling until every developer
 * copied the template. iOS avoids this by committing the file with all-null
 * values, which is exactly the practice `DIFFERENCES.md D3` moved away from
 * after a real home-network address reached the iOS repo.
 *
 * Missing file, or a field the local copy predates, means "no override" rather
 * than a failure. A developer whose local file is older than the template
 * should still get a working app.
 */
internal object LocalConfigLoader {

    /** Where the per-developer file lives when a developer has created one. */
    const val LOCAL_CONFIG_CLASS = "com.memoria.caregiver.data.LocalAppConfig"

    fun load(className: String = LOCAL_CONFIG_CLASS): ConfigOverrides {
        val instance = instanceOf(className) ?: return ConfigOverrides.NONE
        val type = instance.javaClass
        return ConfigOverrides(
            supabaseUrl = read(type, instance, "getSupabaseUrl") as? String,
            supabaseAnonKey = read(type, instance, "getSupabaseAnonKey") as? String,
            backendUrl = read(type, instance, "getBackendUrl") as? String,
            enableClipCapture = read(type, instance, "getEnableClipCapture") as? Boolean,
        )
    }

    /** The singleton a Kotlin `object` declaration exposes as its INSTANCE field. */
    private fun instanceOf(className: String): Any? =
        try {
            Class.forName(className).getField("INSTANCE").get(null)
        } catch (_: ClassNotFoundException) {
            // No local file. The normal case on a fresh clone and in CI.
            null
        } catch (_: NoSuchFieldException) {
            // Someone wrote `class LocalAppConfig` instead of `object`.
            null
        }

    private fun read(type: Class<*>, instance: Any, getter: String): Any? =
        try {
            type.getMethod(getter).invoke(instance)
        } catch (_: NoSuchMethodException) {
            // The local copy predates this field.
            null
        }
}

/**
 * The resolution itself, with the build type passed in rather than read from
 * `BuildConfig`.
 *
 * That is what makes the rule testable: "release ignores an override that
 * debug honours" is the single most important thing about this file, and a
 * test run is always a debug build, so a release resolution can only be
 * exercised if the build type is an argument.
 */
internal object AppConfigResolver {

    // Debug hosts are IP-based (DIFFERENCES.md D4). 10.0.2.2 is the address
    // the emulator gives the guest for the host machine's loopback, which is
    // the closest equivalent to iOS's "the canonical dev Mac" and is not any
    // real person's LAN address. A developer on a physical device sets their
    // own IP in LocalAppConfig.kt.
    const val DEBUG_SUPABASE_URL = "http://10.0.2.2:54341"
    const val DEBUG_BACKEND_URL = "http://10.0.2.2:8002"

    // Local Supabase publishable key. Deterministic per supabase-cli version;
    // refresh with `supabase status -o env | grep PUBLISHABLE_KEY` if a CLI
    // upgrade ever changes it.
    const val DEBUG_SUPABASE_ANON_KEY = "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH"

    const val RELEASE_SUPABASE_URL = "https://zgifmvjnggmgzvtnosnu.supabase.co"
    const val RELEASE_SUPABASE_ANON_KEY = "sb_publishable_OsfCMOdEIb5gvhRWDyNivg_7GriAmyP"
    const val RELEASE_BACKEND_URL = "https://nemara-backend-production.up.railway.app"

    const val PASSWORD_RESET_REDIRECT = "nemara://reset-password"

    const val ENV_SUPABASE_URL = "SUPABASE_URL"
    const val ENV_SUPABASE_ANON_KEY = "SUPABASE_ANON_KEY"
    const val ENV_BACKEND_URL = "NEMARA_BACKEND_URL"
    const val ENV_ENABLE_CLIP_CAPTURE = "NEMARA_ENABLE_CLIP_CAPTURE"

    fun resolve(
        isDebug: Boolean,
        overrides: ConfigOverrides,
        env: (String) -> String?,
    ): ResolvedConfig =
        if (isDebug) {
            ResolvedConfig(
                supabaseUrl = url(overrides.supabaseUrl)
                    ?: url(env(ENV_SUPABASE_URL))
                    ?: DEBUG_SUPABASE_URL,
                supabaseAnonKey = overrides.supabaseAnonKey?.takeIf { it.isNotEmpty() }
                    ?: env(ENV_SUPABASE_ANON_KEY)?.takeIf { it.isNotEmpty() }
                    ?: DEBUG_SUPABASE_ANON_KEY,
                backendUrl = url(overrides.backendUrl)
                    ?: url(env(ENV_BACKEND_URL))
                    ?: DEBUG_BACKEND_URL,
                enableClipCapture = overrides.enableClipCapture
                    ?: env(ENV_ENABLE_CLIP_CAPTURE)?.let { objcBoolValue(it) }
                    ?: false,
            )
        } else {
            // Release, archive, Play: always production. The override file and
            // the environment are both ignored, and clip capture is off no
            // matter what either of them says.
            ResolvedConfig(
                supabaseUrl = RELEASE_SUPABASE_URL,
                supabaseAnonKey = RELEASE_SUPABASE_ANON_KEY,
                backendUrl = RELEASE_BACKEND_URL,
                enableClipCapture = false,
            )
        }

    /**
     * Accepts a configured URL string, or null if it is unusable.
     *
     * iOS builds every configured URL through `URL(string:)`, which returns
     * nil for an empty or unparseable string, and nil then falls through to
     * the next step of the resolution order. This keeps that behaviour: an
     * environment variable set to the empty string falls through to the debug
     * fallback rather than producing a broken base URL.
     *
     * It deliberately does not require a scheme or a host. `URL(string:)` does
     * not either, and adding a stricter rule than iOS has would be a behaviour
     * change dressed up as validation.
     */
    fun url(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        return try {
            URI(raw)
            raw
        } catch (_: URISyntaxException) {
            null
        }
    }

    /**
     * Port of Foundation's `NSString.boolValue`, which is what iOS uses to read
     * the clip-capture environment variable.
     *
     * The rule: skip leading whitespace, then an optional single `+` or `-`,
     * then any run of `0`, and return true if what remains starts with one of
     * `t T y Y 1 2 3 4 5 6 7 8 9`. So "YES", "true", "1", " 007" and "-1" are
     * all true; "0", "no", "" and "0.5" are all false.
     *
     * Whitespace here means Foundation's `CharacterSet.whitespaces`: the space
     * character, the tab, and Unicode's space-separator category. It does not
     * include newlines. Kotlin's `Char.isWhitespace()` is the wrong set in both
     * directions — it counts newlines, which Foundation does not, and it skips
     * the non-breaking space, which Foundation counts — so this spells the set
     * out instead of using it.
     */
    fun objcBoolValue(raw: String): Boolean {
        var i = 0
        while (i < raw.length && isFoundationWhitespace(raw[i])) i++
        if (i < raw.length && (raw[i] == '+' || raw[i] == '-')) i++
        while (i < raw.length && raw[i] == '0') i++
        if (i >= raw.length) return false
        return when (raw[i]) {
            't', 'T', 'y', 'Y' -> true
            else -> raw[i] in '1'..'9'
        }
    }

    private fun isFoundationWhitespace(c: Char): Boolean =
        c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
}
