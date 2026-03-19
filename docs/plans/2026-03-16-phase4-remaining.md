# Phase 4 Remaining Items — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete all 12 remaining Phase 4 items (4 already done: quality tooltips, quality on search, metadata scraping, theme toggle).

**Architecture:** Each task modifies 1-3 files. No new dependencies. All changes use existing GTK4/Adwaita patterns from the codebase.

**Tech Stack:** Python 3.14, GTK4, Libadwaita, GStreamer, tidalapi, mutagen

---

## Task 1: Fix Play Icon Overlay Click in Collection

**Problem:** Left-clicking the play button overlay on album cards in Collection doesn't work. The FlowBox child-activated signal may be consuming the click before the button gets it.

**Files:**
- Modify: `auxen/views/collection.py` — `_make_album_card()` and `_attach_grid_play_button()`

**Step 1:** Find `_make_album_card()` and check if the play button's clicked signal is connected. Ensure the play button has `set_can_target(True)` and `set_focusable(True)`. The FlowBox child may need `set_activatable(False)` to prevent it from stealing the click.

**Step 2:** In `_attach_grid_play_button()`, ensure the button uses `.connect("clicked", handler)` not a GestureClick. Buttons handle their own click events.

**Step 3:** Verify the same pattern in `_make_artist_card()` if applicable.

**Step 4:** Test by clicking play overlay on album cards in Collection grid view.

**Step 5:** Commit: `fix: play icon overlay click in Collection grid view`

---

## Task 2: Fix Selected Item Highlight Inconsistency

**Problem:** Row highlight appears inconsistent — works near edges but not center of row.

**Files:**
- Modify: `data/style.css` — row hover rules

**Step 1:** Search for all `row.activatable:hover` and `:hover` rules in style.css. The issue is likely competing hover rules or padding gaps causing the highlight to not cover the full row.

**Step 2:** Ensure track/album/artist rows in ListBox use `set_activatable(True)`. Check that the row's child box fills the full row width with `hexpand=True`.

**Step 3:** If the inner content box has margins/padding creating a gap, the hover background paints on the row but the inner box covers it. Fix by moving the background to the inner box or removing gaps.

**Step 4:** Test hover highlight across Library, Collection, Search, and Playlist views.

**Step 5:** Commit: `fix: consistent row highlight on hover across all views`

---

## Task 3: Search Page Tabs

**Problem:** Search results are unintuitive — all types mixed together with no filtering.

**Files:**
- Modify: `auxen/views/search.py` — add tab filtering

**Step 1:** Add a `Gtk.Box` with toggle buttons (All / Tracks / Albums / Artists) above the results list. Use `Gtk.ToggleButton` in a group (set_group on each to the first button).

**Step 2:** Store the current filter type. In `_populate_results()`, filter displayed results based on active tab. The search already returns separate result types — filter by checking the track object's type or using separate result lists.

**Step 3:** Connect each toggle button's `toggled` signal to re-filter results without re-searching.

**Step 4:** Style the tabs using existing `.library-filter-btn` CSS class for consistency.

**Step 5:** Test: search for an artist name, switch between tabs, verify filtering works.

**Step 6:** Commit: `feat: add search page tabs for Tracks/Albums/Artists filtering`

---

## Task 4: Make Artist/Album Clickable in Search Results

**Problem:** Artist and album names in search results are plain text, not clickable.

**Files:**
- Modify: `auxen/views/search.py` — add click handlers to artist/album labels
- Modify: `auxen/views/widgets.py` — if `make_standard_track_row` needs artist/album click callbacks

**Step 1:** In search result row construction, check if `make_standard_track_row` supports `on_artist_clicked` / `on_album_clicked` callbacks. If not, add optional callback parameters.

**Step 2:** Wrap artist and album labels with `Gtk.GestureClick` controllers. Add `clickable-link` CSS class and pointer cursor.

**Step 3:** Wire callbacks through to window's `_navigate_to_artist()` and `_on_album_clicked()`.

**Step 4:** Test: search, click artist name → navigates to artist detail. Click album name → navigates to album detail.

**Step 5:** Commit: `feat: clickable artist/album names in search results`

---

## Task 5: Scrolling Text in Now-Playing Bar

**Problem:** Long track titles/artist names get truncated with ellipsis. User wants scrolling/marquee text.

**Files:**
- Modify: `auxen/views/now_playing.py` — add marquee animation
- Modify: `data/style.css` — marquee CSS

**Step 1:** GTK4 CSS supports `@keyframes` but GTK labels don't support CSS `transform`. Instead, use `Adw.TimedAnimation` on a `Gtk.ScrolledWindow` wrapping each label, or accept enhanced ellipsis. The most practical GTK4 approach: wrap labels in a `Gtk.ScrolledWindow` with `set_policy(EXTERNAL, NEVER)` and animate the hadjustment.

**Step 2:** Create a helper `_MarqueeLabel` widget: a `Gtk.ScrolledWindow` containing a `Gtk.Label`. When the label is wider than the scroll area, start a slow `Adw.TimedAnimation` on the hadjustment that scrolls left, pauses, scrolls back.

**Step 3:** Replace `self._title_label`, `self._artist_label`, `self._album_label` with `_MarqueeLabel` instances.

**Step 4:** Start/reset animation on `track-changed`. Only animate if text is actually truncated (label width > container width).

**Step 5:** Test with long track names. Verify animation is smooth and resets on track change.

**Step 6:** Commit: `feat: scrolling marquee text for truncated now-playing labels`

---

## Task 6: Mixes Cover Art

**Problem:** Mixes show placeholder icons instead of cover art. Tidal mobile app shows cover art.

**Files:**
- Modify: `auxen/views/mixes.py` — fix art loading
- Modify: `auxen/providers/tidal.py` — ensure mix image URLs are returned

**Step 1:** Check `get_mixes()` in tidal.py. Verify it returns `image_url` or `cover_url` from the Mix object. tidalapi Mix objects have `.image()` method (callable) or `.picture` attribute.

**Step 2:** In `_make_mix_card()`, verify art loading pipeline: `_load_card_art()` should download the URL and set the `_art_image` widget.

**Step 3:** If mix image URLs aren't being extracted, fix `get_mixes()` to call `mix.image(640)` (or appropriate size) and include in the returned dict.

**Step 4:** Test: navigate to Mixes page with Tidal connected, verify cover art appears.

**Step 5:** Commit: `fix: mixes cover art loading from Tidal`

---

## Task 7: Favorites Sort "Recently Added" for Tidal Tracks

**Problem:** Sort by "Recently Added" doesn't work for Tidal tracks in Collection.

**Files:**
- Modify: `auxen/views/collection.py` — fix sort key for Tidal tracks
- Modify: `auxen/db.py` — check `date_added` field on tracks

**Step 1:** Check if `date_added` is stored for Tidal tracks in the database. The favorites sync may not be setting this field.

**Step 2:** In `_get_sorted_tracks()`, when sorting by `date_added`, ensure Tidal tracks have a fallback (e.g., their database insertion time or `created_at` from favorites API).

**Step 3:** If needed, update favorites sync to store the Tidal `created` timestamp as `date_added`.

**Step 4:** Test: add Tidal tracks to collection, sort by "Recently Added", verify order.

**Step 5:** Commit: `fix: Recently Added sort works for Tidal tracks in Collection`

---

## Task 8: Highlight Currently Playing Track

**Problem:** The currently playing track isn't visually highlighted in track lists.

**Files:**
- Modify: `auxen/views/collection.py`, `auxen/views/library.py`, `auxen/views/home.py` — add highlight logic
- Modify: `auxen/window.py` — propagate track-changed to views
- Modify: `data/style.css` — add `.now-playing-row` style

**Step 1:** Add CSS class `.now-playing-row` with subtle amber background (same as `.queue-track-active`).

**Step 2:** In `window.py` `_on_track_changed`, call a new method on each view: `view.highlight_playing_track(track)`.

**Step 3:** Each view's `highlight_playing_track()` iterates its ListBox rows, comparing track ID or source_id. Add/remove `.now-playing-row` CSS class.

**Step 4:** Test: play a track, verify it's highlighted in Collection/Library. Change tracks, verify highlight moves.

**Step 5:** Commit: `feat: highlight currently playing track in all views`

---

## Task 9: Source Badge Containment

**Problem:** Tidal/Local badge overhangs the cover art in grid view. GTK4 CSS doesn't support `overflow: hidden`.

**Files:**
- Modify: `auxen/views/collection.py`, `auxen/views/library.py`, `auxen/views/home.py` — adjust badge margins
- Modify: `data/style.css` — badge positioning

**Step 1:** The badge is an overlay child positioned with `halign=END, valign=START` and `margin-top: 6px, margin-right: 6px`. The overhang happens when the badge is wider than the remaining space.

**Step 2:** Increase `margin-right` to `10px` and add `margin-top: 8px` to pull badge further inside. Also ensure the art container has the same border-radius as the badge corner.

**Step 3:** If badge still overhangs, reduce badge font-size or padding to make it smaller.

**Step 4:** Test in grid view across Collection, Library, Home, and Explore.

**Step 5:** Commit: `fix: source badge contained within cover art bounds`

---

## Task 10: Normalize Volume + Preferred Audio Sink

**Problem:** Missing volume normalization and audio sink selection from Settings.

**Files:**
- Modify: `auxen/player.py` — ReplayGain is already implemented, just needs UI exposure
- Modify: `auxen/views/settings.py` — add audio sink selector
- Modify: `auxen/db.py` — persist audio sink preference

**Step 1:** ReplayGain normalization already works (rgvolume + rglimiter in pipeline). Verify Settings UI exposes the toggle and mode selector (album/track). If already there, this sub-item is done.

**Step 2:** For audio sink: enumerate available GStreamer audio sinks using `Gst.ElementFactory.find()` or `Gst.DeviceMonitor`. Add a dropdown in Settings.

**Step 3:** Store selected sink in DB. On startup, set `playbin3.set_property("audio-sink", selected_sink)`.

**Step 4:** Test: change audio sink in Settings, verify playback uses the new sink.

**Step 5:** Commit: `feat: preferred audio sink selector in Settings`

---

## Task 11: Artist/Album Properties Dialog

**Problem:** No way to view detailed properties for artists/albums, or set custom cover art.

**Files:**
- Create: `auxen/views/properties_dialog.py` — new dialog
- Modify: `auxen/views/context_menu.py` — add "Properties" option for artists/albums
- Modify: `auxen/window.py` — wire dialog

**Step 1:** Create `ArtistPropertiesDialog` using `Adw.Dialog` showing: name, track count, album count, source, image. Add "Set Cover Art" button using `Gtk.FileDialog`.

**Step 2:** Create `AlbumPropertiesDialog` showing: title, artist, year, track count, source, cover art. Add "Set Cover Art" button.

**Step 3:** Store custom cover art path in DB (new `custom_art` column or settings key).

**Step 4:** Add "Properties" option to artist and album context menus.

**Step 5:** Test: right-click artist → Properties → verify info shows. Set custom art → verify it persists.

**Step 6:** Commit: `feat: artist/album properties dialog with custom cover art`

---

## Task 12: Album Scrollbar Overlap on Artist Detail

**Problem:** Album section scrollbar overlaps with text below on artist detail page.

**Files:**
- Modify: `auxen/views/artist_detail.py` — fix scrollable area
- Modify: `data/style.css` — scrollbar spacing if needed

**Step 1:** Find the albums grid/flow in artist_detail.py. Check if it's in a `Gtk.ScrolledWindow`. The overlap likely means the scrollbar extends beyond the scrolled area.

**Step 2:** Add `margin-bottom` to the scrolled window or the content below it to create spacing.

**Step 3:** Alternatively, if the scrollbar is an overlay scrollbar (default in GTK4), ensure the content below has enough top margin.

**Step 4:** Test: navigate to an artist with many albums, scroll the albums section, verify no overlap.

**Step 5:** Commit: `fix: album scrollbar no longer overlaps text on artist detail page`

---

## Execution Notes

- Tasks 1-2 are bug fixes — do first
- Tasks 3-4 are search improvements — do together
- Task 5 (marquee) is complex — can be deferred if time-constrained
- Tasks 6-7 are data fixes — quick wins
- Task 8 requires touching multiple views — moderate effort
- Tasks 9-10 are polish — do after core fixes
- Tasks 11-12 are smaller items — finish last
