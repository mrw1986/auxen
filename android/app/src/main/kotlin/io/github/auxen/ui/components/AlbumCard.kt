package io.github.auxen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 *
 * Fills its parent's width so it adapts to an adaptive [LazyVerticalGrid] cell
 * (art stays square via `aspectRatio(1f)`); horizontally-scrolling carousels
 * (`LazyRow`) MUST pass a bounded width from the call site (e.g.
 * `Modifier.width(150.dp)`) since a fill-width card has no width constraint
 * inside a LazyRow.
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
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            ArtworkImage(
                model = artUrl,
                contentDescription = title,
                glyphSize = 48.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp)),
            )
            if (source != null) {
                SourceBadge(source, modifier = Modifier.align(Alignment.TopStart).padding(6.dp), tinted = true)
            }
            if (onPlay != null) {
                // 48dp IconButton default keeps the touch target ≥48dp (a phone's
                // only way to start playback from a card) while the visible amber
                // circle stays 36dp inside it — minimumInteractiveComponentSize is
                // applied by IconButton since the size is no longer overridden.
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AuxenColors.AmberPrimary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play $title",
                            tint = AuxenColors.BgDeep,
                        )
                    }
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
