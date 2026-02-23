"""Track matching and source-priority logic for the Auxen music player."""

from __future__ import annotations

import re

from auxen.models import Source, SourcePriority, Track

# Pre-compiled patterns for normalization.
_FEAT_PATTERN = re.compile(r"\b(?:feat\.?|featuring)\b", re.IGNORECASE)
_NON_ALNUM_PATTERN = re.compile(r"[^a-z0-9 ]")
_MULTI_SPACE_PATTERN = re.compile(r" {2,}")


def normalize_for_matching(text: str) -> str:
    """Normalize a string for fuzzy track matching.

    Steps:
        1. Strip leading/trailing whitespace and lowercase.
        2. Normalize "feat." / "featuring" variations to "ft".
        3. Remove non-alphanumeric characters (except spaces).
        4. Collapse multiple spaces to a single space.
    """
    result = text.strip().lower()
    result = _FEAT_PATTERN.sub("ft", result)
    result = _NON_ALNUM_PATTERN.sub(" ", result)
    result = _MULTI_SPACE_PATTERN.sub(" ", result)
    return result.strip()


def tracks_match(a: Track, b: Track, threshold: int = 85) -> bool:
    """Return True when two tracks are considered the same song.

    First checks for an exact match after normalization.  If that fails,
    uses fuzzy comparison via ``thefuzz.fuzz.ratio`` where *both* title
    and artist must score >= *threshold*.

    Gracefully returns ``False`` if ``thefuzz`` is not installed.
    """
    norm_title_a = normalize_for_matching(a.title)
    norm_title_b = normalize_for_matching(b.title)
    norm_artist_a = normalize_for_matching(a.artist)
    norm_artist_b = normalize_for_matching(b.artist)

    # Fast path: exact match after normalization.
    if norm_title_a == norm_title_b and norm_artist_a == norm_artist_b:
        return True

    # Fuzzy path.
    try:
        from thefuzz import fuzz  # noqa: WPS433
    except ImportError:
        return False

    title_score = fuzz.ratio(norm_title_a, norm_title_b)
    artist_score = fuzz.ratio(norm_artist_a, norm_artist_b)
    return title_score >= threshold and artist_score >= threshold


def pick_preferred_track(
    track_a: Track,
    track_b: Track,
    priority: SourcePriority,
) -> Track:
    """Return the preferred track according to *priority*.

    * ``PREFER_LOCAL``  -- return whichever track is local.
    * ``PREFER_TIDAL``  -- return whichever track is from Tidal.
    * ``PREFER_QUALITY`` -- return the track with the higher
      ``quality_score``.  On a tie, return *track_a*.
    * ``ALWAYS_ASK``    -- return the local track as a default (the
      caller is responsible for showing a prompt to the user).
    """
    if priority == SourcePriority.PREFER_LOCAL:
        if track_a.is_local:
            return track_a
        return track_b

    if priority == SourcePriority.PREFER_TIDAL:
        if track_a.is_tidal:
            return track_a
        return track_b

    if priority == SourcePriority.PREFER_QUALITY:
        if track_b.quality_score > track_a.quality_score:
            return track_b
        return track_a

    # ALWAYS_ASK: return the local track as default.
    if track_a.is_local:
        return track_a
    return track_b
