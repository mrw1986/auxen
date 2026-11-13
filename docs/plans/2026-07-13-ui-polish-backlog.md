# Auxen Android — UI Polish Backlog (audit 2026-07-13)

Source: fan-out polish audit (10 screen auditors → synthesis), 88 raw findings → 36 ranked. Working doc for the polish batch.

## Themes (most → least pervasive)
1. **Light-theme correctness** — many always-visible controls hardcode dark-theme gold (`AuxenColors.AmberPrimary`/`#D4A039`) instead of `MaterialTheme.colorScheme.primary`; mini-player, filter chips, seek bar, progress render off-palette in light mode; QualityBadge/EQ text fail WCAG contrast.
2. **Missing empty/loading/placeholder states** — nearly every list surface + art slot; blank/in-flight screens read as broken.
3. **No tablet/wide adaptation** — fixed-width AlbumCards fight the adaptive grid; edge-to-edge stretch; bottom-bar-only nav.
4. Lower-level: numerics not monospaced (~4 places), sub-48dp touch targets, hardcoded-English a11y labels with no toggle state, duplicated components.

## Implementation waves (lead's plan)
- **Wave 1 (pervasive, high-value):** P1 theming (1,5,28) → P2 states (4,8,10,11,12,14,22,35) → P3 image placeholders (2). Sequential (shared files, goldens).
- **Wave 2 (responsiveness):** 3, 9, 17, 26, 29.
- **Wave 3 (nav + a11y + micro-interactions + consistency):** 7, 13, 16, 18, 20, 23, 24, 25, 31, 32, 34, 36.

## Ranked backlog
| # | Title | Screens | Cat | Sev | Effort |
|---|-------|---------|-----|-----|--------|
| 1 | Hardcoded amber → colorScheme.primary | Home, Collection, NowPlaying, MiniPlayer | theming | med | quick |
| 2 | AsyncImage placeholder/fallback (blank art holes) | rows, cards, mini, detail headers | states | med | med |
| 3 | AlbumCard fixed width(150.dp) fights adaptive grid | Library, Collection | responsive | high | med |
| 4 | Library no empty/loading state | Library | states | high | med |
| 5 | QualityBadge fails light-theme contrast (~2.6:1) | Badges/Tidal rows | a11y | med | quick |
| 6 | Numerics not monospaced | Home, Library, Collection, EQ | typography | low | quick |
| 7 | Top bar not context-aware (double header / no back) | scaffold + detail | consistency | high | med |
| 8 | Album/Artist detail no empty state; Play All/Shuffle enabled w/ 0 tracks | Album/Artist detail | states | high | med |
| 9 | 10-band EQ overflows hidden horizontal scroll on phones | Equalizer | responsive | high | med |
| 10 | Search blank void for no-results + initial state | Search | states | med | quick |
| 11 | Library header "N tracks" on every tab | Library | polish | med | quick |
| 12 | Collection Albums/Artists tabs missing empty state | Collection | states | med | quick |
| 13 | NowPlaying toggles don't convey on/off to a11y | NowPlaying | a11y | med | quick |
| 14 | No loading feedback during async login/probe/load | Home, Account, Debug, EQ | states | med | med |
| 15 | Missing section spacing/rhythm | Home, Library, Search, NP sheet, Queue | polish | low | quick |
| 16 | Sub-48dp touch targets | AlbumCard, EQ, Album detail | a11y | med | quick |
| 17 | Color-picker swatches overflow dialog, clipped/unlabeled | Playlist detail | responsive | high | med |
| 18 | FX cards: instant expand + only chevron toggles | Equalizer | micro | med | quick |
| 19 | Dense track rows overcrowd title on narrow phones | Queue, Playlist detail | responsive | med | med |
| 20 | Reorder has no placement animation / drag lift | Playlist, Queue | micro | med | med |
| 21 | NowPlaying backdrop clipped by insets; top scrim weak | NowPlaying | polish | med | med |
| 22 | Empty states bare/left-aligned, not centered w/ icon | Home, Queue | states | med | med |
| 23 | Small motion polish (crossfades, banner, autofocus) | several | micro | low | quick |
| 24 | Hardcoded-English user/a11y strings | NowPlaying, EQ, nav | consistency | med | med |
| 25 | Artist rows bare text, duplicated across 2 screens | Library, Collection | consistency | med | med |
| 26 | Content stretches edge-to-edge, no max width on tablet | many | responsive | med | med |
| 27 | Preset selectors look like buttons, not menu triggers | EQ, Reverb | consistency | med | med |
| 28 | Playlist color: `#d4a039` literal + invisible pale dots (light) | Collection, TrackActionSheet | theming | low | quick |
| 29 | 4 equal segmented tabs crowd/clip labels | Collection | responsive | med | med |
| 30 | Tidal Debug readout flat/unstyled, off card idiom | Tidal Debug | states | med | med |
| 31 | Explicit 'E' hand-rolled inline; album cards have none | rows, cards, Badges | consistency | med | med |
| 32 | Action-sheet playlist picker no back; header no art | TrackActionSheet | micro | med | quick |
| 33 | AutoEq nests 240dp scroll in page scroll; import styling | Equalizer | responsive | med | med |
| 34 | Bottom nav never adapts to tablet (no NavigationRail) | scaffold | responsive | med | large |
| 35 | Open sort menu doesn't mark active option | Library | states | low | quick |
| 36 | Play All+Shuffle row duplicated across 3 detail screens | detail | consistency | low | med |

**Top quick wins:** 1, 5, 6, 10, 11, 12, 13, 15, 16, 18, 32.
