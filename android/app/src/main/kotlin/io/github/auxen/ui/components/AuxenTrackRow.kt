package io.github.auxen.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.theme.AuxenColors

/** "3:47" from seconds; "–:––" when unknown. */
fun formatDuration(seconds: Double?): String {
    if (seconds == null || seconds <= 0) return "–:––"
    val total = seconds.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * The canonical track row — Android port of the desktop
 * `make_standard_track_row`: [Art][Title(+E) / Subtitle(+Quality)][Duration][Heart][trailing].
 * The source pill is intentionally omitted (it is redundant on a Tidal-filtered
 * list and available in the track action sheet); the quality pill rides on the
 * subtitle line so the title column gets the full freed width on phone widths.
 * Long-press opens the track action sheet (wired by callers).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AuxenTrackRow(
    track: Track,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    trackNumber: Int? = null,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onPlay,
                // Both are gated on the SAME null check as onLongClick, so a
                // host that offers no sheet advertises no long-press action and
                // buzzes for nothing. Foundation 1.7.6's combinedClickable does
                // NOT self-haptic — this call is required and does not double-buzz.
                onLongClickLabel = onLongPress?.let { "Show track actions" },
                onLongClick = onLongPress?.let { handler ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        handler()
                    }
                },
            )
            .background(if (isPlaying) AuxenColors.AmberPrimary.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // On an album page every row shares one cover, so a right-aligned track
        // number is more useful than N identical thumbnails. When [trackNumber]
        // is null (every non-album caller) the leading slot is the art, byte-for-
        // byte as before — the number path is purely additive.
        if (trackNumber != null) {
            Text(
                trackNumber.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(32.dp),
            )
        } else {
            ArtworkImage(
                model = track.albumArtUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    // Text tint -- resolves to the contrast-safe primary (final-review fix
                    // round, Minor #2); the 0.08-alpha row-highlight background above is a
                    // wash, not text, and keeps the raw brand amber.
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (track.explicit) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "E",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(track.artist, track.album).joinToString(" — "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The subtitle text absorbs the ellipsis so the fixed-size
                    // quality chip after it stays fully visible even when
                    // "artist — album" is long (fill = false keeps it hugging
                    // its content, leaving room for the chip).
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Quality moved off the title row (was [Duration][Source][Quality]
                // after the title). Still Tidal-only, matching the prior inline
                // gate and the AuxenTrackRowUiTest contract; the != "Unknown"
                // guard avoids a dangling spacer when QualityBadge self-suppresses.
                if (track.source == Source.TIDAL && track.qualityLabel != "Unknown") {
                    Spacer(Modifier.width(6.dp))
                    QualityBadge(track.qualityLabel)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(track.durationSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) AuxenColors.FavoriteRed else LocalContentColor.current,
            )
        }
        trailing?.invoke(this)
    }
}
