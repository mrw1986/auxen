package io.github.auxen.playback.cache

import android.app.Application
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

private val OFFLINE = NetworkState(online = false, unmetered = false)
private val MOBILE = NetworkState(online = true, unmetered = false)
private val WIFI = NetworkState(online = true, unmetered = true)

private fun caps(vararg capabilities: Int): NetworkCapabilities =
    ShadowNetworkCapabilities.newInstance().also { caps ->
        capabilities.forEach { shadowOf(caps).addCapability(it) }
    }

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NetworkMonitorTest {

    @Test
    fun `mayCache is false when offline in every mode`() {
        // Unmetered but not validated, e.g. a captive portal: still no caching.
        val captive = NetworkState(online = false, unmetered = true)
        CacheNetworkMode.entries.forEach { mode ->
            assertFalse("$mode offline", mayCache(mode, OFFLINE))
            assertFalse("$mode captive", mayCache(mode, captive))
        }
    }

    @Test
    fun `Wi-Fi and mobile caches on any validated network`() {
        assertTrue(mayCache(CacheNetworkMode.WIFI_AND_MOBILE, MOBILE))
        assertTrue(mayCache(CacheNetworkMode.WIFI_AND_MOBILE, WIFI))
    }

    @Test
    fun `Wi-Fi only caches only when unmetered`() {
        assertFalse(mayCache(CacheNetworkMode.WIFI_ONLY, MOBILE))
        assertTrue(mayCache(CacheNetworkMode.WIFI_ONLY, WIFI))
    }

    @Test
    fun `Off never caches`() {
        assertFalse(mayCache(CacheNetworkMode.OFF, MOBILE))
        assertFalse(mayCache(CacheNetworkMode.OFF, WIFI))
    }

    @Test
    fun `networkStateOf null is offline`() {
        assertEquals(OFFLINE, networkStateOf(null))
    }

    @Test
    fun `networkStateOf needs VALIDATED to be online`() {
        assertFalse(networkStateOf(caps(NetworkCapabilities.NET_CAPABILITY_INTERNET)).online)
        assertFalse(networkStateOf(caps(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).online)
        assertTrue(
            networkStateOf(
                caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            ).online,
        )
    }

    @Test
    fun `networkStateOf reads NOT_METERED as unmetered`() {
        val online = intArrayOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertEquals(MOBILE, networkStateOf(caps(*online)))
        assertEquals(WIFI, networkStateOf(caps(*online, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))
    }
}
