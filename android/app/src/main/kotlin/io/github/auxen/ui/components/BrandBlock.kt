package io.github.auxen.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.auxen.R
import io.github.auxen.ui.theme.AuxenColors
import io.github.auxen.ui.theme.Fraunces

/**
 * Desktop sidebar brand row (auxen/views/sidebar.py): theme-aware ox logo,
 * AUXEN title, UNORTHODOX AUDIO subtitle. [compact] drops the subtitle and
 * shrinks the logo for use in screen headers.
 */
@Composable
fun BrandBlock(compact: Boolean = false, modifier: Modifier = Modifier) {
    // Derived from the resolved color scheme background rather than
    // isSystemInDarkTheme() directly, so this always agrees with whatever
    // darkTheme AuxenTheme was actually given (including test overrides that
    // don't touch the system night-mode setting).
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val logo = if (dark) R.drawable.auxen_logo else R.drawable.auxen_logo_on_light
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        Image(
            painterResource(logo),
            contentDescription = null,
            modifier = Modifier.size(if (compact) 28.dp else 52.dp),
        )
        Column {
            Text(
                stringResource(R.string.brand_name),
                fontFamily = Fraunces,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 16.sp else 22.sp,
                letterSpacing = 1.sp,
                color = if (dark) AuxenColors.TextPrimary else AuxenColors.BrandTitleOnLight,
            )
            if (!compact) {
                Text(
                    stringResource(R.string.brand_tagline),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    color = if (dark) AuxenColors.BrandGold else AuxenColors.BrandSubtitleOnLight,
                )
            }
        }
    }
}
