package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalInternalModelsTest {

    // ---- releaseYearOf (moved from TidalMetadataPrimitivesTest) ----

    @Test fun `takes the release year from an ISO date`() {
        assertEquals(2021, releaseYearOf("2021-05-14"))
    }

    @Test fun `release year is null for null or empty input`() {
        assertNull(releaseYearOf(null))
        assertNull(releaseYearOf(""))
    }

    // ---- local ∪ Tidal discography dedup (mirrors desktop window.py:1394-1407) ----

    @Test
    fun `dedupRemoteAlbums drops remote albums whose lowercase title is already local`() {
        val remote = listOf(
            albumSummary("1", "Album One"),
            albumSummary("2", "Ride the Lightning"),
        )
        val kept = dedupRemoteAlbums(localTitles = setOf("album one"), remote = remote)
        assertEquals(listOf("Ride the Lightning"), kept.map { it.title })
    }

    @Test
    fun `dedupRemoteAlbums drops duplicates within the remote list itself, keeping the first`() {
        // The real API returned `Fixxxer (Remastered)` TWICE for artist 8405.
        // Desktop adds to seen_names inside the loop, so the second is dropped.
        val remote = listOf(
            albumSummary("1", "Fixxxer (Remastered)"),
            albumSummary("2", "Fixxxer (Remastered)"),
            albumSummary("3", "FIXXXER (REMASTERED)"),
        )
        assertEquals(listOf("1"), dedupRemoteAlbums(emptySet(), remote).map { it.id })
    }

    @Test
    fun `dedupRemoteAlbums is case-insensitive and preserves remote order`() {
        val remote = listOf(albumSummary("1", "Kill 'Em All"), albumSummary("2", "Reload"))
        assertEquals(
            listOf("Reload"),
            dedupRemoteAlbums(localTitles = setOf("kill 'em all"), remote = remote).map { it.title },
        )
    }

    // ---- punctuation-variant dedup (DELIBERATELY BEATS THE DESKTOP, 2026-07-16) ----

    @Test
    fun `normalizeTitleForDedup folds the dash family, ellipses and dots to spaces`() {
        // The dash family and the two ellipsis spellings are the styling
        // differences Tidal ships the SAME release under.
        assertEquals("hardwired to self destruct", normalizeTitleForDedup("Hardwired...To Self Destruct"))
        assertEquals("hardwired to self destruct", normalizeTitleForDedup("Hardwired…To Self-Destruct"))
        assertEquals("hardwired to self destruct", normalizeTitleForDedup("Hardwired... To Self-Destruct"))
        assertEquals("st anger", normalizeTitleForDedup("St. Anger"))
        assertEquals("and justice for all", normalizeTitleForDedup("...And Justice for All"))
        // Parentheticals are UNTOUCHED — they distinguish real releases.
        assertEquals("72 seasons (atmos mix)", normalizeTitleForDedup("72 Seasons (Atmos Mix)"))
    }

    @Test
    fun `normalizeTitleForDedup falls back to the raw title when folding leaves nothing`() {
        // An all-punctuation title folds to "", and TWO such titles would then
        // collide and eat each other. The fallback keeps them distinct.
        assertEquals("...", normalizeTitleForDedup("..."))
        assertNotEquals(normalizeTitleForDedup("..."), normalizeTitleForDedup("…"))
    }

    @Test
    fun `dedupRemoteAlbums merges the Hardwired hyphen variants seen on device`() {
        // THE REPORTED CASE: the discography showed `Hardwired...To Self Destruct`
        // AND `Hardwired...To Self-Destruct` — differing by ONE hyphen, so the old
        // exact-lowercase match kept both.
        val remote = listOf(
            albumSummary("1", "Hardwired...To Self Destruct"),
            albumSummary("2", "Hardwired…To Self-Destruct"),
        )
        assertEquals(listOf("1"), dedupRemoteAlbums(emptySet(), remote).map { it.id })
    }

    @Test
    fun `dedupRemoteAlbums keeps genuinely different releases apart`() {
        // The NEGATIVES. Each of these must render as its own row.
        val remote = listOf(
            albumSummary("1", "72 Seasons"),
            // A different mix is a different release, not a punctuation variant.
            albumSummary("2", "72 Seasons (Atmos Mix)"),
            // Clean/explicit variants both showing is a deliberate product decision.
            albumSummary("3", "72 Seasons (Clean)"),
            // Ambiguous: "Re-Load" may or may not be "Reload". Folding a hyphen to a
            // SPACE (not to nothing) leaves them distinct, which is the safe answer.
            albumSummary("4", "Reload"),
            albumSummary("5", "Re-Load"),
        )
        assertEquals(listOf("1", "2", "3", "4", "5"), dedupRemoteAlbums(emptySet(), remote).map { it.id })
    }

    @Test
    fun `dedupRemoteAlbums normalizes the LOCAL titles too, not just the remote ones`() {
        // The footgun: normalizing only one side means a local
        // `Hardwired...To Self-Destruct` never matches the normalized remote and
        // the duplicate survives anyway.
        val remote = listOf(albumSummary("1", "Hardwired…To Self Destruct"))
        assertTrue(dedupRemoteAlbums(setOf("Hardwired...To Self-Destruct"), remote).isEmpty())
    }

    @Test
    fun `mergeArtistTracks drops a repeated top-track title carrying a DIFFERENT Tidal id`() {
        // THE REPORTED CASE: top tracks showed `Enter Sandman` twice — original and
        // remaster, two distinct catalog ids, so the source:sourceId dedup kept
        // both. Top tracks arrive in popularity order, so the FIRST is the one to
        // keep.
        val top = listOf(
            tidalTrack("100", "Enter Sandman"),
            tidalTrack("200", "Enter Sandman"),
            tidalTrack("300", "One"),
        )
        val merged = mergeArtistTracks(top, emptyList())
        assertEquals(listOf("100", "300"), merged.map { it.sourceId })
    }

    @Test
    fun `mergeArtistTracks never title-dedups a LOCAL track against a Tidal top track`() {
        // The title dedup is scoped to the TIDAL list only. The user's own file is
        // a genuinely distinct, offline-playable track — dropping it because a
        // streaming row happens to share its title would delete their library from
        // their own artist page. The reported bug was two TIDAL ids; this is not
        // that, and it must not be swept up in the fix.
        val top = listOf(tidalTrack("100", "Enter Sandman"))
        val local = listOf(localTrack("f1", "Enter Sandman"))
        assertEquals(
            listOf("Enter Sandman", "Enter Sandman"),
            mergeArtistTracks(top, local).map { it.title },
        )
    }

    @Test
    fun `mergeArtistTracks still drops a local Tidal favorite whose id a deduped top track held`() {
        // Subtle: the id set must be built from the ORIGINAL top tracks, not the
        // title-deduped ones. The remaster (200) is dropped from display, but the
        // user's Tidal favorite OF that remaster must still not reappear below as
        // an "extra" — it was in the top list.
        val top = listOf(tidalTrack("100", "Enter Sandman"), tidalTrack("200", "Enter Sandman"))
        val local = listOf(tidalTrack("200", "Enter Sandman"))
        assertEquals(listOf("100"), mergeArtistTracks(top, local).map { it.sourceId })
    }

    // ---- top tracks ∪ local tracks (mirrors desktop window.py:1409-1422) ----

    @Test
    fun `mergeArtistTracks puts Tidal top tracks first, then local tracks not already in them`() {
        // Desktop: `tracks = list(tidal_top) + local_extras`. Popularity order
        // leads; the user's own extra files follow.
        val top = listOf(tidalTrack("100", "Enter Sandman"), tidalTrack("200", "One"))
        val local = listOf(tidalTrack("200", "One"), localTrack("f1", "Garage B-Side"))
        val merged = mergeArtistTracks(top, local)
        assertEquals(listOf("Enter Sandman", "One", "Garage B-Side"), merged.map { it.title })
    }

    @Test
    fun `mergeArtistTracks keeps the local list untouched when there are no top tracks`() {
        // Desktop guards with `if tidal_top:` — no top tracks means no merge at
        // all, NOT an empty track list. This is the not-logged-in / unknown-artist
        // / call-failed path, and it must leave the offline base exactly as it is.
        val local = listOf(localTrack("f1", "Only Local"))
        assertEquals(local, mergeArtistTracks(emptyList(), local))
    }

    @Test
    fun `mergeArtistTracks never drops a LOCAL track that shares an id with a Tidal top track`() {
        // DELIBERATE DIVERGENCE from desktop (window.py:1413-1421), which compares
        // `source_id` across sources: a MediaStore id and a Tidal catalog id are
        // both numeric strings, so a bare id compare can silently delete the
        // user's own file. Track identity in this codebase is ALWAYS namespaced by
        // source (ArtistDetailScreen.kt:78/166 key on "${source}:${sourceId}"), so
        // the dedup is scoped to Tidal tracks only.
        val top = listOf(tidalTrack("100", "Enter Sandman"))
        val local = listOf(localTrack("100", "My Own Recording"))
        assertEquals(
            listOf("Enter Sandman", "My Own Recording"),
            mergeArtistTracks(top, local).map { it.title },
        )
    }

    @Test
    fun `mergeArtistTracks yields keys unique by source and id even if Tidal repeats a track`() {
        // The merged list is rendered by a LazyColumn keyed on
        // "${source}:${sourceId}" (ArtistDetailScreen.kt:166). A duplicate key is
        // a hard Compose crash, and `topTracks` does NOT come through the pool's
        // `distinctBy` (ArtistDetailScreen.kt:78) — so the merge must guarantee it.
        val top = listOf(tidalTrack("100", "Enter Sandman"), tidalTrack("100", "Enter Sandman"))
        val merged = mergeArtistTracks(top, emptyList())
        assertEquals(1, merged.size)
        assertEquals(merged.size, merged.distinctBy { "${it.source}:${it.sourceId}" }.size)
    }

    private fun albumSummary(id: String, title: String) =
        AlbumSummary(id = id, title = title, albumType = "ALBUM", releaseYear = 1984, explicit = false, artworkUrl = null)

    private fun tidalTrack(id: String, title: String) =
        Track(title = title, artist = "Metallica", source = Source.TIDAL, sourceId = id)

    private fun localTrack(id: String, title: String) =
        Track(title = title, artist = "Metallica", source = Source.LOCAL, sourceId = id)
}
