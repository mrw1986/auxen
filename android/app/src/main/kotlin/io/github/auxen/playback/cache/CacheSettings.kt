package io.github.auxen.playback.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Which networks the track cache may download on. */
enum class CacheNetworkMode { WIFI_AND_MOBILE, WIFI_ONLY, OFF }

const val DEFAULT_CACHE_LIMIT_BYTES = 2_000_000_000L

/** The cache sizes Settings offers, in decimal bytes (500 MB to 10 GB). */
val CACHE_LIMIT_PRESETS: List<Long> =
    listOf(500_000_000L, 1_000_000_000L, 2_000_000_000L, 5_000_000_000L, 10_000_000_000L)

data class CacheSettings(
    val mode: CacheNetworkMode = CacheNetworkMode.WIFI_AND_MOBILE,
    val limitBytes: Long = DEFAULT_CACHE_LIMIT_BYTES,
)

// A corrupt file resets to defaults rather than failing every read.
internal val Context.trackCacheDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "track_cache",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Reads and writes [CacheSettings]. Stored values it doesn't recognize, and reads that fail
 * with an IOException, come back as the defaults.
 */
class CacheSettingsStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<CacheSettings> = dataStore.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { prefs ->
        val defaults = CacheSettings()
        CacheSettings(
            mode = CacheNetworkMode.entries.firstOrNull { it.name == prefs[KEY_MODE] } ?: defaults.mode,
            limitBytes = prefs[KEY_LIMIT]?.takeIf { it in CACHE_LIMIT_PRESETS } ?: defaults.limitBytes,
        )
    }

    suspend fun setMode(mode: CacheNetworkMode) {
        dataStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setLimit(bytes: Long) {
        require(bytes in CACHE_LIMIT_PRESETS) { "$bytes is not a cache limit preset" }
        dataStore.edit { it[KEY_LIMIT] = bytes }
    }

    companion object {
        internal val KEY_MODE = stringPreferencesKey("network_mode")
        internal val KEY_LIMIT = longPreferencesKey("limit_bytes")
    }
}

private const val BYTES_PER_GB = 1_000_000_000L
private const val BYTES_PER_MB = 1_000_000L

/** The Settings readout, e.g. "1.2 GB used · 14 songs". Decimal units, US decimal point. */
fun formatCacheReadout(totalBytes: Long, count: Int): String {
    val size = if (totalBytes >= BYTES_PER_GB) {
        String.format(Locale.US, "%.1f GB", totalBytes.toDouble() / BYTES_PER_GB)
    } else {
        "${totalBytes / BYTES_PER_MB} MB"
    }
    val songs = if (count == 1) "1 song" else "$count songs"
    return "$size used · $songs"
}

/** A cache limit as Settings shows it, e.g. "500 MB", "2 GB". */
fun formatLimitLabel(bytes: Long): String = when {
    bytes < BYTES_PER_GB -> "${bytes / BYTES_PER_MB} MB"
    bytes % BYTES_PER_GB == 0L -> "${bytes / BYTES_PER_GB} GB"
    else -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / BYTES_PER_GB)
}
