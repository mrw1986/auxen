package io.github.auxen.provider.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A folder on the device that contains music, plus how many tracks it holds —
 * one checklist row in the "Music folders" setting. [path] is the folder's
 * stable identity (the parent directory of a track's MediaStore DATA column);
 * [displayName] is the friendly tail shown to the user.
 */
data class MusicFolder(val path: String, val displayName: String, val trackCount: Int)

/**
 * The folder identity for a MediaStore DATA path — its parent directory. E.g.
 * `/storage/emulated/0/Music/FLAC/song.flac` -> `/storage/emulated/0/Music/FLAC`.
 * The full path is the identity (two same-named folders on different volumes
 * never collide). A parentless or blank path yields "".
 */
fun folderPathOf(dataPath: String): String {
    val trimmed = dataPath.trim()
    val slash = trimmed.lastIndexOf('/')
    return if (slash < 0) "" else trimmed.substring(0, slash)
}

/**
 * A friendly label for a folder identity [path]: the primary external-storage
 * prefix (`/storage/emulated/0`) is stripped so the user sees `Music/FLAC`
 * rather than the full absolute path. The internal-storage root itself shows
 * as "Internal storage"; a non-primary volume (SD card) has no prefix to strip
 * and is shown fuller.
 */
fun folderDisplayName(path: String): String {
    val stripped = path.removePrefix(PRIMARY_VOLUME_PREFIX).trim('/')
    return stripped.ifBlank { "Internal storage" }
}

/**
 * Whether a track in [folder] is included given the user's [included] set. An
 * EMPTY set means "all folders" — the default, byte-identical to today's
 * behaviour (no filtering). This is the pure core the MediaStore read path
 * applies per row.
 */
fun isFolderIncluded(included: Set<String>, folder: String): Boolean =
    included.isEmpty() || folder in included

/**
 * Group a flat list of MediaStore DATA [dataPaths] into the distinct music
 * [MusicFolder]s that contain them, with per-folder track counts, sorted by
 * display name (case-insensitive). Blank/parentless paths are dropped. Pure —
 * the live MediaStore query that produces [dataPaths] is the untested feeder.
 */
fun foldersFrom(dataPaths: List<String>): List<MusicFolder> =
    dataPaths.asSequence()
        .map(::folderPathOf)
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .map { (path, count) -> MusicFolder(path, folderDisplayName(path), count) }
        .sortedBy { it.displayName.lowercase() }

/** Serialize a folder-identity set for the settings table (a JSON string array). */
fun encodeFolders(folders: Set<String>): String =
    FOLDERS_JSON.encodeToString(ListSerializer(String.serializer()), folders.toList())

/**
 * Decode the persisted folder selection. Null/blank/garbage -> empty set (=
 * "all folders", the safe default that never accidentally hides the library).
 */
fun decodeFolders(value: String?): Set<String> {
    if (value.isNullOrBlank()) return emptySet()
    return runCatching {
        FOLDERS_JSON.decodeFromString(ListSerializer(String.serializer()), value).toSet()
    }.getOrDefault(emptySet())
}

private const val PRIMARY_VOLUME_PREFIX = "/storage/emulated/0"
private val FOLDERS_JSON = Json { ignoreUnknownKeys = true }
