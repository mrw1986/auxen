package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The single, shared loading surface — a centered [CircularProgressIndicator]
 * shown while a browse surface's async load is in flight, so its [EmptyState]
 * never flashes "nothing here" before the data resolves (polish C2).
 *
 * Mirrors [EmptyState]'s `fillMaxSize()` centering: placed as a direct child of
 * a `fillMaxSize()` `Column` it centers in the remaining space; dropped into an
 * unbounded `LazyColumn`/`LazyVerticalGrid` item it degrades gracefully to a
 * wrap-height, horizontally-centered block — same as [EmptyState].
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
