package com.memoria.caregiver.data.realtime

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The `UserDefaults` half of ever-online persistence.
 *
 * Small, but not pointless: `SharedPreferences.getStringSet` documents that the
 * set it returns must not be modified, and `putStringSet` keeps a reference to
 * whatever it is handed. Getting either wrong corrupts the stored set in a way
 * that only shows up as a camera regressing to "preparing" after a relaunch.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPrefsEverOnlineStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: SharedPrefsEverOnlineStore

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("realtime-test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SharedPrefsEverOnlineStore(prefs)
    }

    @Test
    fun `an empty store loads as empty rather than throwing`() {
        assertEquals(emptySet<String>(), store.load())
    }

    @Test
    fun `ids round-trip`() {
        store.save(setOf("c1", "c2"))

        assertEquals(setOf("c1", "c2"), store.load())
    }

    @Test
    fun `mutating the set that was saved does not change what was stored`() {
        val live = mutableSetOf("c1")
        store.save(live)

        live += "c2"

        assertEquals(setOf("c1"), store.load())
    }
}
