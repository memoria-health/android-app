package com.memoria.caregiver

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.memoria.caregiver.data.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The front door of password recovery: the manifest filter that makes
 * `nemara://reset-password?code=…` reach this app at all, and the parsing that
 * turns it into a code.
 *
 * This is the regression test for the first half of FINDINGS.md F-26. Every
 * other piece of the reset flow was already built and green while recovery was
 * impossible, because nothing was registered for the scheme — so the assertion
 * that matters is not about a screen, it is that the OS can resolve the link to
 * this Activity. A test that started from the code would have passed throughout.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryLinkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Exactly the URL Supabase is told to send, built the way the app builds it. */
    private fun recoveryLink(code: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("${AppConfig.supabasePasswordResetRedirect}?code=$code"))

    // -----------------------------------------------------------------------
    // The manifest
    // -----------------------------------------------------------------------

    /**
     * The address in [AppConfig] and the address in the manifest have to be the
     * same one. They are written in different languages in different files, so
     * nothing but a test connects them: change the redirect and the email keeps
     * sending, the tap keeps resolving to nothing, and every other test stays
     * green.
     */
    @Test
    fun `the redirect Supabase is given resolves to this app`() {
        val matches = context.packageManager
            .queryIntentActivities(recoveryLink("abc123"), PackageManager.MATCH_DEFAULT_ONLY)

        assertEquals(
            "the recovery link must resolve to exactly one activity",
            1,
            matches.size,
        )
        assertEquals(
            MainActivity::class.java.name,
            matches.single().activityInfo.name,
        )
    }

    /**
     * The link is tapped in Gmail, not launched by another app. A browser or an
     * email client adds `BROWSABLE` to the intent it fires, and a filter that
     * does not declare that category refuses it — so without this the manifest
     * could resolve the link in every test above and still be unreachable from
     * the only place a caregiver will ever tap it.
     */
    @Test
    fun `a link tapped in a browser or an email client resolves to this app`() {
        val fromEmail = recoveryLink("abc123").addCategory(Intent.CATEGORY_BROWSABLE)

        val matches = context.packageManager
            .queryIntentActivities(fromEmail, PackageManager.MATCH_DEFAULT_ONLY)

        assertEquals(1, matches.size)
        assertEquals(MainActivity::class.java.name, matches.single().activityInfo.name)
    }

    /** The scheme and host iOS registers (`project.yml` CFBundleURLSchemes: nemara). */
    @Test
    fun `the redirect is the nemara reset-password scheme`() {
        val uri = Uri.parse(AppConfig.supabasePasswordResetRedirect)

        assertEquals("nemara", uri.scheme)
        assertEquals("reset-password", uri.host)
    }

    /**
     * A link that is not ours must not open us. If the filter were declared with
     * only a scheme, every future `nemara://` host — pairing, auth callback —
     * would land on the password screen the day it was added.
     */
    @Test
    fun `a different nemara host does not resolve to this app`() {
        val other = Intent(Intent.ACTION_VIEW, Uri.parse("nemara://auth-callback?code=abc123"))

        assertTrue(
            "only reset-password is claimed",
            context.packageManager.queryIntentActivities(other, PackageManager.MATCH_DEFAULT_ONLY)
                .isEmpty(),
        )
    }

    /**
     * `singleTop` is load-bearing, not tidiness. Under the default launch mode a
     * link tapped while the app is running starts a SECOND MainActivity instead
     * of delivering to the first, so `onNewIntent` never runs and the warm path
     * is dead — with every unit test still passing, because none of them starts
     * an activity twice.
     */
    @Test
    fun `the activity is singleTop, so a link reaches the running instance`() {
        val info: ActivityInfo = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            0,
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    // -----------------------------------------------------------------------
    // The parsing
    // -----------------------------------------------------------------------

    @Test
    fun `the code is read off the recovery link`() {
        assertEquals("abc123", recoveryCodeFrom(recoveryLink("abc123")))
    }

    /** The launcher intent carries no data at all and must not look like a link. */
    @Test
    fun `the launcher intent carries no recovery code`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        assertNull(recoveryCodeFrom(launcher))
        assertNull(recoveryCodeFrom(null))
    }

    /** iOS: `guard url.scheme == "nemara"`, then `switch host`. Both checks, both directions. */
    @Test
    fun `another scheme or another host is not a recovery link`() {
        assertNull(
            recoveryCodeFrom(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://reset-password/?code=abc123")),
            ),
        )
        assertNull(
            recoveryCodeFrom(
                Intent(Intent.ACTION_VIEW, Uri.parse("nemara://auth-callback?code=abc123")),
            ),
        )
    }

    /**
     * iOS drops the open when there is no `code` item — there is nothing to
     * exchange, and the screen it would show could only say so.
     */
    @Test
    fun `a link with no code at all is dropped`() {
        assertNull(
            recoveryCodeFrom(Intent(Intent.ACTION_VIEW, Uri.parse("nemara://reset-password"))),
        )
        assertNull(
            recoveryCodeFrom(
                Intent(Intent.ACTION_VIEW, Uri.parse("nemara://reset-password?token=abc123")),
            ),
        )
    }

    /**
     * An empty `?code=` is a code, as far as this is concerned — iOS takes the
     * query item's value whatever it is. The exchange then fails and the screen
     * says the link is invalid, which is a truer answer to a truncated link than
     * a tap that does nothing.
     */
    @Test
    fun `an empty code still opens the screen, which then reports the link invalid`() {
        assertEquals("", recoveryCodeFrom(recoveryLink("")))
        assertNotNull(recoveryCodeFrom(recoveryLink("")))
    }
}
