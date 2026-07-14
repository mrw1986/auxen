package io.github.auxen.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.auxen.model.Source
import io.github.auxen.ui.theme.AuxenColors

/**
 * Album/mix card for grids and carousels — desktop album-card: square art,
 * always-visible play affordance (phones have no hover), source badge overlay.
 */
@Composable
fun AlbumCard(
    title: String,
    artist: String?,
    artUrl: String?,
    source: Source?,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(150.dp).clickable(onClick = onClick)) {
        Box {
            ArtworkImage(
                model = artUrl,
                contentDescription = title,
                glyphSize = 48.dp,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp)),
            )
            if (source != null) {
                SourceBadge(source, modifier = Modifier.align(Alignment.TopStart).padding(6.dp), tinted = true)
            }
            if (onPlay != null) {
                IconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = AuxenColors.AmberPrimary,
                        contentColor = AuxenColors.BgDeep,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(36.dp)
                        .clip(CircleShape),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play $title")
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (artist != null) {
            Text(
                artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
