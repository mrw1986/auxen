package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure merge/projection logic for two-way collection sync. The network+DB
 * orchestration (pull/push) is untested (internal-v1 precedent); only the
 * additive-union delta and the entity projections are unit-tested. ONE
 * newRemoteFavorites serves both albums and artists.
 */
class TidalCollectionSyncTest {

    private fun album(id: String, title: String = "T", addedAt: Long? = null) =
        TidalFavoriteAlbum(id = id, title = title, artistName = "A", coverUrl = "c", addedAtMillis = addedAt)

    private fun artist(id: String, name: String = "N", addedAt: Long? = null) =
        TidalFavoriteArtist(id = id, name = name, pictureUrl = "p", addedAtMillis = addedAt)

    @Test
    fun `newRemoteFavorites keeps only remote favorites not already local (additive union)`() {
        val remote = listOf(album("111"), album("222"), album("333"))
        val localKeys = setOf("TIDAL:111", "LOCAL:999")
        assertEquals(listOf("222", "333"), newRemoteFavorites(remote, localKeys) { it.id }.map { it.id })
    }

    @Test
    fun `newRemoteFavorites returns empty when every remote favorite is already local`() {
        val remote = listOf(album("111"), album("222"))
        assertEquals(emptyList<String>(), newRemoteFavorites(remote, setOf("TIDAL:111", "TIDAL:222")) { it.id }.map { it.id })
    }

    @Test
    fun `newRemoteFavorites is reused unchanged for artists`() {
        val remote = listOf(artist("7"), artist("8"))
        assertEquals(listOf("7"), newRemoteFavorites(remote, setOf("TIDAL:8")) { it.id }.map { it.id })
    }

    @Test
    fun `favoriteAlbumEntityFor projects a Tidal album favorite row with cover and artist`() {
        val row = favoriteAlbumEntityFor(album("9", title = "Album Nine"), addedAtMillis = 1_000)
        assertEquals(Source.TIDAL.name, row.source)
        assertEquals("9", row.sourceId)
        assertEquals("Album Nine", row.title)
        assertEquals("A", row.artist)
        assertEquals("c", row.artUrl)
        assertEquals(1_000L, row.addedAtMillis)
    }

    @Test
    fun `favoriteAlbumEntityFor falls back to a stable title when the favorite has none`() {
        val blank = TidalFavoriteAlbum(id = "12", title = "  ", artistName = null, coverUrl = null, addedAtMillis = null)
        val row = favoriteAlbumEntityFor(blank, 0)
        assertEquals("Tidal album 12", row.title)
        assertNull(row.artist)
        assertNull(row.artUrl)
    }

    @Test
    fun `favoriteArtistEntityFor projects a Tidal artist favorite row with picture`() {
        val row = favoriteArtistEntityFor(artist("7", name = "Prompto"), addedAtMillis = 2_000)
        assertEquals(Source.TIDAL.name, row.source)
        assertEquals("7", row.sourceId)
        assertEquals("Prompto", row.name)
        assertEquals("p", row.artUrl)
        assertEquals(2_000L, row.addedAtMillis)
    }

    @Test
    fun `favoriteArtistEntityFor falls back to a stable name when the favorite has none`() {
        val blank = TidalFavoriteArtist(id = "21", name = "", pictureUrl = null, addedAtMillis = null)
        assertEquals("Tidal artist 21", favoriteArtistEntityFor(blank, 0).name)
    }
}
