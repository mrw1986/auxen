package io.github.auxen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.auxen.model.Source
import io.github.auxen.ui.theme.AuxenColors

/**
 * Source pill ("TIDAL" cyan / "LOCAL" green) — desktop make_source_badge.
 * [tinted] false (default, track rows / NowPlaying / AlbumDetail): solid
 * pill matching desktop's `.source-badge-tidal`/`.source-badge-local`. true
 * (AlbumCard's art overlay only): 15%-tint chip matching the mockup's
 * `.source-tidal`/`.source-local` art-overlay treatment.
 */
@Composable
fun SourceBadge(source: Source, modifier: Modifier = Modifier, tinted: Boolean = false) {
    val color = if (source == Source.TIDAL) AuxenColors.TidalBlue else AuxenColors.LocalGreen
    Text(
        text = if (source == Source.TIDAL) "TIDAL" else "LOCAL",
        style = MaterialTheme.typography.labelSmall,
        color = if (tinted) color else AuxenColors.BgDeep,
        modifier = modifier
            .clip(if (tinted) RoundedCornerShape(6.dp) else RoundedCornerShape(50))
            .background(if (tinted) color.copy(alpha = 0.15f) else color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Quality pill ("HI-RES", "FLAC", "MP3", ...) — desktop make_quality_badge. */
@Composable
fun QualityBadge(label: String, modifier: Modifier = Modifier) {
    if (label == "Unknown") return
    // Text/tint on a light-alpha wash over the surface -- resolves to the
    // theme's contrast-safe primary (Amber600 in light, AmberPrimary in
    // dark), not the raw brand amber (final-review fix round, Minor #2).
    val color = MaterialTheme.colorScheme.primary
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
