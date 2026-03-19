# Phase 5A: Tidal Home Page — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Transform the Home page from a basic local-only view into a rich, Tidal-powered experience with 15+ personalized sections.

**Architecture:** Keep existing local sections (greeting, stats, recently added/played). Add Tidal sections below them using `session.home()` API. Each Tidal section renders as a `HorizontalCarousel` with type-specific card renderers. Background fetch on startup, cached for 10 minutes.

**Tech Stack:** Python 3.14, GTK4, Libadwaita, tidalapi `session.home()` / `PageCategoryV2`

---

## Task 1: Add `get_home_page()` to Tidal Provider

**Files:** `auxen/providers/tidal.py`

Add method that calls `session.home()`, iterates categories, and returns a structured list of sections. Each section dict has: `title`, `type` (album/track/playlist/artist/mix), `items` (list of tidalapi objects).

## Task 2: Create HorizontalCarousel Widget

**Files:** `auxen/views/widgets.py`, `data/style.css`

A `Gtk.Box(VERTICAL)` containing:
- Header row: section title label + optional "View all" button
- Scrollable content: `Gtk.ScrolledWindow(AUTOMATIC, NEVER)` with a `Gtk.Box(HORIZONTAL)` of cards
- Left/right scroll buttons overlaid on edges

## Task 3: Create Card Renderer Functions

**Files:** `auxen/views/home.py` or `auxen/views/widgets.py`

Card builders for each Tidal content type:
- `_make_tidal_album_card(album)` — reuse existing `_make_album_card` pattern
- `_make_artist_circle(artist)` — circular image + name (new)
- `_make_playlist_card(playlist)` — cover + title + track count (new)
- `_make_mix_card(mix)` — cover + title (new, reuse from mixes.py pattern)

## Task 4: Integrate Tidal Sections into Home Page

**Files:** `auxen/views/home.py`

Add `set_tidal_sections(categories)` method that:
- Clears previous Tidal sections
- For each category, creates a HorizontalCarousel with appropriate card renderer
- Appends sections below the existing local content
- Respects filter pills (All shows everything, Tidal shows only Tidal sections, Local hides them)

## Task 5: Background Fetch + Caching

**Files:** `auxen/app.py`, `auxen/window.py`

- After Tidal session restored, fetch `session.home()` in background thread
- Pass results to `home.set_tidal_sections()` via `GLib.idle_add`
- Cache for 10 minutes, refresh on manual pull or page revisit after expiry

## Task 6: CSS Styling

**Files:** `data/style.css`

- `.tidal-section-header` — section title styling
- `.horizontal-carousel` — scroll container
- `.carousel-scroll-btn` — left/right scroll buttons
- `.artist-circle` — circular image mask
- `.playlist-card`, `.mix-card` — card variants
