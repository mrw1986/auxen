package io.github.auxen.ui.components

import io.github.auxen.model.Track

/**
 * Which entries the track long-press menu shows — the Android port of the
 * desktop's `TrackContextMenu._build_menu_model` (`auxen/views/context_menu.py:186-249`),
 * as PURE functions.
 *
 * Pure and free of Compose/Android on purpose: entry visibility is where the
 * conditionality bugs live, and the desktop proves it — its callback-presence
 * gates never fire (see the plan's *Desktop bugs we are not porting*).
 * Everything here is unit-tested; [TrackActionSheetContent] only renders the
 * list it is handed.
 *
 * **The `can…` flags mean "the host can service this action"** — real Booleans
 * passed from a host that knows whether it has a handler, not the desktop's
 * always-truthy `_noop`-lambda gates.
 */
enum class ActionId {
    PLAY,
    PLAY_NEXT,
    ENQUEUE,
    TOGGLE_FAVORITE,
    ADD_TO_PLAYLIST,
    GO_TO_ALBUM,
    GO_TO_ARTIST,
    SHOW_INFO,
}

/** One rendered menu row: a stable [id] for dispatch, a display [label]. */
data class ActionEntry(val id: ActionId, val label: String)

/**
 * The (album, albumArtist) pair a "Go to album" navigation must target.
 *
 * **This MUST reproduce `groupAlbums`' grouping key exactly**
 * (`data/LibraryGrouping.kt:66-70`): `AlbumDetailScreen` finds its group with
 * `groupAlbums(pool).firstOrNull { it.album == album && it.albumArtist == artist }`
 * (`AlbumDetailScreen.kt:85`). Pass `track.artist` where `groupAlbums` used
 * `track.albumArtist ?: track.artist` and the lookup silently finds nothing —
 * the user lands on an empty album screen. Hence `albumArtist ?: artist`.
 *
 * Divergence from `groupAlbums`, deliberately: it files a blank/absent album
 * under "Unknown Album"; this returns null. The desktop hides "Go to Album" for
 * a track with no album (`context_menu.py:228`) and so do we.
 */
data class AlbumTarget(val album: String, val albumArtist: String)

fun albumTargetFor(track: Track): AlbumTarget? {
    val album = track.album?.takeIf { it.isNotBlank() } ?: return null
    val albumArtist = track.albumArtist?.takeIf { it.isNotBlank() }
        ?: track.artist.takeIf { it.isNotBlank() }
        ?: return null
    return AlbumTarget(album, albumArtist)
}

/**
 * Track menu — desktop `TrackContextMenu` (`context_menu.py:186-249`).
 *
 * Order is load-bearing: the first five entries are what the `action-sheet-*`
 * goldens captured. The two nav entries slot between "Add to playlist" and
 * "Track info", mirroring the desktop's section order (playback → organize →
 * nav → extras).
 *
 * [canShowInfo] is NOT Tidal-gated (D1): the info sheet carries a Details
 * section read straight off this [track], so it is offered for a local track
 * too. Dropped vs. the desktop, for want of a backend: "Go to Track Radio" and
 * "Start Mix" (see the plan's *Deferred*).
 */
fun trackActions(
    track: Track,
    isFavorite: Boolean,
    canGoToAlbum: Boolean,
    canGoToArtist: Boolean,
    canShowInfo: Boolean,
): List<ActionEntry> = buildList {
    add(ActionEntry(ActionId.PLAY, "Play"))
    add(ActionEntry(ActionId.PLAY_NEXT, "Play next"))
    add(ActionEntry(ActionId.ENQUEUE, "Add to queue"))
    add(
        ActionEntry(
            ActionId.TOGGLE_FAVORITE,
            if (isFavorite) "Remove from favorites" else "Add to favorites",
        ),
    )
    add(ActionEntry(ActionId.ADD_TO_PLAYLIST, "Add to playlist"))
    if (canGoToAlbum && albumTargetFor(track) != null) {
        add(ActionEntry(ActionId.GO_TO_ALBUM, "Go to album"))
    }
    if (canGoToArtist && track.artist.isNotBlank()) {
        add(ActionEntry(ActionId.GO_TO_ARTIST, "Go to artist"))
    }
    if (canShowInfo) {
        add(ActionEntry(ActionId.SHOW_INFO, "Track info"))
    }
}
