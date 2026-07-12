package io.github.auxen.matching

import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track

/**
 * Track matching and source-priority logic — Kotlin port of
 * `auxen/matching.py` from the desktop app.
 */

private val FEAT_PATTERN = Regex("""\b(?:feat\.?|featuring)\b""", RegexOption.IGNORE_CASE)
private val NON_ALNUM_PATTERN = Regex("[^a-z0-9 ]")
private val MULTI_SPACE_PATTERN = Regex(" {2,}")

/**
 * Normalize a string for fuzzy track matching: trim + lowercase, fold
 * "feat."/"featuring" to "ft", strip non-alphanumerics, collapse spaces.
 */
fun normalizeForMatching(text: String): String {
    var result = text.trim().lowercase()
    result = FEAT_PATTERN.replace(result, "ft")
    result = NON_ALNUM_PATTERN.replace(result, " ")
    result = MULTI_SPACE_PATTERN.replace(result, " ")
    return result.trim()
}

/**
 * Similarity score in 0..100, matching `thefuzz.fuzz.ratio` (the indel /
 * normalized-Levenshtein ratio): round(200 * LCS / (|a| + |b|)), where
 * "round" is Python's `round()` — round-half-to-even (banker's rounding),
 * not round-half-away-from-zero. E.g. a raw ratio of 12.5 rounds to 12,
 * not 13.
 */
fun fuzzRatio(a: String, b: String): Int {
    val lensum = a.length + b.length
    if (lensum == 0) return 100
    return Math.rint(200.0 * lcsLength(a, b) / lensum).toInt()
}

/** Longest-common-subsequence length, O(min) two-row DP. */
private fun lcsLength(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var prev = IntArray(b.length + 1)
    var curr = IntArray(b.length + 1)
    for (i in a.indices) {
        for (j in b.indices) {
            curr[j + 1] = if (a[i] == b[j]) prev[j] + 1 else maxOf(prev[j + 1], curr[j])
        }
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[b.length]
}

/**
 * True when two tracks are considered the same song: exact match after
 * normalization, else both title and artist fuzzy scores >= [threshold].
 */
fun tracksMatch(a: Track, b: Track, threshold: Int = 85): Boolean {
    val titleA = normalizeForMatching(a.title)
    val titleB = normalizeForMatching(b.title)
    val artistA = normalizeForMatching(a.artist)
    val artistB = normalizeForMatching(b.artist)

    if (titleA == titleB && artistA == artistB) return true

    return fuzzRatio(titleA, titleB) >= threshold && fuzzRatio(artistA, artistB) >= threshold
}

/**
 * Return the preferred of two duplicate tracks according to [priority].
 * ALWAYS_ASK returns the local track as a default; the caller is
 * responsible for prompting the user.
 */
fun pickPreferredTrack(trackA: Track, trackB: Track, priority: SourcePriority): Track = when (priority) {
    SourcePriority.PREFER_LOCAL -> if (trackA.isLocal) trackA else trackB
    SourcePriority.PREFER_TIDAL -> if (trackA.isTidal) trackA else trackB
    SourcePriority.PREFER_QUALITY -> if (trackB.qualityScore > trackA.qualityScore) trackB else trackA
    SourcePriority.ALWAYS_ASK -> if (trackA.isLocal) trackA else trackB
}
