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

    // --- groupTracksByDisc (album-detail disc grouping) ---

    @Test
    fun groupTracksByDiscEmptyReturnsSingleNullSection() {
        val sections = groupTracksByDisc(emptyList())
        assertEquals(1, sections.size)
        assertEquals(null, sections.single().discNumber)
        assertEquals(emptyList<Track>(), sections.single().tracks)
    }

    @Test
    fun groupTracksByDiscSingleDiscHasNoHeaderAndKeepsOrder() {
        // All on disc 1 -> not multi-disc -> one null-disc section (no "Disc N"
        // header), tracks passed through in incoming order, untouched.
        val tracks = listOf(
            t("Two", trackNumber = 2, discNumber = 1),
            t("One", trackNumber = 1, discNumber = 1),
        )
        val sections = groupTracksByDisc(tracks)
        assertEquals(1, sections.size)
        assertEquals(null, sections.single().discNumber)
        assertEquals(listOf("Two", "One"), sections.single().tracks.map { it.title })
    }

    @Test
    fun groupTracksByDiscAllNullDiscNumbersIsSingleSection() {
        val tracks = listOf(t("A", trackNumber = 1), t("B", trackNumber = 2))
        val sections = groupTracksByDisc(tracks)
        assertEquals(1, sections.size)
        assertEquals(null, sections.single().discNumber)
        assertEquals(listOf("A", "B"), sections.single().tracks.map { it.title })
    }

    @Test
    fun groupTracksByDiscMultiDiscSplitsAndOrdersByDiscThenTrack() {
        val tracks = listOf(
            t("D2T1", trackNumber = 1, discNumber = 2),
            t("D1T2", trackNumber = 2, discNumber = 1),
            t("D1T1", trackNumber = 1, discNumber = 1),
            t("D2T2", trackNumber = 2, discNumber = 2),
        )
        val sections = groupTracksByDisc(tracks)
        assertEquals(2, sections.size)
        assertEquals(1, sections[0].discNumber)
        assertEquals(listOf("D1T1", "D1T2"), sections[0].tracks.map { it.title })
        assertEquals(2, sections[1].discNumber)
        assertEquals(listOf("D2T1", "D2T2"), sections[1].tracks.map { it.title })
    }

    @Test
    fun groupTracksByDiscMultiDiscPutsNullDiscTracksLast() {
        val tracks = listOf(
            t("Loose", trackNumber = 5),
            t("D2T1", trackNumber = 1, discNumber = 2),
            t("D1T1", trackNumber = 1, discNumber = 1),
        )
        val sections = groupTracksByDisc(tracks)
        assertEquals(3, sections.size)
        assertEquals(1, sections[0].discNumber)
        assertEquals(2, sections[1].discNumber)
        assertEquals(null, sections[2].discNumber)
        assertEquals(listOf("Loose"), sections[2].tracks.map { it.title })
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
