package io.github.auxen.playback.cache

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The default network as the cache sees it. [unmetered] stands in for "on Wi-Fi". */
data class NetworkState(val online: Boolean, val unmetered: Boolean)

private val OFFLINE = NetworkState(online = false, unmetered = false)

/** Whether [mode] lets the cache download on [net] right now. */
fun mayCache(mode: CacheNetworkMode, net: NetworkState): Boolean = net.online && when (mode) {
    CacheNetworkMode.WIFI_AND_MOBILE -> true
    CacheNetworkMode.WIFI_ONLY -> net.unmetered
    CacheNetworkMode.OFF -> false
}

/** VALIDATED as well as INTERNET, so a captive portal doesn't count as online. */
fun networkStateOf(caps: NetworkCapabilities?): NetworkState {
    if (caps == null) return OFFLINE
    return NetworkState(
        online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}

/**
 * Tracks the default network. App-lifetime singleton, so the callback is never unregistered.
 * Needs ACCESS_NETWORK_STATE.
 */
class ConnectivityNetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(networkStateOf(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _state.value = networkStateOf(caps)
            }

            override fun onLost(network: Network) {
                _state.value = OFFLINE
            }
        })
    }
}
