package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.auxen.provider.tidal.TidalTrackDetail

/**
 * Credits + lyrics for a Tidal track, opened from [TrackActionSheet]'s
 * "Credits & lyrics" action. Backed by the OFFICIAL metadata API's
 * `trackDetail` projection — metadata only; nothing here resolves a stream.
 *
 * [detail] is null both while [loading] and when the fetch failed / the track
 * has no document; the body tells those apart with [loading] alone, so a
 * failure settles on a notice rather than an endless spinner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoSheet(
    detail: TidalTrackDetail?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        TrackInfoSheetContent(detail = detail, loading = loading)
    }
}

/**
 * Stateless body of [TrackInfoSheet].
 *
 * Split out for the same reason [TrackActionSheetContent] is: the Material 3
 * [ModalBottomSheet] wrapper hosts its content in a *separate* window whose
 * animated entrance never settles under Robolectric, so the sheet itself cannot
 * be asserted on (or captured) deterministically. This plain [Column] renders in
 * the main composition — see `TrackInfoSheetUiTest`.
 *
 * Renders nothing for an empty section rather than a placeholder: a track with
 * no credits shows lyrics alone, matching the artist screen's treatment of a
 * null biography.
 */
@Composable
internal fun TrackInfoSheetContent(detail: TidalTrackDetail?, loading: Boolean) {
    Column(
        Modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // `?.takeIf { it.isNotBlank() }`, not a bare elvis: TidalTrackDetail.title
        // is a non-null String projected with `.orEmpty()`
        // (TidalMetadataDetail.kt:354), so a document whose title attribute is
        // absent/blank resolves to "" and a bare elvis would never fire — leaving
        // an EMPTY header instead of the placeholder. Same trap, same fix as the
        // album header (AlbumDetailScreen.kt:111).
        Text(detail?.title?.takeIf { it.isNotBlank() } ?: "Track info", style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (detail == null) {
            if (loading) LoadingState() else Text("No details available.")
        } else {
            if (detail.credits.isNotEmpty()) {
                Text("Credits", style = MaterialTheme.typography.titleSmall)
                // Already grouped by role upstream (trackDetailFrom folds
                // "Producer: Alice" + "Producer: Bob" into one entry), so this
                // only prints the role and joins its names.
                detail.credits.forEach { credit ->
                    Text(
                        credit.role,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        credit.names.joinToString(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                // Inside the block, not after it: this Spacer separates Credits
                // FROM Lyrics, so a track with no credits must not pay for it —
                // otherwise its "Lyrics" header sits 12dp lower than the divider
                // intends, for no reason a reader could see.
                Spacer(Modifier.height(12.dp))
            }
            Text("Lyrics", style = MaterialTheme.typography.titleSmall)
            Text(
                // Plain text when there is any; otherwise say which of the two
                // "no lyrics here" cases this is. Auxen has no synced-lyrics
                // surface yet, so lrcText-only is reported, not rendered raw.
                detail.lyricsText ?: (if (detail.lyricsSynced != null) "Synced lyrics only." else "No lyrics available."),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
