# Android Theme Parity — Desktop Colors, Components, Typography

**Goal:** Close every confirmed visual-identity gap between the Android app and the desktop source of truth (`data/style.css`, shipped GTK app), found by a 28-agent adversarially-verified parity audit (23 confirmed findings, 0 refuted). Branding-batch items (launcher icon, splash, brand block, notification icon, Josefin Sans lockup) are handled in `2026-07-12-android-branding-parity.md` and its fix rounds — this plan covers the rest.

**Source-of-truth precedence** (established by the audit): `data/style.css` (shipped app) > `docs/branding/logo-guide.md` > `ui-mockup.html` (stale in places — it predates the Josefin Sans brand decision and the gold title).

**Non-goals:** wordmark drawable for an About screen (deferred until the About/Settings screen exists — roadmap item), reverb/DSP UI (DSP-b), any layout restructuring.

**Verification gate for every task:**
```bash
cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug
```
Golden shifts are EXPECTED in Tasks 1–4 (that is the point — the colors change). For every shifted golden: re-record via `:app:recordRoborazziDebug`, eyeball the PNG, and state in the report what changed and why it is correct. Known trap: adding test methods to `ComponentScreenshotTest` deterministically shifts the `action-sheet-*` goldens (~0.3% AA drift) — if it happens, verify determinism before re-recording, per the branding Task 2 report.

---

### Task 1: Complete the Material color schemes (kills the purple)

The single highest-impact fix. Neither scheme overrides `secondaryContainer`/`onSecondaryContainer`, so **every selected state in the app** — bottom-nav indicator, selected FilterChips, active SegmentedButtons — renders Material-baseline lavender/purple, the one hue absent from the Auxen palette. Dark `errorContainer` is baseline maroon/pink. Light mode leaks five violet-tinted baseline `surfaceContainer*` surfaces into the warm cream palette, and light `onPrimary` is White where desktop's `accent_fg_color` is scheme-invariant `#0c0b0f`.

**Files:** `android/app/src/main/kotlin/io/github/auxen/ui/theme/Theme.kt`, plus the three FilterChip call sites, plus a new `ThemeParityTest`.

**Step 1 — Theme.kt, AuxenDarkColors** (add to the existing builder call):

```kotlin
    // Desktop nav-selection glow: rgba(212,160,57,0.15) over BgSurface (style.css:184-195)
    secondaryContainer = Color(0xFF31281D),
    onSecondaryContainer = AuxenColors.AmberPrimary,
    // Desktop soft-red error treatment: rgba(231,76,60,0.12) over BgSurface (style.css:776-777)
    errorContainer = Color(0xFF2D1A1C),
    onErrorContainer = Color(0xFFE74C3C),
```

**Step 2 — Theme.kt, AuxenLightColors:**

```kotlin
    // was Color.White — desktop accent_fg_color #0c0b0f is scheme-invariant (style.css:8-11),
    // and every hard-coded amber button already uses BgDeep content in both themes
    onPrimary = AuxenColors.BgDeep,
    secondaryContainer = Color(0xFFF5E6C4),
    onSecondaryContainer = Color(0xFF3D2E00),
    errorContainer = Color(0xFFF8E3DC),   // rgba(231,76,60,0.12) over #FAF8F2
    onErrorContainer = Color(0xFFC0392B),
    // Warm-neutral container ramp — the dark scheme already overrides these; light must too,
    // or NavigationBar/ModalBottomSheet/AlertDialog/slider tracks render M3 baseline lavender
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F4EC),
    surfaceContainer = Color(0xFFF1EDE3),
    surfaceContainerHigh = Color(0xFFEBE6D9),
    surfaceContainerHighest = Color(0xFFE5DFD0),
```

**Step 3 — Solid amber selected filter pills.** Desktop filter pills when checked are SOLID amber with dark text (`.filter-btn:checked { background-color:#d4a039; color:#0c0b0f; }`, style.css:334-339) — stronger than the nav glow. At the three FilterChip call sites (`ui/HomeScreen.kt:88-92`, `ui/SearchScreen.kt:130-134`, `ui/CollectionScreen.kt:99-103`) pass:

```kotlin
    colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = AuxenColors.AmberPrimary,
        selectedLabelColor = AuxenColors.BgDeep,
    ),
```

SegmentedButtons and the NavigationBar indicator keep the (now amber-glow) `secondaryContainer` defaults — matching the desktop's tinted nav-selection treatment.

**Step 4 — TDD.** New `android/app/src/test/kotlin/io/github/auxen/ui/theme/ThemeParityTest.kt` (Robolectric + createComposeRule, mirror `BrandBlockTest` conventions): render `AuxenTheme(darkTheme = true)` and `false`, capture `MaterialTheme.colorScheme` into a var, and assert the exact new slot values above (8 assertions dark, 8+ light). Write RED first (asserting the new values against the current scheme fails), then apply Steps 1–3.

**Step 5 — Goldens.** Nav bar, chips, segmented buttons, bottom sheets, dialogs will shift. Re-record, eyeball each: selected states must now read amber/warm, never purple. Call out in the report if any golden did NOT shift that you expected to.

---

### Task 2: Badge parity (source, quality, explicit)

**Files:** `android/app/src/main/kotlin/io/github/auxen/ui/components/Badges.kt`, `components/AuxenTrackRow.kt`, `components/AlbumCard.kt`, `ui/theme/Type.kt`.

Desktop track-row badges are **solid** pills: `.source-badge-tidal { background:#00c4cc; color:#0c0b0f; border-radius:9999px; }`, `.source-badge-local { background:#7cb87a; color:#0c0b0f; }` (style.css:445-474). The current 15%-tint style is the desktop *nav-badge* treatment, legitimate only for the AlbumCard art overlay (ui-mockup.html:588-611).

**Step 1 — `SourceBadge`:** add a `tinted: Boolean = false` parameter.
- `tinted = false` (default, track rows / NowPlaying / AlbumDetail): `background(color)` solid, text color `AuxenColors.BgDeep`, shape `RoundedCornerShape(50)`.
- `tinted = true`: current treatment (15% tint, colored text), shape stays `RoundedCornerShape(6.dp)` (matches the mockup art-overlay chip).
- Update `AlbumCard.kt:55` to pass `tinted = true`. Other call sites (`AuxenTrackRow.kt:111`, `NowPlayingScreen.kt:119`, `AlbumDetailScreen.kt:100`) take the new solid default unchanged.

**Step 2 — `QualityBadge`:** bg alpha `0.15f` → `0.2f`, shape `RoundedCornerShape(6.dp)` → `RoundedCornerShape(50)` (desktop: rgba(212,160,57,0.2), radius 9999px — style.css:831-834).

**Step 3 — Explicit badge** (`AuxenTrackRow.kt:85-93`): text color `MaterialTheme.colorScheme.onSurfaceVariant` → `MaterialTheme.colorScheme.onSurface`, and `fontWeight = FontWeight.ExtraBold` (desktop weight 800, style.css `.explicit-badge`). Prerequisite: register ExtraBold in the DmSans family — in `Type.kt` add `variable(R.font.dm_sans, FontWeight.ExtraBold)` to `DmSans` (the variable font's wght axis covers 800; same pattern as existing entries).

**Step 4 — Tests/goldens:** badge goldens shift (solid fill is the point). If no badge-specific golden exists, add one `captureComponent` pair (dark/light) rendering a Row of SourceBadge(TIDAL, solid), SourceBadge(LOCAL, solid), SourceBadge(tinted), QualityBadge — heed the action-sheet drift trap.

---

### Task 3: Player + list chrome

**Files:** `components/MiniPlayerBar.kt`, `components/AuxenTrackRow.kt`, `ui/SearchScreen.kt`.

**Step 1 — Mini player play/pause button** (`MiniPlayerBar.kt:82-88`): desktop's signature control is a filled amber circle with dark glyph (`.now-playing-play-btn`, style.css:577-585); `NowPlayingScreen.kt:160-166` already does this. Give the IconButton `colors = IconButtonDefaults.iconButtonColors(containerColor = AuxenColors.AmberPrimary, contentColor = AuxenColors.BgDeep)` and REMOVE the `tint = AuxenColors.AmberPrimary` from the Icon.

**Step 2 — Mini player title weight** (`MiniPlayerBar.kt:67`): add `fontWeight = FontWeight.SemiBold` (desktop now-playing title is 600–700 vs 500 list rows; style.css:551-554).

**Step 3 — List-size art radius:** `RoundedCornerShape(10.dp)` → `RoundedCornerShape(6.dp)` at `AuxenTrackRow.kt:70` and `MiniPlayerBar.kt:61` (desktop 6px for 44–48px art, style.css:562-567; 10dp stays correct for large `AlbumCard` art).

**Step 4 — Search field** (`SearchScreen.kt:65-86`): desktop is a filled 12px-radius rounded field (`.search-entry`, style.css:705-719). Pass to the OutlinedTextField:

```kotlin
    shape = RoundedCornerShape(12.dp),
    colors = OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    ),
```

(The amber focus border already comes through via `primary`.)

**Step 5 — Goldens:** mini-player and search goldens shift; re-record + eyeball (filled amber circle present, 6dp art corners, filled rounded search bar).

---

### Task 4: Typography details

**Files:** `ui/NowPlayingScreen.kt`, `ui/theme/Type.kt`, `components/AuxenTrackRow.kt`.

**Step 1 — Now Playing track title** (`NowPlayingScreen.kt:93`): Fraunces is chrome (brand/headings/stat values); track titles are always DM Sans in the desktop design language — this is even what Type.kt's own doc comment says. Replace `MaterialTheme.typography.headlineSmall` with:

```kotlin
    style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
```

(titleMedium = DM Sans SemiBold; keep 22sp for hierarchy. `headlineSmall` stays Fraunces for page headings elsewhere.)

**Step 2 — Fraunces Bold registration** (`Type.kt:30-34`): add `variable(R.font.fraunces, FontWeight.Bold)` so any `FontWeight.Bold` request in a Fraunces context resolves to wght=700 instead of silently snapping to 600.

**Step 3 — Fixed-width time digits.** Desktop pins ALL time readouts to monospace (style.css:645-647, 1631-1633, 2146-2148); DM Sans has no `tnum` feature (verified — `fontFeatureSettings` will NOT work), so the ticking position label jitters. Apply `fontFamily = FontFamily.Monospace` to: the position/duration labels in `NowPlayingScreen.kt:141-142`, the row duration in `AuxenTrackRow.kt:105-108`, and any time text in `MiniPlayerBar.kt` if present.

**Step 4 — Goldens:** NP-screen and track-row goldens shift; re-record + eyeball (sans-serif NP title, monospace durations).

---

### Task 5: Batch close-out

1. Full gate from a clean state (`--rerun-tasks`), confirm total test count and zero failures.
2. `git log --oneline` summary of the batch's commits for the ledger.
3. Whole-batch multi-agent final review (dispatched by the lead — not the implementer).
4. Roadmap/parity artifact updates (`docs/roadmap.html`, `docs/feature-parity.html` + same-URL artifact republish) — done by the lead.
5. Push after final review passes.

---

### Expected test count progression

Baseline at branding-batch close (Task 3 + fix round): report actual. Task 1 adds ThemeParityTest (~16 assertions across 2 test methods, +2 tests) + golden re-records (count unchanged). Task 2 adds 1 badge golden pair (+2). Tasks 3–4 re-record only (count unchanged) unless a missing golden is added — implementer states final counts in each report.
