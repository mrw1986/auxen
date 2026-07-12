# Auxen Android — Milestone 3a: UI Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Android app's look and player chrome to desktop parity: the Auxen amber design system (colors + Fraunces/DM Sans type), the canonical shared track row and badges, a long-press track action sheet, a navigation shell (Home/Library/Search/Collection bottom nav + routed detail screens), a real mini-player bar, and a full-screen Now Playing view.

**Architecture:** A `ui/theme` rewrite encodes the desktop design tokens (see `.superpowers/sdd/m3-ui-inventory.md` — the authoritative design inventory distilled from `ui-mockup.html` and `auxen/views/`). Shared composables live in a new `ui/components/` package; `MainActivity` moves from a `when(selectedTab)` switch to `navigation-compose` routes so detail screens (Now Playing now; album/artist/playlist in M3b) can be pushed. `PlayerViewModel` grows the player-state surface (position polling, shuffle/repeat, current Track decode) the new chrome binds to.

**Tech Stack:** Jetpack Compose (BOM 2024.12.01, Material 3), navigation-compose 2.8.5 (already a dependency), Media3 1.5.1 MediaController, Room (existing), bundled variable fonts (DM Sans, Fraunces — OFL licensed).

## Global Constraints

- All Gradle commands run from `/home/mrw1986/Projects/auxen/android` and MUST be prefixed with `JAVA_HOME=~/.jdks/jdk-21.0.11+10`.
- Design tokens are LAW (from `.superpowers/sdd/m3-ui-inventory.md`): primary amber `#D4A039`; dark backgrounds `#0C0B0F` (deep) / `#141318` (surface) / `#1C1B22` (elevated) / `#25242D` (hover) / `#2E2D38` (active); text `#F0ECE4` / `#9E9A91` / `#6B6860`; Tidal cyan `#00C4CC`; Local green `#7CB87A`; favorite red `#C75C5C`; radius 6dp badges / 10dp art / 16dp cards.
- Typography: Fraunces (serif) for display/headline/section-title roles; DM Sans for everything else. Both light AND dark themes ship; no Material dynamic color.
- mediaId format `"${source.name}:${sourceId}"`; Track JSON lives in MediaMetadata extras under `Graph.TRACK_EXTRA_KEY`.
- The existing 51 unit tests must stay green; every task ends with `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL.
- Code style: KDoc on public composables/functions, 4-space indent, trailing commas.
- Commit messages: conventional commits with a blank line then the implementer's co-author trailer (given per task dispatch).
- Compose experimental APIs used deliberately: `basicMarquee` (foundation), `combinedClickable` (foundation), `ModalBottomSheet` (M3). Annotate with `@OptIn` as needed.

---

### Task 1: Amber theme + typography

**Files:**
- Create: `android/app/src/main/res/font/dm_sans.ttf` (downloaded)
- Create: `android/app/src/main/res/font/fraunces.ttf` (downloaded)
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/theme/Color.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/theme/Type.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/theme/Theme.kt` (full replace)

**Interfaces:**
- Consumes: nothing new.
- Produces (used by every later task): `AuxenTheme(content)` composable; `AuxenColors` object exposing `AmberPrimary`, `Amber400`, `Amber600`, `BgDeep`, `BgSurface`, `BgElevated`, `BgHover`, `BgActive`, `TextPrimary`, `TextSecondary`, `TextTertiary`, `TidalBlue`, `LocalGreen`, `FavoriteRed` (all `androidx.compose.ui.graphics.Color`); `Fraunces` and `DmSans` `FontFamily` values; `AuxenTypography: Typography`.

- [ ] **Step 1: Download the fonts**

```bash
cd /home/mrw1986/Projects/auxen/android/app/src/main/res
mkdir -p font
curl -fL -o font/dm_sans.ttf "https://github.com/google/fonts/raw/main/ofl/dmsans/DMSans%5Bopsz%2Cwght%5D.ttf"
curl -fL -o font/fraunces.ttf "https://github.com/google/fonts/raw/main/ofl/fraunces/Fraunces%5BSOFT%2CWONK%2Copsz%2Cwght%5D.ttf"
ls -la font/   # both files must be > 100 KB
```

If the URLs 404 (upstream renamed files), find the current filenames at https://github.com/google/fonts/tree/main/ofl/dmsans and .../fraunces and adjust. If download is impossible, report BLOCKED — do not substitute other fonts.

- [ ] **Step 2: Write Color.kt**

```kotlin
package io.github.auxen.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Auxen design tokens — ported from the desktop app's ui-mockup.html /
 * data/style.css palette (amber brand on near-black warm surfaces).
 */
object AuxenColors {
    val AmberPrimary = Color(0xFFD4A039)
    val Amber400 = Color(0xFFFFCA28)
    val Amber600 = Color(0xFFB8860B)

    val BgDeep = Color(0xFF0C0B0F)
    val BgSurface = Color(0xFF141318)
    val BgElevated = Color(0xFF1C1B22)
    val BgHover = Color(0xFF25242D)
    val BgActive = Color(0xFF2E2D38)

    val TextPrimary = Color(0xFFF0ECE4)
    val TextSecondary = Color(0xFF9E9A91)
    val TextTertiary = Color(0xFF6B6860)

    val TidalBlue = Color(0xFF00C4CC)
    val LocalGreen = Color(0xFF7CB87A)
    val FavoriteRed = Color(0xFFC75C5C)
}
```

- [ ] **Step 3: Write Type.kt**

```kotlin
package io.github.auxen.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.auxen.R

private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** DM Sans — body/UI font, per the desktop design. */
val DmSans = FontFamily(
    variable(R.font.dm_sans, FontWeight.Normal),
    variable(R.font.dm_sans, FontWeight.Medium),
    variable(R.font.dm_sans, FontWeight.SemiBold),
    variable(R.font.dm_sans, FontWeight.Bold),
)

/** Fraunces — display/brand serif, per the desktop design. */
val Fraunces = FontFamily(
    variable(R.font.fraunces, FontWeight.Light),
    variable(R.font.fraunces, FontWeight.Medium),
    variable(R.font.fraunces, FontWeight.SemiBold),
)

/**
 * Material type scale mapped to the desktop mockup's sizes: Fraunces for
 * greeting/brand/section titles, DM Sans for rows, labels, and badges.
 */
val AuxenTypography = Typography(
    displaySmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.5.sp),
)
```

Note: if the `variationSettings` `Font` overload does not resolve against the
Compose BOM in use, fall back to `Font(resId = resId, weight = weight)` for
all entries and say so in your report — do not silently drop the weights.

- [ ] **Step 4: Replace Theme.kt**

The existing `Theme.kt` uses stock dynamic color schemes. Replace its entire contents with:

```kotlin
package io.github.auxen.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AuxenDarkColors = darkColorScheme(
    primary = AuxenColors.AmberPrimary,
    onPrimary = AuxenColors.BgDeep,
    primaryContainer = AuxenColors.BgActive,
    onPrimaryContainer = AuxenColors.TextPrimary,
    secondary = AuxenColors.TidalBlue,
    onSecondary = AuxenColors.BgDeep,
    tertiary = AuxenColors.LocalGreen,
    onTertiary = AuxenColors.BgDeep,
    background = AuxenColors.BgDeep,
    onBackground = AuxenColors.TextPrimary,
    surface = AuxenColors.BgSurface,
    onSurface = AuxenColors.TextPrimary,
    surfaceVariant = AuxenColors.BgElevated,
    onSurfaceVariant = AuxenColors.TextSecondary,
    surfaceContainer = AuxenColors.BgElevated,
    surfaceContainerHigh = AuxenColors.BgHover,
    surfaceContainerHighest = AuxenColors.BgActive,
    error = Color(0xFFE74C3C),
    onError = Color.White,
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0FFFFFFF),
)

private val AuxenLightColors = lightColorScheme(
    primary = AuxenColors.Amber600,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5E6C4),
    onPrimaryContainer = Color(0xFF3D2E00),
    secondary = Color(0xFF00747A),
    onSecondary = Color.White,
    tertiary = Color(0xFF4C7A4A),
    onTertiary = Color.White,
    background = Color(0xFFFAF8F2),
    onBackground = Color(0xFF1C1B16),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B16),
    surfaceVariant = Color(0xFFF1EDE3),
    onSurfaceVariant = Color(0xFF6B6860),
    error = Color(0xFFC0392B),
    onError = Color.White,
    outline = Color(0x1F000000),
    outlineVariant = Color(0x14000000),
)

/**
 * Auxen brand theme — amber accent on warm near-black (dark) or warm
 * off-white (light). Deliberately NOT Material You dynamic color: brand
 * identity matches the desktop app.
 */
@Composable
fun AuxenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AuxenDarkColors else AuxenLightColors,
        typography = AuxenTypography,
        content = content,
    )
}
```

If the old `Theme.kt` declared a different composable name that call sites use (check `MainActivity.kt` — it calls `io.github.auxen.ui.theme.AuxenTheme`), keep the name `AuxenTheme`.

- [ ] **Step 5: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 51 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/res/font/ android/app/src/main/kotlin/io/github/auxen/ui/theme/
git commit -m "feat(android): Auxen amber theme + Fraunces/DM Sans typography"
```

---

### Task 2: Shared components — badges, canonical track row, album card, section header

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/Badges.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/AuxenTrackRow.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/SectionHeader.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/Screens.kt` (delete the private `TrackRow`; switch `LibraryScreen`/`SearchScreen`/`FavoritesScreen` lists to `AuxenTrackRow`)

**Interfaces:**
- Consumes: `AuxenColors`, `AuxenTypography` (Task 1); `Track`/`Source` model; existing `PlayerViewModel` (`play`, `enqueue`, `toggleFavorite`, `favoriteKeys`).
- Produces (used by Tasks 3–6 and all of M3b):
  - `SourceBadge(source: Source)` composable
  - `QualityBadge(label: String)` composable (renders nothing when label == "Unknown")
  - `formatDuration(seconds: Double?): String` (`"3:47"`, `"–:––"` when null)
  - `AuxenTrackRow(track: Track, isFavorite: Boolean, onPlay: () -> Unit, onToggleFavorite: () -> Unit, modifier: Modifier = Modifier, isPlaying: Boolean = false, onLongPress: (() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null)`
  - `AlbumCard(title: String, artist: String?, artUrl: String?, source: Source?, onClick: () -> Unit, onPlay: (() -> Unit)? = null, modifier: Modifier = Modifier)`
  - `SectionHeader(title: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null)`

- [ ] **Step 1: Write Badges.kt**

```kotlin
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
```

- [ ] **Step 2: Write AuxenTrackRow.kt**

```kotlin
package io.github.auxen.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.auxen.model.Track
import io.github.auxen.ui.theme.AuxenColors

/** "3:47" from seconds; "–:––" when unknown. */
fun formatDuration(seconds: Double?): String {
    if (seconds == null || seconds <= 0) return "–:––"
    val total = seconds.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * The canonical track row — Android port of the desktop
 * `make_standard_track_row`: [Art][Title(+E)+Subtitle][Duration][Source][Quality][Heart][trailing].
 * Long-press opens the track action sheet (wired by callers).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AuxenTrackRow(
    track: Track,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onLongPress)
            .background(if (isPlaying) AuxenColors.AmberPrimary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.albumArtUrl,
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isPlaying) AuxenColors.AmberPrimary else LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (track.explicit) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "E",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" — "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(track.durationSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        SourceBadge(track.source)
        if (track.source == io.github.auxen.model.Source.TIDAL) {
            Spacer(Modifier.width(4.dp))
            QualityBadge(track.qualityLabel)
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) AuxenColors.FavoriteRed else LocalContentColor.current,
            )
        }
        trailing?.invoke(this)
    }
}
```

(Use a proper `import io.github.auxen.model.Source` and `import androidx.compose.ui.graphics.Color` instead of the inline qualified names.)

- [ ] **Step 3: Write AlbumCard.kt and SectionHeader.kt**

`AlbumCard.kt`:

```kotlin
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
            AsyncImage(
                model = artUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp)),
            )
            if (source != null) {
                SourceBadge(source, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
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
```

`SectionHeader.kt`:

```kotlin
package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.auxen.ui.theme.AuxenColors

/** Fraunces section title + optional amber action link — desktop section-header. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = AuxenColors.AmberPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
```

- [ ] **Step 4: Switch Screens.kt to AuxenTrackRow**

In `Screens.kt`: delete the private `TrackRow` composable entirely. Update the three list call sites (`LibraryScreen`, `SearchScreen`, `FavoritesScreen`) to:

```kotlin
                AuxenTrackRow(
                    track = track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    trailing = {
                        IconButton(onClick = { viewModel.enqueue(track) }) {
                            Icon(Icons.Filled.PlaylistAdd, contentDescription = "Add to queue")
                        }
                    },
                )
```

Add `import io.github.auxen.ui.components.AuxenTrackRow` and remove now-unused imports (AssistChip, etc.). Keep everything else in the file unchanged.

- [ ] **Step 5: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 51 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ui/components/ android/app/src/main/kotlin/io/github/auxen/ui/Screens.kt
git commit -m "feat(android): shared UI components — canonical track row, badges, album card"
```

---

### Task 3: Track action sheet + playlist plumbing

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/Screens.kt` (wire long-press on all three screens)
- Test: `android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt` (extend)

**Interfaces:**
- Consumes: `PlaylistDao.insert/playlists()/appendTrack` (Task 2 of M2); `AuxenTrackRow.onLongPress` (Task 2).
- Produces:
  - `LibraryRepository.playlists(): Flow<List<PlaylistEntity>>`
  - `LibraryRepository.createPlaylist(name: String, color: String = "#d4a039"): Long` (suspend)
  - `LibraryRepository.addTrackToPlaylist(track: Track, playlistId: Long)` (suspend)
  - `PlayerViewModel.playNext(track: Track)`, `PlayerViewModel.playlists: StateFlow<List<PlaylistEntity>>`, `PlayerViewModel.addToPlaylist(track: Track, playlistId: Long)`, `PlayerViewModel.createPlaylistAndAdd(track: Track, name: String)`
  - `TrackActionSheet(track: Track, isFavorite: Boolean, playlists: List<PlaylistEntity>, onDismiss: () -> Unit, onPlay: () -> Unit, onPlayNext: () -> Unit, onEnqueue: () -> Unit, onToggleFavorite: () -> Unit, onAddToPlaylist: (Long) -> Unit, onCreatePlaylist: (String) -> Unit)` composable

- [ ] **Step 1: Write the failing repository tests**

Append to `LibraryRepositoryTest.kt`:

```kotlin
    @Test
    fun createPlaylistAndAddTrackRoundTrips() = runBlocking {
        val playlistId = repo.createPlaylist("Road Trip")
        repo.addTrackToPlaylist(tidalTrack, playlistId)
        repo.addTrackToPlaylist(tidalTrack.copy(sourceId = "100", title = "Walk"), playlistId)

        val names = repo.playlists().first().map { it.name }
        assertEquals(listOf("Road Trip"), names)
        assertEquals(
            listOf("Everlong", "Walk"),
            db.playlistDao().tracksIn(playlistId).map { it.title },
        )
    }

    @Test
    fun createPlaylistUsesDefaultAmberColor() = runBlocking {
        repo.createPlaylist("Mix")
        assertEquals("#d4a039", repo.playlists().first().single().color)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.data.*"`
Expected: FAIL to compile (`unresolved reference: createPlaylist`).

- [ ] **Step 3: Add the repository methods**

In `LibraryRepository.kt` (imports: `io.github.auxen.db.PlaylistEntity`):

```kotlin
    fun playlists(): Flow<List<PlaylistEntity>> = db.playlistDao().playlists()

    /** Create a playlist with the desktop default amber color; returns its id. */
    suspend fun createPlaylist(name: String, color: String = "#d4a039"): Long =
        db.playlistDao().insert(PlaylistEntity(name = name, color = color))

    suspend fun addTrackToPlaylist(track: Track, playlistId: Long) {
        val trackId = upsert(track)
        db.playlistDao().appendTrack(playlistId, trackId)
    }
```

- [ ] **Step 4: Run to verify pass**

Same command as Step 2. Expected: PASS (6 tests in the class).

- [ ] **Step 5: Extend PlayerViewModel**

Add (imports: `io.github.auxen.db.PlaylistEntity`):

```kotlin
    val playlists: StateFlow<List<PlaylistEntity>> = Graph.library.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Insert after the current queue item — desktop "Play Next". */
    fun playNext(track: Track) {
        viewModelScope.launch {
            runCatching { Graph.library.upsert(track) }
            val c = controller ?: return@launch
            val item = runCatching { Graph.mediaItemFor(track) }.getOrNull() ?: return@launch
            val index = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
            c.addMediaItem(index, item)
        }
    }

    fun addToPlaylist(track: Track, playlistId: Long) {
        viewModelScope.launch { runCatching { Graph.library.addTrackToPlaylist(track, playlistId) } }
    }

    fun createPlaylistAndAdd(track: Track, name: String) {
        viewModelScope.launch {
            runCatching {
                val id = Graph.library.createPlaylist(name)
                Graph.library.addTrackToPlaylist(track, id)
            }
        }
    }
```

- [ ] **Step 6: Write TrackActionSheet.kt**

```kotlin
package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.model.Track
import io.github.auxen.ui.theme.AuxenColors

/**
 * Long-press action sheet — the mobile substitute for the desktop
 * TrackContextMenu (Play / Play Next / Add to Queue / Favorite /
 * Add to Playlist / New Playlist).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionSheet(
    track: Track,
    isFavorite: Boolean,
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var showPlaylists by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (!showPlaylists) {
                SheetAction("Play", { Icon(Icons.Filled.PlayArrow, null) }) { onPlay(); onDismiss() }
                SheetAction("Play next", { Icon(Icons.Filled.SkipNext, null) }) { onPlayNext(); onDismiss() }
                SheetAction("Add to queue", { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue(); onDismiss() }
                SheetAction(
                    if (isFavorite) "Remove from favorites" else "Add to favorites",
                    {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            null,
                            tint = if (isFavorite) AuxenColors.FavoriteRed else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                ) { onToggleFavorite(); onDismiss() }
                SheetAction("Add to playlist", { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }) { showPlaylists = true }
            } else {
                SheetAction("New playlist…", { Icon(Icons.Filled.Add, null) }) { showNameDialog = true }
                playlists.forEach { playlist ->
                    SheetAction(
                        playlist.name,
                        {
                            Box(
                                Modifier.size(12.dp).background(
                                    runCatching { Color(android.graphics.Color.parseColor(playlist.color ?: "#d4a039")) }
                                        .getOrDefault(AuxenColors.AmberPrimary),
                                    CircleShape,
                                ),
                            )
                        },
                    ) { onAddToPlaylist(playlist.id); onDismiss() }
                }
            }
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreatePlaylist(newName.trim())
                            showNameDialog = false
                            onDismiss()
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetAction(label: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = leading,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clickable(onClick = onClick),
    )
}
```

(Additional import for the file: `androidx.compose.foundation.clickable`.)

- [ ] **Step 7: Wire long-press in Screens.kt**

In each of `LibraryScreen`, `SearchScreen`, `FavoritesScreen`, hoist sheet state above the list and pass `onLongPress`:

```kotlin
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    val playlists by viewModel.playlists.collectAsState()
```

on each row: `onLongPress = { sheetTrack = track },`

after the list (inside the same composable, at the end):

```kotlin
    sheetTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
            playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { viewModel.play(track) },
            onPlayNext = { viewModel.playNext(track) },
            onEnqueue = { viewModel.enqueue(track) },
            onToggleFavorite = { viewModel.toggleFavorite(track) },
            onAddToPlaylist = { viewModel.addToPlaylist(track, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(track, it) },
        )
    }
```

Imports: `io.github.auxen.ui.components.TrackActionSheet`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.setValue`.

- [ ] **Step 8: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 53 tests (51 + 2 new), 0 failures.

- [ ] **Step 9: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/Screens.kt \
        android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt
git commit -m "feat(android): track action sheet with play-next and playlist support"
```

---

### Task 4: Navigation shell — bottom nav + routes

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (full restructure of `MainScreen`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt` (one method)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (recentlyPlayed state)
- Test: `android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt` (extend)

**Interfaces:**
- Consumes: existing screens; `PlayHistoryDao.recentlyPlayed(limit)` (M2 Task 2); `TrackEntity.toTrack()`.
- Produces (Tasks 5/6 + M3b rely on these):
  - Routes: `"home"`, `"library"`, `"search"`, `"collection"`, `"equalizer"`, `"account"`, `"nowplaying"` in a `NavHost` owned by `MainScreen`
  - `HomeScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier)` (M3a stub version: greeting + Recently Played list; M3b replaces internals, keeps signature)
  - `LibraryRepository.recentlyPlayed(limit: Int = 20): List<Track>` (suspend)
  - `PlayerViewModel.recentlyPlayed: StateFlow<List<Track>>` + `fun refreshRecentlyPlayed()`

- [ ] **Step 1: Write the failing repository test**

Append to `LibraryRepositoryTest.kt`:

```kotlin
    @Test
    fun recentlyPlayedMapsHistoryToTracks() = runBlocking {
        repo.upsert(tidalTrack)
        repo.recordPlay("TIDAL:99")
        assertEquals(listOf("Everlong"), repo.recentlyPlayed().map { it.title })
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.data.*"`
Expected: FAIL to compile (`unresolved reference: recentlyPlayed`).

- [ ] **Step 3: Add repository method + ViewModel state**

`LibraryRepository.kt`:

```kotlin
    /** Most recently played tracks (desktop get_recently_played). */
    suspend fun recentlyPlayed(limit: Int = 20): List<Track> =
        db.playHistoryDao().recentlyPlayed(limit).map { it.toTrack() }
```

`PlayerViewModel.kt`:

```kotlin
    val recentlyPlayed = MutableStateFlow<List<Track>>(emptyList())

    fun refreshRecentlyPlayed() {
        viewModelScope.launch {
            runCatching { Graph.library.recentlyPlayed() }.onSuccess { recentlyPlayed.value = it }
        }
    }
```

Run the Step 2 command again. Expected: PASS.

- [ ] **Step 4: Write HomeScreen.kt (M3a version)**

```kotlin
package io.github.auxen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.SectionHeader
import java.util.Calendar

/** Time-of-day greeting — desktop HomePage header. */
internal fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * Home — M3a foundation version: greeting + Recently Played. The full
 * desktop-parity Home (filter pills, stat cards, Recently Added carousel,
 * Tidal sections) lands in milestone 3b.
 */
@UnstableApi
@Composable
fun HomeScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshRecentlyPlayed() }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
        if (recentlyPlayed.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Nothing played yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Play something from your Library or Search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item { SectionHeader("Recently Played") }
            items(recentlyPlayed, key = { "${it.source}:${it.sourceId}" }) { track ->
                AuxenTrackRow(
                    track = track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                )
            }
        }
    }
}
```

- [ ] **Step 5: Restructure MainActivity**

Replace the `MainScreen` composable (and the `Tab` data class) in `MainActivity.kt` with:

```kotlin
private data class Destination(val route: String, val label: String, val icon: @Composable () -> Unit)

@UnstableApi
@Composable
private fun MainScreen(viewModel: PlayerViewModel) {
    val navController = rememberNavController()
    val destinations = listOf(
        Destination("home", "Home") { Icon(Icons.Filled.Home, contentDescription = null) },
        Destination("library", "Library") { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
        Destination("search", "Search") { Icon(Icons.Filled.Search, contentDescription = null) },
        Destination("collection", "Collection") { Icon(Icons.Filled.Favorite, contentDescription = null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        topBar = {
            if (currentRoute != "nowplaying") {
                CenterAlignedTopAppBar(
                    title = { Text("Auxen", style = MaterialTheme.typography.headlineSmall) },
                    actions = {
                        IconButton(onClick = { navController.navigate("equalizer") }) {
                            Icon(Icons.Filled.Equalizer, contentDescription = "Equalizer")
                        }
                        IconButton(onClick = { navController.navigate("account") }) {
                            Icon(Icons.Filled.Person, contentDescription = "Account")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (currentRoute != "nowplaying") {
                Column {
                    NowPlayingBar(viewModel)
                    NavigationBar {
                        destinations.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = dest.icon,
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") { HomeScreen(viewModel) }
            composable("library") { LibraryScreen(viewModel) }
            composable("search") { SearchScreen(viewModel) }
            composable("collection") { FavoritesScreen(viewModel) }
            composable("equalizer") { EqualizerScreen() }
            composable("account") { AccountScreen(viewModel) }
            composable("nowplaying") { NowPlayingScreenPlaceholder(onBack = { navController.popBackStack() }) }
        }
    }
}

/** Replaced by the real NowPlayingScreen in Task 6. */
@Composable
private fun NowPlayingScreenPlaceholder(onBack: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Now Playing", style = MaterialTheme.typography.displaySmall)
    }
}
```

New imports needed in `MainActivity.kt`: `androidx.navigation.compose.NavHost`, `androidx.navigation.compose.composable`, `androidx.navigation.compose.rememberNavController`, `androidx.navigation.compose.currentBackStackEntryAsState`, `androidx.navigation.NavGraph.Companion.findStartDestination`, `androidx.compose.material3.CenterAlignedTopAppBar` (+ `@OptIn(ExperimentalMaterial3Api::class)` on `MainScreen`), `androidx.compose.material.icons.filled.Home`, `androidx.compose.material3.TextButton`, `io.github.auxen.ui.theme` stays as-is. Remove the old `Tab` class, `mutableIntStateOf` usage, and unused imports.

- [ ] **Step 6: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 54 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ui/ android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt
git commit -m "feat(android): navigation shell — Home/Library/Search/Collection routes"
```

---

### Task 5: Mini player bar

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/MiniPlayerBar.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (position polling + skip)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (swap `NowPlayingBar` for `MiniPlayerBar`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/Screens.kt` (delete the old `NowPlayingBar`)

**Interfaces:**
- Consumes: `AuxenColors` (Task 1); nav route `"nowplaying"` (Task 4).
- Produces (Task 6 relies on the ViewModel additions):
  - `PlayerViewModel.positionMs: StateFlow<Long>`, `PlayerViewModel.durationMs: StateFlow<Long>` (500 ms polling while a controller exists)
  - `PlayerViewModel.skipNext()`, `PlayerViewModel.skipPrevious()`, `PlayerViewModel.seekTo(ms: Long)`
  - `MiniPlayerBar(viewModel: PlayerViewModel, onOpen: () -> Unit)` composable (renders nothing when no current item)

- [ ] **Step 1: Add player-state surface to PlayerViewModel**

```kotlin
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
```

In `init`, after the controller future listener block:

```kotlin
        viewModelScope.launch {
            while (isActive) {
                controller?.let {
                    positionMs.value = it.currentPosition.coerceAtLeast(0)
                    durationMs.value = it.duration.coerceAtLeast(0)
                }
                delay(500)
            }
        }
```

New functions:

```kotlin
    fun skipNext() {
        controller?.seekToNext()
    }

    fun skipPrevious() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }
```

Imports: `kotlinx.coroutines.delay`, `kotlinx.coroutines.isActive`.

- [ ] **Step 2: Write MiniPlayerBar.kt**

```kotlin
package io.github.auxen.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import io.github.auxen.ui.PlayerViewModel
import io.github.auxen.ui.theme.AuxenColors

/**
 * Collapsed player bar — the desktop now-playing bar's ultra-narrow tier
 * (art + marquee title/artist + play + next) with a hairline progress
 * indicator. Tap anywhere to open the full Now Playing screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
fun MiniPlayerBar(viewModel: PlayerViewModel, onOpen: () -> Unit) {
    val metadata = viewModel.nowPlaying ?: return
    val title = metadata.title?.toString() ?: return
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()

    Surface(tonalElevation = 4.dp) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onOpen).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = metadata.artworkUri,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee(),
                    )
                    metadata.artist?.let {
                        Text(
                            it.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { viewModel.togglePlayPause() }) {
                    Icon(
                        if (viewModel.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (viewModel.isPlaying) "Pause" else "Play",
                        tint = AuxenColors.AmberPrimary,
                    )
                }
                IconButton(onClick = { viewModel.skipNext() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }
            if (durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    color = AuxenColors.AmberPrimary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    drawStopIndicator = {},
                )
            }
        }
    }
}
```

- [ ] **Step 3: Swap it in and delete the old bar**

In `MainActivity.kt`'s bottom bar `Column`, replace `NowPlayingBar(viewModel)` with `MiniPlayerBar(viewModel, onOpen = { navController.navigate("nowplaying") })` (import `io.github.auxen.ui.components.MiniPlayerBar`). Delete the entire `NowPlayingBar` composable from `Screens.kt` and its now-unused imports.

- [ ] **Step 4: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 54 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ui/
git commit -m "feat(android): mini player bar with marquee and progress hairline"
```

---

### Task 6: Now Playing screen

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/NowPlayingScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (shuffle/repeat/current-track state)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (replace `NowPlayingScreenPlaceholder`)

**Interfaces:**
- Consumes: `positionMs`/`durationMs`/`seekTo`/`skipNext`/`skipPrevious` (Task 5); `Graph.TRACK_EXTRA_KEY`, `Graph.json` (M2); `QualityBadge`, `SourceBadge`, `formatDuration` (Task 2); `AuxenColors` (Task 1).
- Produces: `NowPlayingScreen(viewModel: PlayerViewModel, onBack: () -> Unit)`; `PlayerViewModel.currentTrack: Track?` (decoded from metadata extras), `PlayerViewModel.shuffleEnabled: Boolean`, `PlayerViewModel.repeatMode: Int` (Player.REPEAT_MODE_*), `fun toggleShuffle()`, `fun cycleRepeat()`.

- [ ] **Step 1: Extend PlayerViewModel**

Add state:

```kotlin
    var currentTrack: Track? by mutableStateOf(null)
        private set
    var shuffleEnabled: Boolean by mutableStateOf(false)
        private set
    var repeatMode: Int by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
```

In the existing `Player.Listener` inside `init` (the one already overriding `onMediaMetadataChanged`/`onIsPlayingChanged`), extend `onMediaMetadataChanged` and add two overrides:

```kotlin
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    nowPlaying = mediaMetadata
                    currentTrack = mediaMetadata.extras
                        ?.getString(Graph.TRACK_EXTRA_KEY)
                        ?.let { encoded -> runCatching { Graph.json.decodeFromString<Track>(encoded) }.getOrNull() }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    shuffleEnabled = shuffleModeEnabled
                }

                override fun onRepeatModeChanged(mode: Int) {
                    repeatMode = mode
                }
```

Also, right after the controller connects (inside the `future.addListener` block after `controller = c`), seed the state:

```kotlin
            shuffleEnabled = c.shuffleModeEnabled
            repeatMode = c.repeatMode
```

New functions:

```kotlin
    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    /** Off -> All -> One -> Off, like the desktop repeat button. */
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
```

- [ ] **Step 2: Write NowPlayingScreen.kt**

```kotlin
package io.github.auxen.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import io.github.auxen.ui.components.QualityBadge
import io.github.auxen.ui.components.SourceBadge
import io.github.auxen.ui.components.formatDuration
import io.github.auxen.ui.theme.AuxenColors

/**
 * Full-screen player — the desktop now-playing bar expanded to a mobile
 * screen: large art, marquee title/artist, seek, transport with 3-state
 * repeat, favorite heart, and source/quality badges.
 */
@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
fun NowPlayingScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val metadata = viewModel.nowPlaying
    val track = viewModel.currentTrack
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Spacer(Modifier.height(16.dp))
        AsyncImage(
            model = metadata?.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    metadata?.title?.toString() ?: "Nothing playing",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    metadata?.artist?.toString() ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (track != null) {
                val key = "${track.source.name}:${track.sourceId}"
                IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                    Icon(
                        if (key in favoriteKeys) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (key in favoriteKeys) AuxenColors.FavoriteRed else LocalContentColor.current,
                    )
                }
            }
        }
        if (track != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SourceBadge(track.source)
                Spacer(Modifier.width(6.dp))
                QualityBadge(track.qualityLabel)
            }
        }
        Spacer(Modifier.height(16.dp))
        Slider(
            value = (dragPositionMs ?: positionMs).toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
            onValueChange = { dragPositionMs = it.toLong() },
            onValueChangeFinished = {
                dragPositionMs?.let { viewModel.seekTo(it) }
                dragPositionMs = null
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = AuxenColors.AmberPrimary,
                activeTrackColor = AuxenColors.AmberPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((dragPositionMs ?: positionMs) / 1000.0), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(durationMs / 1000.0), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (viewModel.shuffleEnabled) AuxenColors.AmberPrimary else LocalContentColor.current,
                )
            }
            IconButton(onClick = { viewModel.skipPrevious() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = { viewModel.togglePlayPause() },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = AuxenColors.AmberPrimary,
                    contentColor = AuxenColors.BgDeep,
                ),
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    if (viewModel.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (viewModel.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { viewModel.skipNext() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { viewModel.cycleRepeat() }) {
                Icon(
                    if (viewModel.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    tint = if (viewModel.repeatMode != Player.REPEAT_MODE_OFF) AuxenColors.AmberPrimary else LocalContentColor.current,
                )
            }
        }
    }
}
```

(Missing import in the block above: `androidx.compose.foundation.layout.width` for `Spacer(Modifier.width(6.dp))` — include it.)

- [ ] **Step 3: Replace the placeholder route**

In `MainActivity.kt`: delete `NowPlayingScreenPlaceholder` and change the route to `composable("nowplaying") { NowPlayingScreen(viewModel, onBack = { navController.popBackStack() }) }`.

- [ ] **Step 4: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 54 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ui/
git commit -m "feat(android): full-screen Now Playing with seek, shuffle, repeat"
```

---

### Task 7: Docs, verification, push

**Files:**
- Modify: `docs/plans/2026-07-03-android-app.md`

- [ ] **Step 1: Update the design doc**

In the roadmap table, change the milestone 3 row label to `**3 — in progress**` and add below the table: `Milestone 3a (UI foundation: amber theme, shared components, action sheet, nav shell, mini player, Now Playing) shipped; 3b (Home/Library/Search/Collection/detail screens) follows — design source: .superpowers/sdd/m3-ui-inventory.md.`

- [ ] **Step 2: Full verification**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL, 54 tests, 0 failures. Record the totals.

- [ ] **Step 3: Commit and push**

```bash
cd /home/mrw1986/Projects/auxen
git add docs/plans/2026-07-03-android-app.md
git commit -m "docs(android): milestone 3a UI foundation shipped"
git push origin claude/android-app-availability-y6uzb0
```
