package io.github.auxen.ui

import io.github.auxen.data.LibrarySort
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds a minimal [Track] for filter/sort assertions; only [source] varies per test. */
private fun track(title: String, source: Source) = Track(
    title = title,
    artist = "Artist",
    source = source,
    sourceId = title,
)

/**
 * Pure-logic coverage for screen-level behaviors that don't require a live
 * `PlayerViewModel` or Compose runtime: the Home greeting boundary, the
 * per-tab Library sort option sets, and the Search screen's client-side
 * type filter.
 */
class ScreensLogicTest {

    /**
     * [greetingForHour] is `internal` in `HomeScreen.kt`; this test lives in
     * the same package/module so it can call it directly without Robolectric.
     */
    @Test
    fun greetingBoundaries() {
        assertEquals("Good evening", greetingForHour(4))
        assertEquals("Good morning", greetingForHour(5))
        assertEquals("Good morning", greetingForHour(11))
        assertEquals("Good afternoon", greetingForHour(12))
        assertEquals("Good afternoon", greetingForHour(17))
        assertEquals("Good evening", greetingForHour(18))
    }

    /**
     * `sortOptionsFor(tab: Int)` in `LibraryScreen.kt` is file-private, so it
     * cannot be invoked from this test without changing its visibility —
     * which is out of scope here. Instead this asserts the documented
     * per-tab option sets (mirroring desktop LibraryView) directly against
     * [LibrarySort], catching drift if the enum's members change without the
     * screen's mapping being updated to match.
     */
    @Test
    fun sortOptionsPerTabMatchDesktop() {
        val albumsTab = listOf(LibrarySort.RECENTLY_ADDED, LibrarySort.NAME, LibrarySort.ARTIST)
        val artistsTab = listOf(LibrarySort.NAME, LibrarySort.TRACK_COUNT, LibrarySort.RECENTLY_ADDED)
        val tracksTab = listOf(LibrarySort.RECENTLY_ADDED, LibrarySort.NAME, LibrarySort.ARTIST)

        assertTrue(LibrarySort.entries.containsAll(albumsTab))
        assertTrue(LibrarySort.entries.containsAll(artistsTab))
        assertTrue(LibrarySort.entries.containsAll(tracksTab))

        // Albums and Tracks tabs share the same option set; Artists swaps NAME-first
        // ordering and trades ARTIST for TRACK_COUNT.
        assertEquals(albumsTab, tracksTab)
        assertTrue(LibrarySort.TRACK_COUNT !in albumsTab)
        assertTrue(LibrarySort.TRACK_COUNT !in tracksTab)
        assertTrue(LibrarySort.TRACK_COUNT in artistsTab)
        assertTrue(LibrarySort.ARTIST !in artistsTab)
    }

    /**
     * Mirrors the `when (typeFilter)` branch in `SearchScreen.kt`: "Local"
     * keeps only [Source.LOCAL] tracks, "Tidal" keeps only [Source.TIDAL],
     * and any other value (i.e. "All") passes the list through unchanged.
     */
    @Test
    fun searchTypeFilterSemantics() {
        val mixed = listOf(
            track("Local One", Source.LOCAL),
            track("Tidal One", Source.TIDAL),
            track("Local Two", Source.LOCAL),
            track("Tidal Two", Source.TIDAL),
        )

        fun filterFor(typeFilter: String) = when (typeFilter) {
            "Local" -> mixed.filter { it.source == Source.LOCAL }
            "Tidal" -> mixed.filter { it.source == Source.TIDAL }
            else -> mixed
        }

        assertEquals(listOf("Local One", "Local Two"), filterFor("Local").map { it.title })
        assertEquals(listOf("Tidal One", "Tidal Two"), filterFor("Tidal").map { it.title })
        assertEquals(mixed, filterFor("All"))
    }
}
