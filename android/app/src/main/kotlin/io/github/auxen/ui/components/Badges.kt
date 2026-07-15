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
    // Amber-tinted pill. The wash is the theme's contrast-safe primary
    // (Amber600 in light, AmberPrimary in dark) at low alpha; the LABEL,
    // however, uses onPrimaryContainer, not the amber itself. An amber label on
    // the pale light wash failed WCAG at ~2.6:1 (Amber600 #B8860B on ~#F1E7CE);
    // onPrimaryContainer is a dark brown (#3D2E00) in light (~10.7:1) and a warm
    // cream (#F0ECE4) in dark (~11:1), so it clears 4.5:1 in BOTH themes while
    // the pill still reads as amber (polish P1, Fix 2).
    val wash = MaterialTheme.colorScheme.primary
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(wash.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
