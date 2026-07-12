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

/** Source pill ("TIDAL" cyan / "LOCAL" green) — desktop make_source_badge. */
@Composable
fun SourceBadge(source: Source, modifier: Modifier = Modifier) {
    val color = if (source == Source.TIDAL) AuxenColors.TidalBlue else AuxenColors.LocalGreen
    Text(
        text = if (source == Source.TIDAL) "TIDAL" else "LOCAL",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Quality pill ("HI-RES", "FLAC", "MP3", ...) — desktop make_quality_badge. */
@Composable
fun QualityBadge(label: String, modifier: Modifier = Modifier) {
    if (label == "Unknown") return
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = AuxenColors.AmberPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AuxenColors.AmberPrimary.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
