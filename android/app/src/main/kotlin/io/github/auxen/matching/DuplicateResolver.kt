package io.github.auxen.matching

import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track

/**
 * Quality-aware duplicate resolution for merged local + Tidal result lists —
 * the Android analog of the desktop app's match-group handling.
 */
object DuplicateResolver {

    /**
     * Merge [local] and [tidal] results. Each local track pairs with the
     * first unconsumed Tidal track that [tracksMatch]es it; the pair
     * collapses to [pickPreferredTrack]'s winner, tagged with a
     * deterministic matchGroupId (the local track's "SOURCE:sourceId").
     * Unmatched tracks pass through unchanged, local first.
     */
    fun merge(local: List<Track>, tidal: List<Track>, priority: SourcePriority): List<Track> {
        val remaining = tidal.toMutableList()
        val merged = mutableListOf<Track>()
        for (localTrack in local) {
            val matchIndex = remaining.indexOfFirst { tracksMatch(localTrack, it) }
            if (matchIndex == -1) {
                merged += localTrack
                continue
            }
            val tidalTrack = remaining.removeAt(matchIndex)
            val groupId = "${localTrack.source.name}:${localTrack.sourceId}"
            merged += pickPreferredTrack(localTrack, tidalTrack, priority).copy(matchGroupId = groupId)
        }
        merged += remaining
        return merged
    }
}
