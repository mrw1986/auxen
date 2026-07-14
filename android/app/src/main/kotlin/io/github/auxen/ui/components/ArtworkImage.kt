package io.github.auxen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * The canonical album/track art slot — a Coil [AsyncImage] that never renders a
 * transparent hole. A null/loading/errored [model] leaves Coil's own painter
 * empty (fully transparent — confirmed by the pre-existing track-row goldens),
 * which used to expose whatever was behind the clip. Here that empty area is a
 * deliberate, filled placeholder instead: the [Modifier]'s shape is filled with
 * [androidx.compose.material3.ColorScheme.surfaceVariant] and centered on a
 * muted [glyph], so the same treatment serves as BOTH the while-loading state
 * and the missing/failed-art fallback.
 *
 * The image is drawn on top of that placeholder and covers it once decoded, so
 * no `placeholder`/`error`/`fallback` painters are needed — the Box behind is
 * the placeholder. The caller supplies the size and clip shape via [modifier]
 * (e.g. `Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))`); the clip in that
 * chain also clips both the fill and the loaded image to the rounded shape.
 *
 * @param model the Coil image model (a URL string, `Uri`, etc.), or null.
 * @param contentDescription forwarded to the [AsyncImage] for accessibility;
 *   pass null for decorative art (the glyph itself is always decorative).
 * @param modifier must carry the size and clip shape for the slot.
 * @param glyphSize size of the centered placeholder glyph — scale it to the
 *   slot (small for a mini-player thumb, larger for a card) so the placeholder
 *   reads as proportional rather than a fixed dot.
 * @param glyph the placeholder/fallback icon; defaults to a filled album disc.
 * @param contentScale how the loaded image fills the slot; [ContentScale.Crop]
 *   by default to fill square/rectangular slots without letterboxing.
 */
@Composable
fun ArtworkImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 24.dp,
    glyph: ImageVector = Icons.Filled.Album,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(glyphSize),
        )
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
