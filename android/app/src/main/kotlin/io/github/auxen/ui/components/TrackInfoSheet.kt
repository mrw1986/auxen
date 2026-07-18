package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import io.github.auxen.model.Track
import io.github.auxen.provider.tidal.TidalTrackDetail

/**
 * Info sheet for a track, opened from [TrackActionSheet]'s "Track info" action.
 *
 * Carries three things:
 *  - **Credits** and **Lyrics** for a Tidal track, backed by the internal-v1
 *    `tracks/{id}/credits` and `tracks/{id}/lyrics` reads (assembled by
 *    `TidalProvider.trackDetail`) — metadata only; nothing here resolves a stream.
 *  - A **Details** section (format / bitrate / sample rate / bit depth / source)
 *    read straight off the [track] model — no fetch (D1). This is what makes the
 *    sheet worth opening on a LOCAL track, whose credits/lyrics block is skipped.
 *
 * [detail] is null both while [loading] and when the fetch failed / the track
 * has no document; the body tells those apart with [loading] alone, so a
 * failure settles on a notice rather than an endless spinner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoSheet(
    track: Track,
    detail: TidalTrackDetail?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        TrackInfoSheetContent(track = track, detail = detail, loading = loading)
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
internal fun TrackInfoSheetContent(track: Track, detail: TidalTrackDetail?, loading: Boolean) {
    Column(
        Modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            track.title.takeIf { it.isNotBlank() } ?: "Track info",
            style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Credits + lyrics are catalog-keyed (Tidal only). A LOCAL track has
        // nothing to fetch, so the whole block is skipped and the sheet shows
        // Details alone — which is exactly why "Track info" is now offered for
        // local tracks (D1).
        if (track.isTidal) {
            when {
                detail != null -> {
                    if (detail.credits.isNotEmpty()) {
                        Text("Credits", style = MaterialTheme.typography.titleSmall)
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
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("Lyrics", style = MaterialTheme.typography.titleSmall)
                    Text(
                        detail.lyricsText
                            ?: (if (detail.lyricsSynced != null) "Synced lyrics only." else "No lyrics available."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                loading -> LoadingState()
                else -> Text("No credits or lyrics available.")
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("Details", style = MaterialTheme.typography.titleSmall)
        DetailRow("Format", track.format)
        DetailRow("Bitrate", track.bitrateKbps?.let { "$it kbps" })
        DetailRow("Sample rate", track.sampleRateHz?.let { "%.1f kHz".format(it / 1000.0) })
        DetailRow("Bit depth", track.bitDepth?.let { "$it-bit" })
        DetailRow("Source", if (track.isTidal) "Tidal" else "Local")
    }
}

/** One label/value Details row; renders nothing when the value is absent/blank. */
@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
