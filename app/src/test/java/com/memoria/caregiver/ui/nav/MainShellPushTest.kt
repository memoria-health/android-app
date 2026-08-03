package com.memoria.caregiver.ui.nav

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import com.memoria.caregiver.ui.home.RosterViewModel
import com.memoria.caregiver.ui.settings.AppSettingsViewModel
import com.memoria.caregiver.ui.theme.MemoriaTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two things the signed-in surface owes the push stack.
 *
 * Both were missing, and both are the kind of gap that leaves every other test
 * green: `DeepLinkRouter` was fully built and tested with **zero production
 * callers**, and nothing anywhere asked for the notification permission. A test
 * of the router, or of the permission gate, passes in exactly that state. The
 * assertion has to be that this surface calls them.
 *
 * `MainTabView.onAppear` is the iOS counterpart for the drain. There is no iOS
 * counterpart for the permission — see the comment in [MainShell] for why it is
 * asked here rather than at launch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainShellPushTest {

    @get:Rule val compose = createComposeRule()

    private val patient = "9a2c7d40-1f3e-4b55-8a6d-0c5e7b21d904"
    private val event = "3f1b8b1e-2b7c-4c2e-9c1a-6f0f2d4a8b31"

    @Before fun installMain() = kotlinx.coroutines.Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun restoreMain() = Dispatchers.resetMain()

    /** The real client over a backend that returns an empty roster. */
    private fun api() = NemaraApiClient(
        HttpTransport(
            client = HttpClient(
                MockEngine { request ->
                    val json = headersOf(HttpHeaders.ContentType, "application/json")
                    if (request.url.encodedPath.endsWith("/events")) {
                        respond("""{"items":[]}""", headers = json)
                    } else {
                        respond("[]", headers = json)
                    }
                },
            ) {
                install(ContentNegotiation) { json(NemaraJson.json) }
            },
            baseUrl = "https://backend.test",
            tokenProvider = { "test-jwt" },
        ),
    )

    private fun setUp(
        deepLink: DeepLinkRouter,
        onAsk: () -> Unit = {},
    ) {
        val api = api()
        val caregiver = MutableStateFlow<Caregiver?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    MainShell(
                        rosterViewModel = RosterViewModel(api, caregiver),
                        settingsViewModel = AppSettingsViewModel(
                            api = api,
                            caregiver = caregiver,
                            onSignOut = {},
                            onDeleteAccount = {},
                        ),
                        deepLink = deepLink,
                        patientContent = { androidx.compose.material3.Text("Patient surface") },
                        onAddPatient = {},
                        onEditProfile = {},
                        onPrivacy = {},
                        onContactSupport = {},
                        onAskForNotifications = onAsk,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // -----------------------------------------------------------------------
    // The drain
    // -----------------------------------------------------------------------

    @Test
    fun `a tap recorded before sign-in reaches the patient and the event`() {
        // The shape of the real failure: the push lands, the caregiver is not
        // signed in yet, and by the time this surface exists the tap is old.
        val router = DeepLinkRouter()
        router.bufferEventTap(eventId = event, patientId = patient)

        setUp(router)

        // The shell opens the patient the tap named, and the event id is still
        // set for the events screen to consume. Before this task nothing in the
        // app ever called bufferEventTap, so this whole path was unreachable —
        // and every test of the router passed throughout.
        assertEquals(patient, router.state.value.openPatientId)
        assertEquals(event, router.state.value.focusEventId)
        compose.onNodeWithText("Patient surface").assertExists()
    }

    @Test
    fun `draining is take-and-clear, so appearing does not re-open a spent tap`() {
        val router = DeepLinkRouter()
        router.bufferEventTap(eventId = event, patientId = patient)
        // The caregiver read the event and came back to the roster.
        router.closePatient()

        setUp(router)

        // Once spent, a tap is spent. A drain that found something here would
        // drag the caregiver straight back into the event they just closed.
        assertEquals(null, router.state.value.openPatientId)
    }

    @Test
    fun `a surface with nothing pending routes nowhere`() {
        val router = DeepLinkRouter()

        setUp(router)

        assertEquals(null, router.state.value.openPatientId)
    }

    // -----------------------------------------------------------------------
    // The permission
    // -----------------------------------------------------------------------

    @Test
    fun `the notification permission is asked for on the signed-in surface`() {
        var asked = 0

        setUp(DeepLinkRouter(), onAsk = { asked++ })

        // Not on the welcome screen and not on sign-up. Android stops showing
        // the dialog after a second refusal, so there are exactly two chances
        // and they are spent on somebody who has just signed in and therefore
        // has something to be alerted about.
        assertEquals(1, asked)
    }

    @Test
    fun `the permission is asked once per visit, not on every recomposition`() {
        var asked = 0
        val router = DeepLinkRouter()

        setUp(router, onAsk = { asked++ })
        // Any state change that recomposes the shell.
        router.openPatient(patient)
        compose.waitForIdle()
        router.closePatient()
        compose.waitForIdle()

        assertEquals(1, asked)
    }
}
