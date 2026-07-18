# Search UX: live autocomplete suggestions (backlog — grounded, ready to expand)

**Status:** captured backlog item (user request 2026-07-17). Grounded against the live API. Expand into
a full TDD plan (Files/Interfaces/steps) when it reaches the build queue — after context menus + Slice 2.

## What already exists (do NOT rebuild)

**Recent searches is already implemented and working.** When the search box is empty, `SearchScreen.kt`
(~L171) renders DB-backed recent queries with tap-to-rerun, per-item delete, and clear-all:
- `SearchHistoryEntity` + `search_history` table (`db/Entities.kt`), `SearchHistoryDao`
  (`put`/`recent(limit): Flow`/`delete`/`clear`, `db/Daos.kt:174`).
- `PlayerViewModel.searchHistoryItems: StateFlow<List<String>>` (`Graph.library.searchHistory()`),
  `addSearchHistory` (on keyboard submit, PlayerViewModel.kt:669), `clearSearchHistory`,
  `deleteSearchHistory`.

So the improvement to build is NOT recent-searches — it is **live, as-you-type suggestions** shown
*while typing a query* (before submit), complementing the recent-searches-when-empty that already ships.

## The feature

While the user types (debounced), show a suggestions list that updates per keystroke. Two sources,
both grounded below — recommend combining them:
1. **Recent searches matching the current prefix** (local, instant, zero network) — filter
   `searchHistoryItems` by `startsWith`/`contains` the current text. Free, offline, already have the data.
2. **Live Tidal autocomplete** (network, debounced ~250-300ms) — mixed typed suggestions that navigate
   straight to a detail page on tap.

Tapping a suggestion: an artist/album/track/playlist suggestion navigates to its detail page (reusing
the existing `artistRoute`/`albumRoute` etc. — same as multi-type search results); a plain recent-query
string fills the box and runs the full search.

## Probed API (GROUND TRUTH, 2026-07-17, live desktop session)

**`GET https://api.tidal.com/v1/search/autocomplete`** — params `query`, `limit`, `countryCode` — 200.
Returns `{ items: [...], totalNumberOfItems, limit, offset }`. Each item is a **typed wrapper**
`{ item: {...}, type }` mixing artist / album / track / playlist. Probed `query=metalli` → Metallica
(artist: `item.id`, `item.name`, `item.picture`), Metallica (album: `item.id`, `item.title`,
`item.cover`), a track (`item.id`, `item.title`), a playlist (`item.uuid`, `item.title`, `item.image`).
- Artist picture = 480px (`tidalV1ArtistPictureUrl`); album/track cover = 640px (`tidalV1CoverUrl`) —
  same split as everywhere; REUSE, don't re-declare.
- This is the SAME wrapper/DTO family as `pages/artist` and multi-type search — reuse `TidalV1Page*`
  DTOs / `TidalMultiTypeSearch` parsing primitives; do not fork.

**Alternative source (no new endpoint):** the existing multi-type `search` already returns good hits on
partial input ("meta" → Metallica, Metalachi, …) plus a `topHit`. If we'd rather not add an endpoint,
debounced multi-type search on the partial query is a viable suggestion source and reuses code we just
shipped. `search/suggest` and `search/topHits` both 404 — do NOT use them.

## Design notes / decisions to make at build time

- **Debounce** the network suggest (~250-300ms) and cancel in-flight on new keystrokes (generation
  token, like the artist-page loader). Recent-prefix matching is instant/local, shown immediately.
- **Layout:** suggestions replace the recent-searches list while the box has text; recent-searches
  still shows when the box is empty. Keep it a simple list (icon + label + type hint), not the full
  tabbed results — the tabbed results appear on submit (today's behavior).
- **Not-logged-in / offline:** fall back to recent-prefix matches only; never crash or block typing.
- **Pure parse unit-tested** (autocomplete JSON → typed suggestion list; empty/missing/malformed →
  empty). No live-HTTP test.
- **Open question for the user:** dedicated `search/autocomplete` vs reusing multi-type `search` on
  partials. Autocomplete is purpose-built and lighter; multi-type reuses shipped code and adds a
  `topHit`. Decide at build time.

## Binding constraints (carry into the full plan)

- Internal-v1 only (official v2 is retired). Additive read-only. Never log tokens.
- Gradle lines carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`. Git: explicit pathspecs.
- Never re-declare an existing type (reuse `TidalV1Page*` / multi-type-search DTOs + the artwork
  helpers); ADD imports where new code names an unimported type.
- Reuse `artistRoute`/`albumRoute`/playlist route for suggestion taps — do not invent routes.
