package io.github.auxen.data

import io.github.auxen.model.Track

/**
 * In-memory album/artist grouping and sorting of the local library —
 * the Android analog of the desktop `Database.get_albums`/`get_artists`
 * queries, computed over the MediaStore-backed track list.
 */

data class AlbumGroup(
    val album: String,
    val albumArtist: String,
    val artUrl: String?,
    val year: Int?,
    val tracks: List<Track>,
)

data class ArtistGroup(
    val artist: String,
    val artUrl: String?,
    val tracks: List<Track>,
)

/**
 * A run of an album's tracks that share a disc. [discNumber] is `null` for a
 * single-disc album (or one with no disc metadata) — the album-detail screen
 * renders a "Disc N" header only when it is non-null, so a single-disc album
 * shows no headers, exactly as before disc grouping existed.
 */
data class DiscSection(
    val discNumber: Int?,
    val tracks: List<Track>,
)

/**
 * Splits an album's tracks into per-disc [DiscSection]s, but ONLY when the album
 * genuinely spans more than one disc (the set of distinct non-null [Track.discNumber]s
 * has size > 1). A single-disc album — including one with no disc metadata at all —
 * returns a single section with `discNumber = null` and the tracks passed through
 * untouched, so it renders identically to how it did before grouping.
 *
 * When multi-disc, tracks are ordered by `discNumber` then `trackNumber` (nulls
 * last in both), and any track missing a disc number lands in a trailing
 * `discNumber = null` section (which draws no header).
 */
fun groupTracksByDisc(tracks: List<Track>): List<DiscSection> {
    val multiDisc = tracks.mapNotNull { it.discNumber }.distinct().size > 1
    if (!multiDisc) return listOf(DiscSection(discNumber = null, tracks = tracks))
    return tracks
        .sortedWith(
            compareBy({ it.discNumber ?: Int.MAX_VALUE }, { it.trackNumber ?: Int.MAX_VALUE }),
        )
        .groupBy { it.discNumber }
        .map { (disc, discTracks) -> DiscSection(discNumber = disc, tracks = discTracks) }
}

enum class LibrarySort { RECENTLY_ADDED, NAME, ARTIST, TRACK_COUNT }

private const val UNKNOWN_ALBUM = "Unknown Album"
private const val UNKNOWN_ARTIST = "Unknown Artist"

fun groupAlbums(tracks: List<Track>): List<AlbumGroup> =
    tracks
        .groupBy { track ->
            val album = track.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM
            val artist = track.albumArtist?.takeIf { it.isNotBlank() }
                ?: track.artist.takeIf { it.isNotBlank() }
                ?: UNKNOWN_ARTIST
            album to artist
        }
        .map { (key, groupTracks) ->
            val sorted = groupTracks.sortedWith(
                compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }),
            )
            AlbumGroup(
                album = key.first,
                albumArtist = key.second,
                artUrl = sorted.firstNotNullOfOrNull { it.albumArtUrl },
                year = sorted.firstNotNullOfOrNull { it.year },
                tracks = sorted,
            )
        }

fun groupArtists(tracks: List<Track>): List<ArtistGroup> =
    tracks
        .groupBy { it.artist.takeIf { name -> name.isNotBlank() } ?: UNKNOWN_ARTIST }
        .map { (artist, groupTracks) ->
            ArtistGroup(
                artist = artist,
                artUrl = groupTracks.firstNotNullOfOrNull { it.albumArtUrl },
                tracks = groupTracks,
            )
        }

fun sortAlbums(albums: List<AlbumGroup>, sort: LibrarySort, ascending: Boolean): List<AlbumGroup> {
    val sorted = when (sort) {
        LibrarySort.NAME -> albums.sortedBy { it.album.lowercase() }
        LibrarySort.ARTIST -> albums.sortedBy { it.albumArtist.lowercase() }
        // The library list arrives DATE_ADDED-descending (see LocalProvider.
        // allTracks), so keeping input order IS recency for RECENTLY_ADDED.
        LibrarySort.RECENTLY_ADDED, LibrarySort.TRACK_COUNT -> albums
    }
    return if (ascending) sorted else sorted.reversed()
}

fun sortArtists(artists: List<ArtistGroup>, sort: LibrarySort, ascending: Boolean): List<ArtistGroup> {
    val sorted = when (sort) {
        LibrarySort.NAME, LibrarySort.ARTIST -> artists.sortedBy { it.artist.lowercase() }
        LibrarySort.TRACK_COUNT -> artists.sortedBy { it.tracks.size }
        LibrarySort.RECENTLY_ADDED -> artists
    }
    return if (ascending) sorted else sorted.reversed()
}

fun sortTracks(tracks: List<Track>, sort: LibrarySort, ascending: Boolean): List<Track> {
    val sorted = when (sort) {
        LibrarySort.NAME -> tracks.sortedBy { it.title.lowercase() }
        LibrarySort.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
        LibrarySort.RECENTLY_ADDED, LibrarySort.TRACK_COUNT -> tracks
    }
    return if (ascending) sorted else sorted.reversed()
}
