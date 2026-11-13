# Android Auto (v1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user browse a curated, driving-safe subset of their Auxen library (Recently played, Playlists, Albums, Artists, Favorite tracks) on an Android Auto head unit and tap to play, reusing the existing Media3 streaming stack.

**Architecture:** A pure `AuxenBrowseTree` maps repo snapshots → Media3 `MediaItem` browse nodes; an isolated `TidalBrowseFetcher` supplies album/artist tracks on demand; a pure `BrowseQueueResolver` caches served lists so a tapped leaf expands into its sibling queue; `PlaybackService` is promoted from `MediaSessionService` to `MediaLibraryService` and wires the browse callbacks. Any leaf reuses `Graph.mediaItemFor`, so playback/streaming is unchanged.

**Tech Stack:** Kotlin, Media3 1.5.1 (`media3-session`/`media3-exoplayer`), Guava `ListenableFuture`, `kotlinx-coroutines-guava` (added), Room, JUnit.

## Global Constraints

- Media3 version is **1.5.1** — use only APIs available there. `MediaSession.MediaItemsWithStartPosition` is already imported in `PlaybackService.kt`.
- **Never** log or embed `accessToken`/`refreshToken`/session data in any node, extra, or log line.
- **Playback, notification, bit-perfect player-swap (`setPlayer`), queue save/restore, and the `ResolvingDataSource` factory must stay behaviorally identical.** The only change to those areas is the base-class/type promotion; no logic edits.
- Leaf **track** `MediaItem`s keep `mediaId = "SOURCE:sourceId"` (built by `Graph.mediaItemFor`) — do not change that format; other code routes on it.
- Browse **folder** ids use the `auxen://browse/...` scheme defined in Task 1.
- Every `onGetChildren`/`onGetItem`/`onGetLibraryRoot` path must **fail safe**: a failed/near-empty result returns a friendly non-playable node or a `LibraryResult` error — never a thrown exception, never a silent empty that reads as "no content".
- APK delivery: build locally (CI lacks Tidal creds), verify the About string is `0.1.0+<sha>` before any on-device test (see `android-build-identity-verification`).
- `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10` on every gradle invocation, run from `android/`.
- Git: explicit pathspecs only — never `git add -A`/`.`/`-a`.

---

## File Structure

- Create `android/app/src/main/kotlin/io/github/auxen/playback/browse/AuxenBrowseIds.kt` — browse-id scheme + parse (pure).
- Create `android/app/src/main/kotlin/io/github/auxen/playback/browse/AuxenBrowseTree.kt` — pure node builders (root, category lists, detail-from-tracks, empty/error nodes).
- Create `android/app/src/main/kotlin/io/github/auxen/playback/browse/TidalBrowseFetcher.kt` — album/artist track fetch (network), failure-safe.
- Create `android/app/src/main/kotlin/io/github/auxen/playback/browse/BrowseQueueResolver.kt` — caches served lists; resolves a tapped id → sibling queue + start index (pure).
- Modify `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt` — promote to `MediaLibraryService`; wire callbacks.
- Modify `android/app/src/main/AndroidManifest.xml` — Android Auto meta-data.
- Create `android/app/src/main/res/xml/automotive_app_desc.xml` — `<uses name="media"/>`.
- Modify `android/gradle/libs.versions.toml` + `android/app/build.gradle.kts` — add `kotlinx-coroutines-guava`.
- Tests: `android/app/src/test/kotlin/io/github/auxen/playback/browse/{AuxenBrowseTreeTest,BrowseQueueResolverTest}.kt`.

---

## Task 1: Browse ids + `AuxenBrowseTree` (pure) + tests

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/browse/AuxenBrowseIds.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/browse/AuxenBrowseTree.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/browse/AuxenBrowseTreeTest.kt`

**Interfaces:**
- Consumes: `io.github.auxen.model.Track`, `io.github.auxen.model.Source`, `io.github.auxen.db.PlaylistEntity`, `io.github.auxen.db.FavoriteAlbumEntity`, `io.github.auxen.db.FavoriteArtistEntity`, `io.github.auxen.AuxenApp.Graph.mediaItemFor(Track): MediaItem`.
- Produces:
  - `object AuxenBrowseIds` with `const val ROOT/RECENT/PLAYLISTS/ALBUMS/ARTISTS/FAVORITES: String`; `fun playlist(id: Long): String`; `fun album(tidalId: String): String`; `fun artist(tidalId: String): String`; `fun playAllAlbum(tidalId: String): String`; `fun playAllArtist(tidalId: String): String`; `fun parse(mediaId: String): BrowseTarget`.
  - `sealed interface BrowseTarget` with `Root, Recent, Playlists, Albums, Artists, Favorites, data class Playlist(id: Long), data class Album(tidalId: String), data class Artist(tidalId: String), data class PlayAllAlbum(tidalId: String), data class PlayAllArtist(tidalId: String), data class Unknown(raw: String)`.
  - `class AuxenBrowseTree` (no ctor deps) with:
    - `fun root(): MediaItem`
    - `fun rootChildren(): List<MediaItem>`
    - `fun playlistCategory(playlists: List<PlaylistEntity>): List<MediaItem>`
    - `fun albumCategory(albums: List<FavoriteAlbumEntity>): List<MediaItem>`
    - `fun artistCategory(artists: List<FavoriteArtistEntity>): List<MediaItem>`
    - `fun trackList(tracks: List<Track>): List<MediaItem>`
    - `fun albumDetail(tidalId: String, tracks: List<Track>): List<MediaItem>`
    - `fun artistDetail(tidalId: String, tracks: List<Track>): List<MediaItem>`
    - `fun emptyNode(text: String): MediaItem`
    - `fun errorNode(): MediaItem`
    - `fun item(mediaId: String): MediaItem?` (single-node builder for `onGetItem`; folders + play-all only — track items come from the caches, so returns null for a bare track id)

- [ ] **Step 1: Write `AuxenBrowseIds.kt`**

```kotlin
package io.github.auxen.playback.browse

/** Stable, parseable ids for Auxen's Android Auto browse folders. Track leaves keep
 *  their "SOURCE:sourceId" ids (built by Graph.mediaItemFor) and are NOT in this scheme. */
object AuxenBrowseIds {
    const val ROOT = "auxen://browse/root"
    const val RECENT = "auxen://browse/recent"
    const val PLAYLISTS = "auxen://browse/playlists"
    const val ALBUMS = "auxen://browse/albums"
    const val ARTISTS = "auxen://browse/artists"
    const val FAVORITES = "auxen://browse/favorites"

    fun playlist(id: Long) = "auxen://browse/playlist/$id"
    fun album(tidalId: String) = "auxen://browse/album/$tidalId"
    fun artist(tidalId: String) = "auxen://browse/artist/$tidalId"
    fun playAllAlbum(tidalId: String) = "auxen://browse/playall/album/$tidalId"
    fun playAllArtist(tidalId: String) = "auxen://browse/playall/artist/$tidalId"

    fun parse(mediaId: String): BrowseTarget = when {
        mediaId == ROOT -> BrowseTarget.Root
        mediaId == RECENT -> BrowseTarget.Recent
        mediaId == PLAYLISTS -> BrowseTarget.Playlists
        mediaId == ALBUMS -> BrowseTarget.Albums
        mediaId == ARTISTS -> BrowseTarget.Artists
        mediaId == FAVORITES -> BrowseTarget.Favorites
        mediaId.startsWith("auxen://browse/playall/album/") ->
            BrowseTarget.PlayAllAlbum(mediaId.removePrefix("auxen://browse/playall/album/"))
        mediaId.startsWith("auxen://browse/playall/artist/") ->
            BrowseTarget.PlayAllArtist(mediaId.removePrefix("auxen://browse/playall/artist/"))
        mediaId.startsWith("auxen://browse/playlist/") ->
            mediaId.removePrefix("auxen://browse/playlist/").toLongOrNull()
                ?.let { BrowseTarget.Playlist(it) } ?: BrowseTarget.Unknown(mediaId)
        mediaId.startsWith("auxen://browse/album/") ->
            BrowseTarget.Album(mediaId.removePrefix("auxen://browse/album/"))
        mediaId.startsWith("auxen://browse/artist/") ->
            BrowseTarget.Artist(mediaId.removePrefix("auxen://browse/artist/"))
        else -> BrowseTarget.Unknown(mediaId)
    }
}

sealed interface BrowseTarget {
    data object Root : BrowseTarget
    data object Recent : BrowseTarget
    data object Playlists : BrowseTarget
    data object Albums : BrowseTarget
    data object Artists : BrowseTarget
    data object Favorites : BrowseTarget
    data class Playlist(val id: Long) : BrowseTarget
    data class Album(val tidalId: String) : BrowseTarget
    data class Artist(val tidalId: String) : BrowseTarget
    data class PlayAllAlbum(val tidalId: String) : BrowseTarget
    data class PlayAllArtist(val tidalId: String) : BrowseTarget
    data class Unknown(val raw: String) : BrowseTarget
}
```

- [ ] **Step 2: Write the failing test `AuxenBrowseTreeTest.kt`**

```kotlin
package io.github.auxen.playback.browse

import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuxenBrowseTreeTest {
    private val tree = AuxenBrowseTree()

    private fun track(id: String, title: String) = Track(
        title = title, artist = "A", source = Source.TIDAL, sourceId = id,
    )

    @Test fun `root has the five categories in order`() {
        val ids = tree.rootChildren().map { it.mediaId }
        assertEquals(
            listOf(
                AuxenBrowseIds.RECENT, AuxenBrowseIds.PLAYLISTS, AuxenBrowseIds.ALBUMS,
                AuxenBrowseIds.ARTISTS, AuxenBrowseIds.FAVORITES,
            ),
            ids,
        )
    }

    @Test fun `root children are browsable folders, not playable`() {
        tree.rootChildren().forEach {
            assertTrue(it.mediaMetadata.isBrowsable == true)
            assertFalse(it.mediaMetadata.isPlayable == true)
        }
    }

    @Test fun `album category maps each favorite album to a browsable node`() {
        val albums = listOf(
            FavoriteAlbumEntity(source = "TIDAL", sourceId = "111", title = "Album One", addedAtMillis = 1),
            FavoriteAlbumEntity(source = "TIDAL", sourceId = "222", title = "Album Two", addedAtMillis = 2),
        )
        val nodes = tree.albumCategory(albums)
        assertEquals(listOf(AuxenBrowseIds.album("111"), AuxenBrowseIds.album("222")), nodes.map { it.mediaId })
        assertEquals("Album One", nodes[0].mediaMetadata.title)
        assertTrue(nodes[0].mediaMetadata.isBrowsable == true)
    }

    @Test fun `artist category maps each followed artist`() {
        val artists = listOf(
            FavoriteArtistEntity(source = "TIDAL", sourceId = "9", name = "Artist Nine", addedAtMillis = 1),
        )
        val nodes = tree.artistCategory(artists)
        assertEquals(AuxenBrowseIds.artist("9"), nodes.single().mediaId)
        assertEquals("Artist Nine", nodes.single().mediaMetadata.title)
    }

    @Test fun `playlist category maps each playlist by local id`() {
        val pls = listOf(PlaylistEntity(id = 5, name = "Roadtrip"))
        val nodes = tree.playlistCategory(pls)
        assertEquals(AuxenBrowseIds.playlist(5), nodes.single().mediaId)
        assertEquals("Roadtrip", nodes.single().mediaMetadata.title)
    }

    @Test fun `trackList produces playable leaves with SOURCE colon id media ids`() {
        val nodes = tree.trackList(listOf(track("42", "Song")))
        assertEquals("TIDAL:42", nodes.single().mediaId)
        assertTrue(nodes.single().mediaMetadata.isPlayable == true)
    }

    @Test fun `albumDetail prepends a Play all node then the tracks`() {
        val nodes = tree.albumDetail("111", listOf(track("1", "T1"), track("2", "T2")))
        assertEquals(AuxenBrowseIds.playAllAlbum("111"), nodes.first().mediaId)
        assertTrue(nodes.first().mediaMetadata.isPlayable == true)
        assertEquals(listOf("TIDAL:1", "TIDAL:2"), nodes.drop(1).map { it.mediaId })
    }

    @Test fun `artistDetail prepends a Play all node then the tracks`() {
        val nodes = tree.artistDetail("9", listOf(track("1", "T1")))
        assertEquals(AuxenBrowseIds.playAllArtist("9"), nodes.first().mediaId)
        assertEquals(listOf("TIDAL:1"), nodes.drop(1).map { it.mediaId })
    }

    @Test fun `empty category yields a single non-playable placeholder`() {
        val nodes = tree.albumCategory(emptyList())
        assertEquals(1, nodes.size)
        assertFalse(nodes.single().mediaMetadata.isPlayable == true)
        assertFalse(nodes.single().mediaMetadata.isBrowsable == true)
    }

    @Test fun `errorNode is non-playable and non-browsable`() {
        val n = tree.errorNode()
        assertFalse(n.mediaMetadata.isPlayable == true)
        assertFalse(n.mediaMetadata.isBrowsable == true)
    }

    @Test fun `item builds folders and play-all but not bare track ids`() {
        assertEquals(AuxenBrowseIds.ALBUMS, tree.item(AuxenBrowseIds.ALBUMS)?.mediaId)
        assertEquals(AuxenBrowseIds.playAllAlbum("111"), tree.item(AuxenBrowseIds.playAllAlbum("111"))?.mediaId)
        assertNull(tree.item("TIDAL:42"))
    }

    @Test fun `id parse round-trips`() {
        assertEquals(BrowseTarget.Playlist(7), AuxenBrowseIds.parse(AuxenBrowseIds.playlist(7)))
        assertEquals(BrowseTarget.Album("x"), AuxenBrowseIds.parse(AuxenBrowseIds.album("x")))
        assertEquals(BrowseTarget.PlayAllArtist("z"), AuxenBrowseIds.parse(AuxenBrowseIds.playAllArtist("z")))
        assertTrue(AuxenBrowseIds.parse("garbage") is BrowseTarget.Unknown)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.playback.browse.AuxenBrowseTreeTest" --console=plain`
Expected: FAIL — `AuxenBrowseTree` / `AuxenBrowseIds` unresolved.

- [ ] **Step 4: Write `AuxenBrowseTree.kt`**

```kotlin
package io.github.auxen.playback.browse

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.auxen.AuxenApp.Graph
import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.model.Track

/**
 * Pure builder of the Android Auto browse tree: repo snapshots + browse ids ->
 * Media3 MediaItem nodes. No Android framework calls beyond MediaItem/MediaMetadata
 * construction, no network, no DB — callers pass fetched data in. Track leaves reuse
 * Graph.mediaItemFor, so any leaf this returns is playable through the existing
 * ResolvingDataSource. Folder nodes carry isBrowsable=true/isPlayable=false.
 */
class AuxenBrowseTree {

    fun root(): MediaItem = folder(AuxenBrowseIds.ROOT, "Auxen", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun rootChildren(): List<MediaItem> = listOf(
        folder(AuxenBrowseIds.RECENT, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        folder(AuxenBrowseIds.PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        folder(AuxenBrowseIds.ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        folder(AuxenBrowseIds.ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        folder(AuxenBrowseIds.FAVORITES, "Favorite tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
    )

    fun playlistCategory(playlists: List<PlaylistEntity>): List<MediaItem> =
        playlists.map { folder(AuxenBrowseIds.playlist(it.id), it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
            .ifEmpty { listOf(emptyNode("No playlists yet")) }

    fun albumCategory(albums: List<FavoriteAlbumEntity>): List<MediaItem> =
        albums.map {
            folder(AuxenBrowseIds.album(it.sourceId), it.title, MediaMetadata.MEDIA_TYPE_ALBUM, it.artUrl, it.artist)
        }.ifEmpty { listOf(emptyNode("No saved albums yet")) }

    fun artistCategory(artists: List<FavoriteArtistEntity>): List<MediaItem> =
        artists.map {
            folder(AuxenBrowseIds.artist(it.sourceId), it.name, MediaMetadata.MEDIA_TYPE_ARTIST, it.artUrl)
        }.ifEmpty { listOf(emptyNode("No followed artists yet")) }

    fun trackList(tracks: List<Track>): List<MediaItem> =
        tracks.map(Graph::mediaItemFor).ifEmpty { listOf(emptyNode("Nothing here yet")) }

    fun albumDetail(tidalId: String, tracks: List<Track>): List<MediaItem> =
        if (tracks.isEmpty()) listOf(emptyNode("No tracks"))
        else listOf(playAll(AuxenBrowseIds.playAllAlbum(tidalId))) + tracks.map(Graph::mediaItemFor)

    fun artistDetail(tidalId: String, tracks: List<Track>): List<MediaItem> =
        if (tracks.isEmpty()) listOf(emptyNode("No tracks"))
        else listOf(playAll(AuxenBrowseIds.playAllArtist(tidalId))) + tracks.map(Graph::mediaItemFor)

    fun emptyNode(text: String): MediaItem = inert(mediaId = "auxen://browse/empty", title = text)

    fun errorNode(): MediaItem = inert(mediaId = "auxen://browse/error", title = "Couldn't load — check connection")

    /** Single-node builder for onGetItem: folders + play-all only. Track items are
     *  served from the queue caches, so a bare "SOURCE:sourceId" returns null here. */
    fun item(mediaId: String): MediaItem? = when (val t = AuxenBrowseIds.parse(mediaId)) {
        BrowseTarget.Root -> root()
        BrowseTarget.Recent -> folder(AuxenBrowseIds.RECENT, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        BrowseTarget.Playlists -> folder(AuxenBrowseIds.PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        BrowseTarget.Albums -> folder(AuxenBrowseIds.ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        BrowseTarget.Artists -> folder(AuxenBrowseIds.ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
        BrowseTarget.Favorites -> folder(AuxenBrowseIds.FAVORITES, "Favorite tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        is BrowseTarget.PlayAllAlbum -> playAll(AuxenBrowseIds.playAllAlbum(t.tidalId))
        is BrowseTarget.PlayAllArtist -> playAll(AuxenBrowseIds.playAllArtist(t.tidalId))
        else -> null
    }

    private fun folder(
        id: String, title: String, mediaType: Int, artUrl: String? = null, subtitle: String? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artUrl?.let(android.net.Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build(),
        )
        .build()

    private fun playAll(id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("▶ Play all")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private fun inert(mediaId: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.playback.browse.AuxenBrowseTreeTest" --console=plain`
Expected: PASS (all cases).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/playback/browse/AuxenBrowseIds.kt \
        android/app/src/main/kotlin/io/github/auxen/playback/browse/AuxenBrowseTree.kt \
        android/app/src/test/kotlin/io/github/auxen/playback/browse/AuxenBrowseTreeTest.kt
git commit -m "feat(android): AuxenBrowseTree — pure Android Auto browse-node builder"
```

---

## Task 2: `TidalBrowseFetcher` (album/artist tracks, failure-safe)

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/browse/TidalBrowseFetcher.kt`

**Interfaces:**
- Consumes: `io.github.auxen.provider.tidal.TidalProvider.albumDetail(albumId: String): TidalAlbumDetail?` (its `.tracks: List<Track>`), `TidalProvider.artistTopTracks(artistId: String, limit: Int = 50): List<Track>`, `io.github.auxen.model.Track`.
- Produces: `class TidalBrowseFetcher(private val tidal: TidalProvider)` with `suspend fun albumTracks(tidalId: String): BrowseFetch` and `suspend fun artistTracks(tidalId: String): BrowseFetch`; `sealed interface BrowseFetch { data class Ok(val tracks: List<Track>) : BrowseFetch; data object Failed : BrowseFetch }`.

- [ ] **Step 1: Write `TidalBrowseFetcher.kt`** (no unit test — it is a thin failure-mapping wrapper over network; its logic is exercised by the service integration and the DHU checklist. The `BrowseFetch` mapping is trivial and covered by Task 4's error-node path.)

```kotlin
package io.github.auxen.playback.browse

import io.github.auxen.model.Track
import io.github.auxen.provider.tidal.TidalProvider

/**
 * The only network piece of the Android Auto browse tree: fetches album/artist
 * track lists on demand. Kept out of AuxenBrowseTree so that stays pure. Any
 * failure (not logged in, network error, empty body) maps to [BrowseFetch.Failed]
 * so the caller renders the error node instead of a misleading empty list.
 */
class TidalBrowseFetcher(private val tidal: TidalProvider) {

    suspend fun albumTracks(tidalId: String): BrowseFetch =
        runCatching { tidal.albumDetail(tidalId)?.tracks }
            .getOrNull()
            ?.let { BrowseFetch.Ok(it) }
            ?: BrowseFetch.Failed

    suspend fun artistTracks(tidalId: String): BrowseFetch =
        runCatching { tidal.artistTopTracks(tidalId) }
            .getOrNull()
            ?.let { BrowseFetch.Ok(it) }
            ?: BrowseFetch.Failed
}

sealed interface BrowseFetch {
    data class Ok(val tracks: List<Track>) : BrowseFetch
    data object Failed : BrowseFetch
}
```

- [ ] **Step 2: Compile-check** (no test yet; ensure it builds)

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/playback/browse/TidalBrowseFetcher.kt
git commit -m "feat(android): TidalBrowseFetcher — failure-safe on-demand album/artist tracks"
```

---

## Task 3: `BrowseQueueResolver` (sibling-queue expansion) + tests

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/browse/BrowseQueueResolver.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/browse/BrowseQueueResolverTest.kt`

**Interfaces:**
- Consumes: `androidx.media3.common.MediaItem`, `androidx.media3.session.MediaSession.MediaItemsWithStartPosition`.
- Produces: `class BrowseQueueResolver` with:
  - `fun remember(parentId: String, playableChildren: List<MediaItem>)` — records the ordered playable list served for a browse folder (folders/placeholder nodes filtered out by the caller).
  - `fun expand(tappedMediaId: String): MediaItemsWithStartPosition?` — the sibling list containing `tappedMediaId` with the start index at that item; null if unknown.
  - `fun resolve(mediaId: String): MediaItem?` — the last-served full playable `MediaItem` for a leaf id (URI+extras intact), for `onAddMediaItems` reconstruction; null if never served.

**Why this exists:** Android Auto plays an item by sending back only its `mediaId` (URI/extras stripped). This resolver, populated whenever `onGetChildren` serves a list, lets the service (a) rebuild the full playable `MediaItem` and (b) expand a tapped leaf into "play from here to the end of the list it was shown in".

- [ ] **Step 1: Write the failing test `BrowseQueueResolverTest.kt`**

```kotlin
package io.github.auxen.playback.browse

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseQueueResolverTest {
    private fun leaf(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test fun `expand returns the sibling list with the tapped item as start index`() {
        val r = BrowseQueueResolver()
        r.remember("parentA", listOf(leaf("TIDAL:1"), leaf("TIDAL:2"), leaf("TIDAL:3")))
        val out = r.expand("TIDAL:3")!!
        assertEquals(listOf("TIDAL:1", "TIDAL:2", "TIDAL:3"), out.mediaItems.map { it.mediaId })
        assertEquals(2, out.startIndex)
    }

    @Test fun `expand of an unknown id is null`() {
        assertNull(BrowseQueueResolver().expand("TIDAL:99"))
    }

    @Test fun `resolve returns the full remembered item`() {
        val r = BrowseQueueResolver()
        val item = MediaItem.Builder().setMediaId("TIDAL:7").setUri("auxen://tidal/7").build()
        r.remember("p", listOf(item))
        assertEquals("auxen://tidal/7", r.resolve("TIDAL:7")?.localConfiguration?.uri.toString())
        assertNull(r.resolve("TIDAL:404"))
    }

    @Test fun `re-remembering the same parent replaces its list`() {
        val r = BrowseQueueResolver()
        r.remember("p", listOf(leaf("TIDAL:1"), leaf("TIDAL:2")))
        r.remember("p", listOf(leaf("TIDAL:9")))
        // TIDAL:2 was only in the old list for parent p; but other parents could
        // still hold it. Here nothing else does, so it is gone.
        assertNull(r.expand("TIDAL:2"))
        assertEquals(listOf("TIDAL:9"), r.expand("TIDAL:9")!!.mediaItems.map { it.mediaId })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.playback.browse.BrowseQueueResolverTest" --console=plain`
Expected: FAIL — `BrowseQueueResolver` unresolved.

- [ ] **Step 3: Write `BrowseQueueResolver.kt`**

```kotlin
package io.github.auxen.playback.browse

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition

/**
 * Bridges browse -> playback. Android Auto plays an item by sending back only its
 * mediaId (URI/extras stripped), so this holds the full playable MediaItems last
 * served for each browse folder, keyed by mediaId, and reconstructs a play queue
 * from them: [expand] turns a tapped leaf into "its sibling list, starting at it";
 * [resolve] returns the full item for a single id. Not thread-safe on its own;
 * the service confines all access to its session callback thread.
 */
class BrowseQueueResolver {
    private val parentToItems = LinkedHashMap<String, List<MediaItem>>()
    private val idToItem = HashMap<String, MediaItem>()
    private val idToParent = HashMap<String, String>()

    fun remember(parentId: String, playableChildren: List<MediaItem>) {
        // Drop the old membership for this parent before rebinding (so a removed
        // sibling doesn't linger), then re-index.
        parentToItems[parentId]?.forEach { old ->
            if (idToParent[old.mediaId] == parentId) {
                idToParent.remove(old.mediaId)
                idToItem.remove(old.mediaId)
            }
        }
        parentToItems[parentId] = playableChildren
        playableChildren.forEach {
            idToItem[it.mediaId] = it
            idToParent[it.mediaId] = parentId
        }
    }

    fun expand(tappedMediaId: String): MediaItemsWithStartPosition? {
        val parent = idToParent[tappedMediaId] ?: return null
        val list = parentToItems[parent] ?: return null
        val index = list.indexOfFirst { it.mediaId == tappedMediaId }
        if (index < 0) return null
        return MediaItemsWithStartPosition(list, index, 0L)
    }

    fun resolve(mediaId: String): MediaItem? = idToItem[mediaId]
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.playback.browse.BrowseQueueResolverTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/playback/browse/BrowseQueueResolver.kt \
        android/app/src/test/kotlin/io/github/auxen/playback/browse/BrowseQueueResolverTest.kt
git commit -m "feat(android): BrowseQueueResolver — expand a tapped browse leaf into its sibling queue"
```

---

## Task 4: Promote `PlaybackService` to `MediaLibraryService` + wire callbacks

**Files:**
- Modify: `android/gradle/libs.versions.toml` (add `kotlinx-coroutines-guava`)
- Modify: `android/app/build.gradle.kts` (add the dependency)
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt`

**Interfaces:**
- Consumes: `AuxenBrowseTree`, `AuxenBrowseIds`, `BrowseTarget`, `TidalBrowseFetcher`, `BrowseFetch`, `BrowseQueueResolver` (Tasks 1–3); `io.github.auxen.AuxenApp.Graph` (`library`, `tidal`, `tidalCollectionSync`, `mediaItemFor`); `LibraryRepository.recentlyPlayed(limit)`, `.favorites()` (Flow), `.playlists()` (Flow), `.playlistTracks(id)`, `.favoriteAlbums()` (Flow), `.favoriteArtists()` (Flow); `TidalCollectionSync.importPlaylistTracks(playlistId, uuid)`; `PlaylistEntity.tidalPlaylistId`.
- Produces: no new public API — the service now answers `MediaBrowser`/Android Auto browse requests.

**Context the implementer needs:**
- Current declaration is `class PlaybackService : MediaSessionService()`, field `private var mediaSession: MediaSession? = null`, session built at ~L283 with `MediaSession.Builder(this, player).setSessionActivity(sessionActivity).setCallback(AuxenSessionCallback()).build()`, callback is `private inner class AuxenSessionCallback : MediaSession.Callback` (~L899), and `override fun onGetSession(controllerInfo): MediaSession? = mediaSession` (~L923). **Preserve every existing member of `AuxenSessionCallback`** — this task only changes its supertype and ADDS methods.
- There is an existing `mainScope` CoroutineScope used at ~L301. Reuse it for `future { }`.
- Loading a Tidal playlist's tracks mirrors `PlayerViewModel.loadPlaylist`: read `Graph.library.playlistTracks(id)`; if empty and the playlist is a Tidal one (`PlaylistEntity.tidalPlaylistId != null`), call `Graph.tidalCollectionSync.importPlaylistTracks(id, uuid)` then re-read `playlistTracks(id)`.

- [ ] **Step 1: Add the `kotlinx-coroutines-guava` dependency**

In `android/gradle/libs.versions.toml`, under `[libraries]` (the `coroutines` version already exists):

```toml
kotlinx-coroutines-guava = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-guava", version.ref = "coroutines" }
```

In `android/app/build.gradle.kts`, next to `implementation(libs.kotlinx.coroutines.android)`:

```kotlin
    implementation(libs.kotlinx.coroutines.guava)
```

- [ ] **Step 2: Promote the class, field, session builder, and `onGetSession`**

In `PlaybackService.kt`:

Replace the import `import androidx.media3.session.MediaSessionService` with:
```kotlin
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
```
Add these imports:
```kotlin
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.auxen.playback.browse.AuxenBrowseIds
import io.github.auxen.playback.browse.AuxenBrowseTree
import io.github.auxen.playback.browse.BrowseFetch
import io.github.auxen.playback.browse.BrowseQueueResolver
import io.github.auxen.playback.browse.BrowseTarget
import io.github.auxen.playback.browse.TidalBrowseFetcher
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

> **Implementer note — Media3 override signatures.** `onGetLibraryRoot`, `onGetItem`,
> `onGetChildren`, `onAddMediaItems`, and `onSetMediaItems` must match the exact
> signatures on Media3 1.5.1's `MediaLibrarySession.Callback` (which extends
> `MediaSession.Callback`). Java `List<MediaItem>` maps to a Kotlin platform type, so
> the compiler may require `MutableList<MediaItem>` (parameter) and a specific
> `ListenableFuture<...>` generic (e.g. `ListenableFuture<MutableList<MediaItem>>` vs
> `<List<MediaItem>>`) for `onAddMediaItems`. Write each with `override fun` and let
> the compiler dictate the precise `List`/`MutableList` and generic form — adjust the
> snippets below to whatever the `override` keyword accepts. The bodies are correct;
> only the type spellings may need to match the SDK.

Change the class declaration:
```kotlin
class PlaybackService : MediaLibraryService() {
```

Change the field:
```kotlin
    private var mediaSession: MediaLibrarySession? = null
```

Add these fields near `mediaSession`:
```kotlin
    private val browseTree = AuxenBrowseTree()
    private val browseQueue = BrowseQueueResolver()
    private val browseFetcher by lazy { TidalBrowseFetcher(Graph.tidal) }
```

Change the session builder (~L283) to a `MediaLibrarySession` (the callback is passed in the constructor for a library session, not via `setCallback`):
```kotlin
        mediaSession = MediaLibrarySession.Builder(this, player, AuxenSessionCallback())
            .setSessionActivity(sessionActivity)
            .build()
```

Change `onGetSession` (~L923):
```kotlin
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession
```

- [ ] **Step 3: Change the callback supertype and ADD the library + playback-resolution methods**

Change the callback declaration (~L899) from `: MediaSession.Callback` to:
```kotlin
    private inner class AuxenSessionCallback : MediaLibrarySession.Callback {
```

Inside `AuxenSessionCallback`, keeping all existing members, ADD:

```kotlin
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = browseTree.item(mediaId) ?: browseQueue.resolve(mediaId)
            return if (item != null) Futures.immediateFuture(LibraryResult.ofItem(item, null))
            else Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = mainScope.future {
            val children: List<MediaItem> = withContext(Dispatchers.IO) {
                try {
                    childrenFor(parentId)
                } catch (t: Throwable) {
                    listOf(browseTree.errorNode())
                }
            }
            // Cache the playable leaves of this folder so a tapped item can be
            // reconstructed and expanded into its sibling queue.
            val playable = children.filter { it.mediaMetadata.isPlayable == true }
            if (playable.isNotEmpty()) browseQueue.remember(parentId, playable)
            LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
        }

        // Android Auto sends back items carrying only a mediaId. Rebuild each into a
        // full playable MediaItem (URI + extras) from the browse-queue cache; drop any
        // that cannot be resolved.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) item else browseQueue.resolve(item.mediaId)
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        // When a browse leaf (or "Play all") is chosen, expand to its sibling list so
        // playback continues from the tapped item to the end of the shown list.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val id = mediaItems.single().mediaId
                // "Play all" -> the parent folder's full list from the top.
                val playAllExpansion = when (val t = AuxenBrowseIds.parse(id)) {
                    is BrowseTarget.PlayAllAlbum ->
                        browseQueue.expandParent(AuxenBrowseIds.album(t.tidalId))
                    is BrowseTarget.PlayAllArtist ->
                        browseQueue.expandParent(AuxenBrowseIds.artist(t.tidalId))
                    else -> null
                }
                val expansion = playAllExpansion ?: browseQueue.expand(id)
                if (expansion != null) return Futures.immediateFuture(expansion)
            }
            // Fallback: resolve each id to a full item; keep the requested start.
            val resolved = mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) item else browseQueue.resolve(item.mediaId)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceAtMost(maxOf(0, resolved.size - 1)), startPositionMs),
            )
        }
```

Add a private helper method to `PlaybackService` (outside the callback, e.g. below the callback class) that resolves a folder id to its children (suspend, called inside `future { }`):

```kotlin
    private suspend fun childrenFor(parentId: String): List<MediaItem> =
        when (val target = AuxenBrowseIds.parse(parentId)) {
            BrowseTarget.Root -> browseTree.rootChildren()
            BrowseTarget.Recent -> browseTree.trackList(Graph.library.recentlyPlayed(limit = 50))
            BrowseTarget.Favorites -> browseTree.trackList(Graph.library.favorites().first())
            BrowseTarget.Playlists -> browseTree.playlistCategory(Graph.library.playlists().first())
            BrowseTarget.Albums -> browseTree.albumCategory(Graph.library.favoriteAlbums().first())
            BrowseTarget.Artists -> browseTree.artistCategory(Graph.library.favoriteArtists().first())
            is BrowseTarget.Playlist -> browseTree.trackList(playlistTracksFor(target.id))
            is BrowseTarget.Album -> when (val f = browseFetcher.albumTracks(target.tidalId)) {
                is BrowseFetch.Ok -> browseTree.albumDetail(target.tidalId, f.tracks)
                BrowseFetch.Failed -> listOf(browseTree.errorNode())
            }
            is BrowseTarget.Artist -> when (val f = browseFetcher.artistTracks(target.tidalId)) {
                is BrowseFetch.Ok -> browseTree.artistDetail(target.tidalId, f.tracks)
                BrowseFetch.Failed -> listOf(browseTree.errorNode())
            }
            else -> listOf(browseTree.errorNode())
        }

    /** Local playlist tracks, importing a Tidal playlist's items on first open
     *  (mirrors PlayerViewModel.loadPlaylist). */
    private suspend fun playlistTracksFor(playlistId: Long): List<Track> {
        val local = runCatching { Graph.library.playlistTracks(playlistId) }.getOrDefault(emptyList())
        if (local.isNotEmpty()) return local
        val uuid = Graph.library.playlists().first().firstOrNull { it.id == playlistId }?.tidalPlaylistId
            ?: return local
        runCatching { Graph.tidalCollectionSync.importPlaylistTracks(playlistId, uuid) }
        return runCatching { Graph.library.playlistTracks(playlistId) }.getOrDefault(emptyList())
    }
```

- [ ] **Step 4: Add `expandParent` to `BrowseQueueResolver`** (needed by the "Play all" path in Step 3)

In `BrowseQueueResolver.kt`, add:
```kotlin
    /** The full remembered list for a browse folder, from the top; null if unknown. */
    fun expandParent(parentId: String): MediaItemsWithStartPosition? {
        val list = parentToItems[parentId] ?: return null
        if (list.isEmpty()) return null
        return MediaItemsWithStartPosition(list, 0, 0L)
    }
```

And add a test for it in `BrowseQueueResolverTest.kt`:
```kotlin
    @Test fun `expandParent returns the whole list from the top`() {
        val r = BrowseQueueResolver()
        r.remember("auxen://browse/album/111", listOf(leaf("TIDAL:1"), leaf("TIDAL:2")))
        val out = r.expandParent("auxen://browse/album/111")!!
        assertEquals(listOf("TIDAL:1", "TIDAL:2"), out.mediaItems.map { it.mediaId })
        assertEquals(0, out.startIndex)
        assertNull(r.expandParent("auxen://browse/album/999"))
    }
```

Note: the album/artist `Play all` path caches under the *detail* folder id (`auxen://browse/album/<id>`), and `albumDetail` prepends a non-playable? — no: the play-all node IS playable, so `onGetChildren`'s `playable` filter would include it. To keep "Play all" out of the remembered track queue, the filter in `onGetChildren` must exclude play-all ids. Update the `playable` line in Step 3's `onGetChildren` to:
```kotlin
            val playable = children.filter {
                it.mediaMetadata.isPlayable == true &&
                    AuxenBrowseIds.parse(it.mediaId).let { t ->
                        t !is BrowseTarget.PlayAllAlbum && t !is BrowseTarget.PlayAllArtist
                    }
            }
```

- [ ] **Step 5: Build and run the full unit suite**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL; all tests pass (Task 1 + Task 3 suites included, existing suite unchanged and green).

- [ ] **Step 6: Commit**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle.kts \
        android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt \
        android/app/src/main/kotlin/io/github/auxen/playback/browse/BrowseQueueResolver.kt \
        android/app/src/test/kotlin/io/github/auxen/playback/browse/BrowseQueueResolverTest.kt
git commit -m "feat(android): promote PlaybackService to MediaLibraryService; wire Android Auto browse tree"
```

---

## Task 5: Manifest — declare Android Auto media support

**Files:**
- Create: `android/app/src/main/res/xml/automotive_app_desc.xml`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:** none (configuration only). The `MediaBrowserService` intent-filter already exists on `PlaybackService`.

- [ ] **Step 1: Create `automotive_app_desc.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

- [ ] **Step 2: Add the meta-data inside `<application>`** in `AndroidManifest.xml` (sibling of the existing `<activity>`/`<service>` — place it directly under the `<application>` open tag):

```xml
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc"/>
```

- [ ] **Step 3: Build to verify the manifest merges**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/xml/automotive_app_desc.xml android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): declare Android Auto media support (automotive_app_desc + car meta-data)"
```

---

## Task 6 (optional): Content-style hints (grid for albums/artists)

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/browse/AuxenBrowseTree.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt` (`onGetLibraryRoot` params)

This is cosmetic (grid vs list rendering in the car). Implement only if Tasks 1–5 validate cleanly on-device; skip if it adds friction. Details deferred to implementation time — set the Android Auto content-style extras (`android.media.browse.CONTENT_STYLE_BROWSABLE_HINT` = grid on the Albums/Artists category nodes, list elsewhere) in the node `MediaMetadata` extras and the root `LibraryParams`. Not required for a functional v1.

---

## On-device validation (user-run; Claude cannot run DHU)

After Task 4/5 build green, deliver an APK stamped `0.1.0+<sha>` (verify via aapt2), then validate with the **Desktop Head Unit** or a real car:

1. About shows `0.1.0+<sha>` (confirm the build landed).
2. Connect Android Auto → Auxen appears → root shows: Recently played, Playlists, Albums, Artists, Favorite tracks.
3. Recently played / Favorite tracks list tracks; tapping one plays and next/prev continues down the list.
4. Playlists → a playlist → its tracks; tap plays from there.
5. Albums → an album → "▶ Play all" + tracks; Play all plays the album; tapping track 3 plays 3→end.
6. Artists → an artist → "▶ Play all" + tracks; same behavior.
7. Airplane mode, then open an album/artist → shows "Couldn't load — check connection", **no crash**.
8. Now-playing metadata, artwork, and transport controls behave correctly on the head unit; the phone UI and bit-perfect toggle are unaffected.

---

## Self-review notes

- **Spec coverage:** browse tree (§5) → Tasks 1+4; TidalBrowseFetcher (§4.2) → Task 2; service promotion (§4.3) → Task 4; leaf→queue expansion (§4.3) → Tasks 3+4; manifest (§4.4) → Task 5; error/empty nodes (§6) → Task 1 (nodes) + Task 4 (wiring); testing (§7) → Task 1/3 unit tests + on-device checklist; content style (§5) → Task 6 (optional).
- **Types consistent:** `BrowseFetch`, `BrowseTarget`, `MediaItemsWithStartPosition`, and the `AuxenBrowseIds`/`AuxenBrowseTree`/`BrowseQueueResolver` signatures match across Tasks 1–4.
- **No placeholders** except Task 6, which is explicitly optional/cosmetic and gated behind on-device validation.
