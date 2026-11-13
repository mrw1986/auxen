package io.github.auxen.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure folder logic — the (selected, folder) predicate, folder identity
 * derivation from a MediaStore DATA path, distinct-folder enumeration with
 * counts, and the settings-table JSON codec. No live MediaStore: every input
 * is a fabricated DATA string, exactly as the plan requires (the query wrapper
 * that produces these strings is untested).
 */
class LocalFoldersTest {

    @Test
    fun `folderPathOf returns the parent directory`() {
        assertEquals("/storage/emulated/0/Music/FLAC", folderPathOf("/storage/emulated/0/Music/FLAC/song.flac"))
        assertEquals("/storage/emulated/0/Music", folderPathOf("/storage/emulated/0/Music/track.mp3"))
    }

    @Test
    fun `folderPathOf is blank for a parentless or empty path`() {
        assertEquals("", folderPathOf("song.flac"))
        assertEquals("", folderPathOf(""))
    }

    @Test
    fun `folderDisplayName strips the primary-volume prefix`() {
        assertEquals("Music/FLAC", folderDisplayName("/storage/emulated/0/Music/FLAC"))
        assertEquals("Music", folderDisplayName("/storage/emulated/0/Music"))
        assertEquals("Internal storage", folderDisplayName("/storage/emulated/0"))
        // A non-primary volume (SD card) has no prefix to strip — shown fuller.
        assertEquals("storage/1234-5678/Music", folderDisplayName("/storage/1234-5678/Music"))
    }

    @Test
    fun `isFolderIncluded treats an empty selection as all folders`() {
        assertTrue(isFolderIncluded(emptySet(), "/storage/emulated/0/Music"))
        assertTrue(isFolderIncluded(setOf("/a", "/b"), "/a"))
        assertFalse(isFolderIncluded(setOf("/a", "/b"), "/c"))
        // A blank folder (null DATA) is excluded when a selection is active.
        assertFalse(isFolderIncluded(setOf("/a"), ""))
    }

    @Test
    fun `foldersFrom groups distinct folders with counts sorted by display name`() {
        val folders = foldersFrom(
            listOf(
                "/storage/emulated/0/Music/Rock/a.flac",
                "/storage/emulated/0/Music/Rock/b.flac",
                "/storage/emulated/0/Music/Jazz/c.mp3",
                "bad-path-no-parent",
                "",
            ),
        )
        assertEquals(2, folders.size)
        // Jazz-folder sorts before Rock-folder (case-insensitive display-name
        // order); displayName is the full relative path (folderDisplayName strips
        // only the primary-volume prefix, so same-named subfolders never collide).
        assertEquals(listOf("Music/Jazz", "Music/Rock"), folders.map { it.displayName })
        assertEquals("/storage/emulated/0/Music/Jazz", folders[0].path)
        assertEquals(1, folders[0].trackCount)
        assertEquals(2, folders[1].trackCount)
    }

    @Test
    fun `encodeFolders and decodeFolders round-trip`() {
        val set = setOf("/storage/emulated/0/Music/Rock", "/storage/emulated/0/Music/Jazz")
        assertEquals(set, decodeFolders(encodeFolders(set)))
    }

    @Test
    fun `decodeFolders is empty for null, blank, or garbage`() {
        assertEquals(emptySet<String>(), decodeFolders(null))
        assertEquals(emptySet<String>(), decodeFolders(""))
        assertEquals(emptySet<String>(), decodeFolders("not json"))
        assertEquals(emptySet<String>(), decodeFolders("{\"unexpected\":true}"))
    }
}
