package io.github.auxen.playback.cache

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: CacheSettingsStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(produceFile = { tmp.newFile("cache.preferences_pb") })
        store = CacheSettingsStore(dataStore)
    }

    @Test
    fun `defaults are Wi-Fi and mobile and 2 GB`() = runBlocking {
        val settings = store.settings.first()
        assertEquals(CacheNetworkMode.WIFI_AND_MOBILE, settings.mode)
        assertEquals(2_000_000_000L, settings.limitBytes)
    }

    @Test
    fun `mode and limit persist`() = runBlocking {
        store.setMode(CacheNetworkMode.WIFI_ONLY)
        store.setLimit(5_000_000_000L)

        // A second store over the same DataStore reads what the first one wrote.
        val reread = CacheSettingsStore(dataStore).settings.first()
        assertEquals(CacheSettings(CacheNetworkMode.WIFI_ONLY, 5_000_000_000L), reread)
    }

    @Test
    fun `setLimit rejects a value that is not a preset`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.setLimit(3_000_000_000L) }
        }
        assertEquals(DEFAULT_CACHE_LIMIT_BYTES, store.settings.first().limitBytes)
    }

    @Test
    fun `an unknown stored mode falls back to the default`() = runBlocking {
        dataStore.edit { it[CacheSettingsStore.KEY_MODE] = "SATELLITE" }
        assertEquals(CacheNetworkMode.WIFI_AND_MOBILE, store.settings.first().mode)
    }

    @Test
    fun `an unknown stored limit falls back to the default`() = runBlocking {
        dataStore.edit { it[CacheSettingsStore.KEY_LIMIT] = 3_000_000_000L }
        assertEquals(DEFAULT_CACHE_LIMIT_BYTES, store.settings.first().limitBytes)
    }

    @Test
    fun `readout formats GB with one decimal`() {
        assertEquals("1.2 GB used · 14 songs", formatCacheReadout(1_200_000_000L, 14))
    }

    @Test
    fun `readout formats MB below 1 GB`() {
        assertEquals("340 MB used · 1 song", formatCacheReadout(340_000_000L, 1))
    }

    @Test
    fun `readout rounds MB down`() {
        assertEquals("999 MB used · 2 songs", formatCacheReadout(999_999_999L, 2))
    }

    @Test
    fun `readout for an empty cache`() {
        assertEquals("0 MB used · 0 songs", formatCacheReadout(0L, 0))
    }

    @Test
    fun `limit labels`() {
        assertEquals("500 MB", formatLimitLabel(500_000_000L))
        assertEquals("1 GB", formatLimitLabel(1_000_000_000L))
        assertEquals("2 GB", formatLimitLabel(2_000_000_000L))
        assertEquals("5 GB", formatLimitLabel(5_000_000_000L))
        assertEquals("10 GB", formatLimitLabel(10_000_000_000L))
        assertEquals("1.5 GB", formatLimitLabel(1_500_000_000L))
    }
}
