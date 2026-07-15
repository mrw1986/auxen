package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The single, shared empty / no-results surface for the whole app.
 *
 * Every screen that can render an empty or filtered-empty list uses this so
 * there is exactly one visual language for "there's nothing here" — a muted
 * brand [icon], a [title] ([MaterialTheme.typography.titleMedium]), and an
 * optional [subtitle] ([bodyMedium], `onSurfaceVariant`), stacked and centered.
 * Modeled on the original hand-rolled empty state in `PlaylistDetailScreen`
 * (titleMedium + bodyMedium/onSurfaceVariant), lifted here and given a glyph +
 * centering so no surface is ever a blank void (polish P2).
 *
 * The centering assumes a bounded height: place it as a direct child of a
 * `fillMaxSize()` `Column` (or pass `Modifier.fillParentMaxSize()` from a
 * `LazyItemScope`) to center in the remaining space. Dropped into a plain
 * `LazyColumn`/`LazyVerticalGrid` item (unbounded height) it degrades
 * gracefully to a wrap-height block — still the same glyph + copy, just not
 * vertically centered.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
