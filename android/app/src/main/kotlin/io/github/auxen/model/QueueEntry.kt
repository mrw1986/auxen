package io.github.auxen.model

/**
 * One occurrence of a [Track] in the live playback queue, tagged with a
 * per-occurrence stable [id].
 *
 * The queue is the raw MediaController media-item list with no dedup — the
 * same track can appear more than once (a playlist with a duplicate, or
 * "Play next" on the same song twice). A track-identity key
 * (`SOURCE:sourceId`) therefore collides for duplicates, which crashes a
 * Compose `LazyColumn` and gives the drag-reorder animation no stable
 * identity to follow. [id] is a fresh UUID stamped into each media item's
 * `auxen.queueUid` metadata extra when it is enqueued
 * ([io.github.auxen.Graph.mediaItemFor]); `moveMediaItem` preserves the media
 * item (and its metadata) across reorders, so the id follows the item, and a
 * second enqueue of the same track gets a different id — no key collision,
 * ever. Built from a live player by [io.github.auxen.Graph.queueEntriesFrom].
 */
data class QueueEntry(val id: String, val track: Track)
