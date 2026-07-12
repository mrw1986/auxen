package io.github.auxen.data

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

private fun t(
    title: String,
    artist: String = "Artist",
    album: String? = "Album",
    albumArtist: String? = null,
    trackNumber: Int? = null,
    discNumber: Int? = null,
    sourceId: String = title,
) = Track(
    title = title,
    artist = artist,
    source = Source.LOCAL,
    sourceId = sourceId,
    album = album,
    albumArtist = albumArtist,
    trackNumber = trackNumber,
    discNumber = discNumber,
)

class LibraryGroupingTest {

    @Test
    fun groupAlbumsKeysOnAlbumPlusAlbumArtistAndOrdersTracks() {
        val tracks = listOf(
            t("B2", album = "X", trackNumber = 2, discNumber = 1),
            t("A1", album = "X", trackNumber = 1, discNumber = 1),
            t("C1", album = "X", trackNumber = 1, discNumber = 2),
            t("Solo", album = "Y", artist = "Other"),
        )
        val albums = groupAlbums(tracks)
        assertEquals(2, albums.size)
        val x = albums.first { it.album == "X" }
        assertEquals(listOf("A1", "B2", "C1"), x.tracks.map { it.title })
    }

    @Test
    fun sameAlbumNameDifferentArtistStaysSeparate() {
        val tracks = listOf(
            t("One", album = "Greatest Hits", artist = "Queen"),
            t("Two", album = "Greatest Hits", artist = "ABBA"),
        )
        assertEquals(2, groupAlbums(tracks).size)
    }

    @Test
    fun unknownAlbumAndArtistFallbacks() {
        val albums = groupAlbums(listOf(t("Loose", album = null)))
        assertEquals("Unknown Album", albums.single().album)
        val artists = groupArtists(listOf(t("Loose", artist = "")))
        assertEquals("Unknown Artist", artists.single().artist)
    }

    @Test
    fun groupArtistsAggregatesTracks() {
        val artists = groupArtists(
            listOf(t("One", artist = "Queen"), t("Two", artist = "Queen"), t("Three", artist = "ABBA")),
        )
        assertEquals(2, artists.size)
        assertEquals(2, artists.first { it.artist == "Queen" }.tracks.size)
    }

    @Test
    fun sortModesAndDirection() {
        val albums = groupAlbums(
            listOf(
                t("One", album = "Bravo", artist = "Zed"),
                t("Two", album = "Alpha", artist = "Ann"),
            ),
        )
        assertEquals(listOf("Alpha", "Bravo"), sortAlbums(albums, LibrarySort.NAME, ascending = true).map { it.album })
        assertEquals(listOf("Bravo", "Alpha"), sortAlbums(albums, LibrarySort.NAME, ascending = false).map { it.album })
        assertEquals(listOf("Two", "One"), sortTracks(listOf(t("One"), t("Two")), LibrarySort.NAME, ascending = false).map { it.title })

        val artists = groupArtists(
            listOf(t("A", artist = "Duo1"), t("B", artist = "Duo1"), t("C", artist = "Solo")),
        )
        assertEquals(
            listOf("Duo1", "Solo"),
            sortArtists(artists, LibrarySort.TRACK_COUNT, ascending = false).map { it.artist },
        )
    }
}
