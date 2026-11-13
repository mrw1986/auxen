package io.github.auxen.data

import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track

/**
 * The Collection Albums/Artists union — the Android port of the desktop
 * CollectionView's semantics: Albums = Tidal saved albums ∪ local albums with
 * favorited tracks; Artists likewise. Pure and unit-tested; grouping itself is
 * reused from [groupAlbums]/[groupArtists] (LibraryGrouping.kt).
 */

/**
 * One Collection Albums-tab entry. Either a locally-grouped album (favorited
 * tracks — has [tracks]) or a Tidal saved album with no local tracks (has
 * [tidalId]). On internal-v1 both carry [artist] + [artUrl].
 */
data class CollectionAlbum(
    val title: String,
    val artist: String?,
    val artUrl: String?,
    val source: Source?,
    val tidalId: String?,
    val tracks: List<Track>,
) {
    /** True when this row exists because the user favorited the ALBUM on Tidal (vs. because its tracks are favorited). */
    val isRemoteFavorite: Boolean get() = tidalId != null

    /** Stable, unique LazyGrid key: remote favorites by Tidal id, local groups by (title, artist). */
    val key: String get() = if (tidalId != null) "tidal:$tidalId" else "local:$title|${artist.orEmpty()}"

    /** Project back to an [AlbumGroup] for the existing `album/{album}/{artist}` navigation route. */
    fun toAlbumGroup(): AlbumGroup = AlbumGroup(
        album = title,
        albumArtist = artist.orEmpty(),
        artUrl = artUrl,
        year = null,
        tracks = tracks,
    )
}

/** One Collection Artists-tab entry — a locally-grouped artist, or a Tidal followed artist with no local tracks. */
data class CollectionArtist(
    val name: String,
    val artUrl: String?,
    val source: Source?,
    val tidalId: String?,
    val trackCount: Int,
    // The local ArtistGroup this row came from, or null for a follow-only remote
    // artist (which has no local tracks). Carried so the Collection Artists tab
    // can keep its long-press action sheet (Play / Shuffle / Play Next / Enqueue),
    // added in the 2026-07-18 context-menu work — the sheet needs a real track
    // pool, which only a local group has. mergeCollectionArtists sets this to the
    // local group for local entries and null for remote-only ones.
    val localGroup: ArtistGroup? = null,
) {
    val isRemoteFavorite: Boolean get() = tidalId != null

    val key: String get() = if (tidalId != null) "tidal:$tidalId" else "local:$name"
}

/**
 * Albums tab = [local] groups ∪ [remote] saved albums, local first. A remote
 * favorite whose title matches a local group's album title is dropped: the
 * local group is strictly richer (playable tracks). Dedup is EXACT-name
 * (trimmed, case-insensitive) — never fuzzy, so clean/explicit and deluxe
 * variants deliberately stay visible as separate entries (desktop behaviour).
 */
fun mergeCollectionAlbums(local: List<AlbumGroup>, remote: List<FavoriteAlbumEntity>): List<CollectionAlbum> {
    val localTitles = local.mapTo(mutableSetOf()) { it.album.normalizedName() }
    val localEntries = local.map { group ->
        CollectionAlbum(
            title = group.album,
            artist = group.albumArtist,
            artUrl = group.artUrl,
            source = group.tracks.firstOrNull()?.source,
            tidalId = null,
            tracks = group.tracks,
        )
    }
    val remoteOnly = remote
        .filter { it.title.normalizedName() !in localTitles }
        .map { row ->
            CollectionAlbum(
                title = row.title,
                artist = row.artist,
                artUrl = row.artUrl,
                source = sourceOf(row.source),
                tidalId = row.sourceId.takeIf { row.source == Source.TIDAL.name },
                tracks = emptyList(),
            )
        }
    return localEntries + remoteOnly
}

/** Artists tab = [local] groups ∪ [remote] followed artists, local first. Same exact-name dedup as [mergeCollectionAlbums]. */
fun mergeCollectionArtists(local: List<ArtistGroup>, remote: List<FavoriteArtistEntity>): List<CollectionArtist> {
    val localNames = local.mapTo(mutableSetOf()) { it.artist.normalizedName() }
    val localEntries = local.map { group ->
        CollectionArtist(
            name = group.artist,
            artUrl = group.artUrl,
            source = group.tracks.firstOrNull()?.source,
            tidalId = null,
            trackCount = group.tracks.size,
            localGroup = group, // carries the track pool so the row keeps its long-press action sheet
        )
    }
    val remoteOnly = remote
        .filter { it.name.normalizedName() !in localNames }
        .map { row ->
            CollectionArtist(
                name = row.name,
                artUrl = row.artUrl,
                source = sourceOf(row.source),
                tidalId = row.sourceId.takeIf { row.source == Source.TIDAL.name },
                trackCount = 0,
            )
        }
    return localEntries + remoteOnly
}

/** The dedup key: exact name, tolerant only of case and surrounding whitespace. */
private fun String.normalizedName(): String = trim().lowercase()

/** Tolerant [Source] lookup — mirrors LibraryRepository.sourcePriority()'s `entries.firstOrNull` pattern; null on an unknown value. */
private fun sourceOf(name: String): Source? = Source.entries.firstOrNull { it.name == name }
