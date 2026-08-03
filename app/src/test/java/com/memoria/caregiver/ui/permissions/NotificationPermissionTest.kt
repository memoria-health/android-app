package com.memoria.caregiver.ui.permissions

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one thing about `POST_NOTIFICATIONS` that the other two permissions do not
 * have: it did not exist before Android 13.
 *
 * minSdk here is 26, so on roughly the older half of the phones this app runs on
 * there is no such permission — notifications are granted at install and turned
 * off in Settings.
 *
 * **What these tests do and do not prove.** They pin the contract: the gate
 * reports "already allowed" below 33 and "must be asked for" from 33 up. They do
 * NOT prove that the version branch in `rememberNotificationPermission` is what
 * produces the first answer — measured directly, `checkSelfPermission` already
 * returns granted at API 30 for a permission the platform does not define, so
 * deleting the branch leaves these tests green. That was confirmed by mutation
 * rather than assumed, and the branch is kept anyway: it is what stops
 * `launcher.launch()` being called with a permission name the platform has never
 * heard of, on any OEM build that answers the check the other way. Nothing in
 * this suite can tell those builds apart from the well-behaved ones.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationPermissionTest {

    @get:Rule val compose = createComposeRule()

    private fun grantedUnderTest(): Boolean {
        var granted = false
        compose.setContent { granted = rememberNotificationPermission().granted }
        compose.waitForIdle()
        return granted
    }

    @Test
    @Config(sdk = [33])
    fun `on Android 13 the permission has to be asked for`() {
        // Robolectric grants nothing by default, which is the state a fresh
        // install is in.
        assertFalse(grantedUnderTest())
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 13 notifications are already allowed`() {
        // Reporting "denied" here would gate alerts behind a prompt that can
        // never appear.
        assertTrue(grantedUnderTest())
    }

    @Test
    @Config(sdk = [26])
    fun `at the app's oldest supported version notifications are already allowed`() {
        assertTrue(grantedUnderTest())
    }
}
