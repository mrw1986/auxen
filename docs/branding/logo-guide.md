---
title: Auxen Logo & Branding Guide
date: 2026-03-10
tags:
  - branding
  - logo
  - design-system
status: active
---

# Auxen Logo & Branding Guide

## Logo Concept

The Auxen logo is a **bold, geometric ox head wearing over-ear headphones** with a nose ring. The design is flat, minimal, and icon-forward — built for clarity at every size from 16px favicons to full-page marketing.

> [!tip] Design Philosophy
> The ox represents strength and presence. The headphones tie it to music. The nose ring adds personality. Together they create a memorable, distinctive mark for a music player.

## Color Palette

### Primary Colors

| Color | Hex | RGB | Usage |
|:------|:----|:----|:------|
| **Cream/Ivory** | `#fef8e4` | `254, 248, 228` | Ox body (dark mode) |
| **Dark Brown** | `#2a1f14` | `42, 31, 20` | Ox body (light mode) |
| **Gold Earpad** | `#b68312` | `182, 131, 18` | Headphone earpads (both modes) |
| **Dark Background** | `#0c0b0f` | `12, 11, 15` | Dark mode background |
| **Light Background** | `#fafaf8` | `250, 250, 248` | Light mode background |

> [!important] Gold Earpad Consistency
> The gold earpad color **must be identical** across dark and light variants. Both are calibrated to `#b68312` (within 1 RGB unit). This was pixel-verified — dark avg `#b58312`, light avg `#b78413`.

### Extended Palette

| Color | Hex | Usage |
|:------|:----|:------|
| **Accent Gold** | `#d4a039` | UI accents, highlights, active states |
| **Deep Gold** | `#886b30` | Section labels, muted accents |
| **Dark Gold** | `#8B6914` | Text accents on light backgrounds |

## Theme Variants

### Dark Mode (Primary)

Cream/ivory ox with gold earpads on dark background. This is the **primary** variant — the app defaults to dark theme.

![[auxen-dark-512.png]]

### Light Mode

Dark brown ox with gold earpads on light background. An **inverted** version of the dark mode logo that maintains the same silhouette and proportions.

![[auxen-light-512.png]]

> [!note] Theme Switching
> In GTK4/Adw, swap based on `Adw.ColorScheme`:
> - `FORCE_DARK` / `PREFER_DARK` → dark variant
> - `FORCE_LIGHT` / `PREFER_LIGHT` → light variant

## Typography

### Wordmark Font

| Property | Value |
|:---------|:------|
| **Font** | Josefin Sans |
| **Weight** | 700 (Bold) |
| **Case** | ALL UPPERCASE |
| **Letter Spacing** | 0.25em (wide tracking) |
| **Source** | [Google Fonts](https://fonts.google.com/specimen/Josefin+Sans) |

> [!info] Why Josefin Sans?
> Josefin Sans is a geometric sans-serif with an elegant Art Deco feel. Its clean, wide letterforms complement the bold flat logo style. The generous tracking gives the wordmark a premium, spacious quality.

### Wordmark Colors

| Mode | Text Color | Matches |
|:-----|:-----------|:--------|
| Dark | `#fef8e4` (Cream) | Ox body color |
| Light | `#2a1f14` (Dark Brown) | Ox body color |

## File Locations

All logo assets live under `data/` in the project root.

### App Icon (Freedesktop/GTK)

Installed by `meson.build` into the hicolor icon theme.

```
data/icons/hicolor/
├── scalable/apps/io.github.auxen.Auxen.svg    ← Primary (dark mode SVG)
├── 16x16/apps/io.github.auxen.Auxen.png
├── 24x24/apps/io.github.auxen.Auxen.png
├── 32x32/apps/io.github.auxen.Auxen.png
├── 48x48/apps/io.github.auxen.Auxen.png
├── 64x64/apps/io.github.auxen.Auxen.png
├── 128x128/apps/io.github.auxen.Auxen.png
├── 256x256/apps/io.github.auxen.Auxen.png
├── 512x512/apps/io.github.auxen.Auxen.png
└── scalable/actions/
    ├── tidal-symbolic.svg                      ← Custom symbolic icons
    └── never-played-symbolic.svg
```

> [!warning] App Icon Name
> The filename **must** be `io.github.auxen.Auxen` to match the `application_id` in `auxen/app.py` and the `Icon=` field in `data/io.github.auxen.Auxen.desktop`.

### Full Logo Collection

Brand assets for marketing, README, social, etc.

```
data/logo/
├── svg/
│   ├── auxen-dark.svg                          ← Icon, dark bg
│   ├── auxen-light.svg                         ← Icon, light bg
│   ├── auxen-dark-transparent.svg              ← Icon, no bg
│   ├── auxen-light-transparent.svg             ← Icon, no bg
│   ├── auxen-wordmark-dark.svg                 ← Icon + "AUXEN", dark bg
│   ├── auxen-wordmark-light.svg                ← Icon + "AUXEN", light bg
│   ├── auxen-wordmark-dark-transparent.svg     ← Icon + "AUXEN", no bg
│   └── auxen-wordmark-light-transparent.svg    ← Icon + "AUXEN", no bg
├── wordmark/
│   ├── auxen-wordmark-dark-{128,256,512,1024}.png
│   └── auxen-wordmark-light-{128,256,512,1024}.png
├── png-light/
│   └── auxen-light-{16..512}.png               ← Light mode rasters
├── png-dark-transparent/
│   └── auxen-dark-transparent-{16..512}.png    ← Dark icon, no bg
├── png-light-transparent/
│   └── auxen-light-transparent-{16..512}.png   ← Light icon, no bg
├── ico/
│   ├── auxen-dark.ico                          ← Multi-res (16/32/48/256), transparent bg
│   └── auxen-light.ico                         ← Multi-res (16/32/48/256), transparent bg
├── auxen-wordmark-dark.jpg                     ← AI-generated wordmark raster (dark)
└── auxen-wordmark-light.jpg                    ← AI-generated wordmark raster (light)
```

### Total Asset Count

| Category | Files |
|:---------|------:|
| App icon (hicolor SVG + PNGs) | 9 |
| Logo SVGs (all variants) | 8 |
| Wordmark PNGs | 8 |
| Light mode PNGs | 8 |
| Transparent PNGs (dark + light) | 16 |
| ICO files | 2 |
| Wordmark JPGs | 2 |
| Custom symbolic icons | 2 |
| **Total** | **55** |

## Build Integration

### meson.build

The scalable SVG and all raster sizes are installed via `meson.build`:

```meson
# Install icons
install_data(
  'data/icons/hicolor/scalable/apps/io.github.auxen.Auxen.svg',
  install_dir: get_option('datadir') / 'icons' / 'hicolor' / 'scalable' / 'apps',
)

foreach size : ['16', '24', '32', '48', '64', '128', '256', '512']
  install_data(
    'data/icons/hicolor' / size + 'x' + size / 'apps' / 'io.github.auxen.Auxen.png',
    install_dir: get_option('datadir') / 'icons' / 'hicolor' / size + 'x' + size / 'apps',
  )
endforeach
```

### Desktop File

```ini
[Desktop Entry]
Icon=io.github.auxen.Auxen
```

### Python App Registration

```python
# auxen/app.py
super().__init__(application_id="io.github.auxen.Auxen")
```

## Usage Guidelines

### Do

- Use the dark variant on dark backgrounds, light variant on light backgrounds
- Use transparent variants when placing the logo over photos or gradients
- Maintain the gold earpad accent in both themes
- Use the wordmark for marketing, README headers, and splash screens
- Use the icon-only mark for app icons, favicons, and small UI elements

### Don't

- Don't recolor the gold earpads — they are the consistent brand element across themes
- Don't use the dark variant on light backgrounds (or vice versa) — use the correct theme variant
- Don't stretch or distort the logo — always maintain aspect ratio
- Don't add drop shadows, glows, or other effects to the logo
- Don't place the logo on busy backgrounds without using the version with a background rect

### Minimum Sizes

| Context | Minimum Size | Recommended |
|:--------|:-------------|:------------|
| Favicon | 16px | 32px |
| Sidebar icon | 32px | 40px |
| App icon | 48px | 128px |
| Marketing / README | 256px | 512px |
| Print | — | SVG (scalable) |

## SVG Technical Details

### Generation Method

SVGs were generated by:
1. Extracting color layers (body + gold earpads) from the raster logos using PIL/numpy
2. Tracing each layer to vector paths using **potrace** (threshold 10, optimize 0.5)
3. Combining layers into multi-color SVGs with correct potrace coordinate transforms

### SVG Structure

Each SVG contains:
- A `<rect>` background (or `fill="none"` for transparent)
- A `<g>` group with potrace transform for the **body** paths
- A `<g>` group with potrace transform for the **gold earpad** paths

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 14513.38 14513.38">
  <rect width="100%" height="100%" fill="#0c0b0f"/>
  <g transform="translate(0,14513.38) scale(0.7087,-0.7087)" fill="#fef8e4">
    <path d="..."/>
  </g>
  <g transform="translate(0,14513.38) scale(0.7087,-0.7087)" fill="#b68312">
    <path d="..."/>
  </g>
</svg>
```

### Wordmark SVGs

Wordmark SVGs embed the icon paths and add a `<text>` element using Josefin Sans Bold. The font must be installed on the rendering system for correct display.

## Design History

> [!abstract] Timeline
> - **Concept:** Bold geometric ox with headphones, nose ring
> - **Iteration:** 20+ variants tested (gold, cream, lineart, refined, flat)
> - **Winner:** "Bold Cream" — cream/ivory ox on dark, inverted for light
> - **Refinement:** Gold earpads added to light variant, then matched on dark variant
> - **Color calibration:** Gold earpads pixel-matched to within 1 RGB unit across both themes
> - **Asset generation:** SVG tracing via potrace, PNG rendering via Inkscape, ICO via ImageMagick
