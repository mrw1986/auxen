# Artist Page Rebuild — one call, the whole Tidal artist page

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild `ArtistDetailScreen`'s remote half on the single internal-v1 read `GET api.tidal.com/v1/pages/artist`, which returns the entire Tidal artist page in one response: the **biography**, **Fans Also Like with photos**, and the discography **already categorised by Tidal** into Albums / EP & Singles / Live albums / Compilations / Appears On. This replaces a four-call fan-out (official-v2 `artistDetail` + internal-v1 `artistBio` + `artistAlbums` + `artistTopTracks`) that produced, on the user's real device, **no biography**, **placeholder avatars**, and **a discography of 20 "Fixxxer" rough mixes**.

**User decision (2026-07-16 — locked, implement it):** *"Yes — rebuild the artist page on pages/artist."*

**After this, official v2 contributes NOTHING to the artist page's content.** The user knows and accepted it. Official v2 keeps exactly one job here — `trackRelations` in the id-resolution chain (§ *Artist-id resolution*) — and keeps its Slice-1 roles on the album and track screens untouched.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp (raw), Jetpack Compose + Navigation-Compose, JUnit4 + Robolectric + Roborazzi. Manual `Graph` service-locator DI (`AuxenApp.kt:137`). minSdk 26 / compileSdk 35.

---

## Ground truth — read before writing a line

`.superpowers/sdd/pages-artist-schema.md`, captured by hitting the live API with the desktop's own session on 2026-07-16. **It is ground truth. Do not guess at shapes; do not contradict it.** Everything below is traceable to it, to real Kotlin in this repo, to `tidalapi` on this machine (`/home/mrw1986/.local/lib/python3.14/site-packages/tidalapi/`), or to the Python desktop at `auxen/`.

**Request:** `GET https://api.tidal.com/v1/pages/artist` with **only** `artistId`, `deviceType=BROWSER`, `locale=en_US`, `countryCode=US`.
**Sending `limit` returns 400.** tidalapi's `request.request` auto-injects `limit=1000`, which is why a naive call through it fails; the desktop's own `session.page.get` (`auxen/providers/tidal.py:410`) works because it takes a different path. Our `TidalProvider.get()` sends no params of its own, so a bare `HttpUrl` with those four parameters is exactly right.

**Response:** `{selfLink, id, title, rows[]}`; each row has `modules[]`; list modules carry `pagedList.{items, totalNumberOfItems, limit, offset}`.

| module `type` | 8405 (Metallica) `title` / count | 3634161 `title` / count |
|---|---|---|
| `ARTIST_HEADER` | `''` | `''` |
| `TRACK_LIST` | 'Top Tracks' 300 | 'Top Tracks' 300 |
| `ALBUM_LIST` | 'Albums' 16 | 'Albums' 25 |
| `ALBUM_LIST` | 'EP & Singles' 47 | 'EP & Singles' 8 |
| `ALBUM_LIST` | 'Compilations' 1 | 'Compilations' 12 |
| `ALBUM_LIST` | 'Live albums' 7 | 'Live albums' 8 |
| `MIXED_TYPES_LIST` | 'Playlists' 79 | 'Playlists' 119 |
| `VIDEO_LIST` | 'Videos' 33 | 'Videos' 10 |
| `ALBUM_LIST` | 'Appears On' 17 | 'Appears On' 6 |
| `ITEM_LIST_WITH_ROLES` | 'Credits' 462 | 'Credits' 1553 |
| `ARTIST_LIST` | 'Fans Also Like' 15 | 'Fans Also Like' 15 |
| `ARTIST_LIST` | — | **'Influencers' 8** |
| `SOCIAL` | 'Social' | 'Social' |
| `ARTICLE_LIST` | 'Articles' 6 | 'Articles' 38 |

## Why one call beats what shipped (the rationale — keep it in the file)

`artists/{id}/albums?limit=50` returns **43 albums, flattened**. That is why the user's device showed 20 *"Fixxxer"* rough mixes as a discography and an `S&M2 (Live)` duplicate. `pages/artist` **already categorises them**: 'Albums' = 16 real studio albums (*72 Seasons*…), the Fixxxer rough mixes live under 'EP & Singles', `S&M2 (Live)` under 'Live albums', `Garage, Inc.` under 'Compilations'. **Tidal did the curation; the flat endpoint just discards it.**

Three more things one call buys that the four-call fan-out could not:

1. **`ARTIST_HEADER.bio.text` carries the biography** — the thing official v2 returns null for at our tier (proven on-device for Metallica *and* Tommy Richman; see `TidalMetadataRepository.kt:99-108`).
2. **`ARTIST_LIST` items carry `picture`** — the photos the user asked for (*"the tidal app itself shows the pics. can we make sure we have the same info available?"*). Slice 1's similar artists had `artworkUrl == null` **by construction**: a similar artist's own `profileArt` is not flatly include-able from an official artist document (`TidalMetadataDetail.kt:213-215`).
3. **Four sequential network round-trips collapse to one.**

---

## THE CENTRAL DESIGN DECISION — titles are DATA, never selectors

The brief flags module→title matching as fragile: modules are identified by `type` plus a **human-readable, locale-dependent `title`** ('Albums', 'EP & Singles', 'Fans Also Like'). We send `locale=en_US` so the titles are stable *for us* — but this is an undocumented reverse-engineered endpoint and **Tidal owes us nothing**.

**So this plan does not match on titles at all. It selects on `type` only, and renders each module under the title Tidal gave it.**

- Every `ALBUM_LIST` module becomes its own titled carousel, in page order. We never ask "is this one 'Albums'?".
- Every `ARTIST_LIST` module becomes its own titled carousel. This is why artist 3634161's **'Influencers'** row — which no title-matching design would have found — ships for free.
- `TRACK_LIST` → the first one is the top tracks. One exists; no disambiguation needed.
- `ARTIST_HEADER` → the header. One exists.

**What this buys:**

| Failure title-matching would suffer | What happens here |
|---|---|
| Tidal renames 'EP & Singles' → 'EPs & Singles' | We render "EPs & Singles". Correct. |
| Tidal adds a sixth album category | It renders, for free. |
| Tidal localises titles (or we ever send a device locale) | Headings arrive localised. Correct. |
| Tidal adds a module type we have never seen | Ignored — we only select types we know. |
| A module we expect is missing | The section is simply absent. |

**The ONE residual title/position assumption, stated plainly:** the screen merges the user's **local** albums into `albumSections.first()`. That assumes *the first `ALBUM_LIST` is the artist's primary discography* — true for both probed artists ('Albums' is first in both). It is a **position** assumption, deliberately chosen over a **title** match, because position survives localisation and renaming and a title does not. **If it is ever wrong the failure is cosmetic** — local albums surface under a row headed 'EP & Singles' — **never data loss, never a crash.** If there are no `ALBUM_LIST` modules at all, the screen falls back to today's local-only "Albums" row.

**Totality is a hard requirement, and is enforced at three levels:**

1. **Envelope:** every field of every DTO is nullable or defaulted. The collection fields (`rows`, `modules`, `items`) are **nullable, not merely defaulted** — a default only applies when the key is *absent*, while an explicit `"items": null` makes a non-nullable field throw, and because the page decodes in one pass that would lose the **whole page** over one null in a section we do not even render. A body that is not a page at all (a v1 error envelope, an HTML error page, not-JSON) yields **null** → no enrichment → the screen renders exactly as it does today.
2. **Module:** an unrecognised `type` is never looked at. A missing module yields an absent section. A module with an empty `pagedList` yields an absent section (no empty carousel under a lonely header).
3. **Item:** items are decoded **one at a time** under `runCatching`. One malformed item is dropped; its siblings survive. A single bad row can never cost the user a whole section.

Every one of those is a named unit test in Task 1.

---

## Two findings that change the design — do not "simplify" past them

Both were caught by reading the probe and the sources rather than assuming. Both would have shipped as bugs.

### Finding 1 — `pages/artist` items have NO singular `artist` key. Reusing `toTrack` would blank every artist name.

The probed `TRACK_LIST` item keys are:

```
['accessType', 'adSupportedStreamReady', 'ai', 'album', 'allowStreaming', 'artists', 'audioModes',
 'audioQuality', 'djReady', 'doublePopularity', 'duration', 'editable', 'explicit', 'id',
 'mediaMetadata', 'mixes', 'payToStream', 'popularity', 'replayGain', 'spotlighted', 'stemReady',
 'streamReady', 'streamStartDate', 'title', 'trackNumber', 'upload', 'url', 'version', 'volumeNumber']
```

`artists` (plural array) is present. **There is no singular `artist`.** `ALBUM_LIST` items are the same: `artists` present, no `artist`.

`tidalapi` confirms this is expected, not a probe artifact (`tidalapi/media.py:239-245`):

```python
artists = self.session.parse_artists(json_obj["artists"])
# Sometimes the artist field is not filled, example: 62300893
if "artist" in json_obj:
    artist = self.session.parse_artist(json_obj["artist"])
else:
    artist = artists[0]
```

`TidalProvider`'s `TrackDto` reads `val artist: ArtistDto = ArtistDto()` (`TidalProvider.kt:60`) — a **singular** object defaulting to `name = ""`. Mapping a `pages/artist` track through it would yield **`Track.artist == ""`** on every top-track row. So the page projection **must read `artists.firstOrNull()?.name`**.

This is moot for reuse anyway: `TrackDto`, `SearchResponse` and `toTrack()` are **`private` nested inside `TidalProvider`** (`TidalProvider.kt:44-67`, `:292`). A pure function in another file **cannot reference them at all**. The page projection therefore declares its own DTOs. That is not a re-declaration of an existing type — different names, different scope.

### Finding 2 — artist pictures are NOT 640x640. Reusing `tidalV1CoverUrl` would 404 every photo.

The brief said to check whether the codebase already has the artwork-URL helper and reuse it. It does — `tidalV1CoverUrl` (`TidalInternalArtist.kt:144-146`), which builds **`.../640x640.jpg`**. **It is correct for album covers and wrong for artist pictures.** Two independent sources:

- `tidalapi/artist.py:283-293` — `Artist.image(dimensions=320)`: *"Valid resolutions: 160x160, 320x320, 480x480, 750x750"*, and it **raises `ValueError`** for anything else. **640 is not valid for an artist.**
- `tidalapi/album.py:243-256` — `Album.image()`: *"Valid resolutions: 80x80, 160x160, 320x320, 640x640, 1280x1280, 3000x3000"*. **480 and 750 are not valid for an album.**

The **Auxen desktop agrees**, and builds artist images at **480x480** in both places it does so (`auxen/providers/tidal.py:883` and `:1017`):

```python
img_fn = getattr(best, "image", None)
if callable(img_fn):
    image_url = img_fn(480)
elif isinstance(img_fn, str) and img_fn:
    image_url = (
        f"https://resources.tidal.com/images/"
        f"{img_fn.replace('-', '/')}/480x480.jpg"
    )
```

…while album covers use 640 (`auxen/providers/tidal.py:597`, `:1502`).

**So: `tidalV1CoverUrl` (640) is REUSED for album covers, and a new `tidalV1ArtistPictureUrl` (480) is added for artist pictures.** Two builders, because the CDN has two size sets. Shipping 640 for a picture would have reproduced the exact bug we are here to fix — *"Artist images work for some but not all"* is a live, known issue in this project's own memory.

---

## What this REPLACES — the fate of every call, with the grep that justifies it

Grep run at `android/app/src` over `--include=*.kt` (main **and** test):

```
grep -rn "artistDetail(\|\.artistBio(\|\.artistAlbums(\|\.artistTopTracks(\|searchArtistIdExact(\|trackRelations(" --include=*.kt .
```

| Call | Layer | Callers today | Fate |
|---|---|---|---|
| `TidalMetadataRepository.artistDetail(artistId)` | official v2 | `PlayerViewModel.kt:481` **only** | **UNWIRED** in Task 3 → **zero callers** → **DELETED** in Task 6 (gated on the Task 5 on-device pass) |
| `artistDetailFrom` / `TidalArtistDetail` / `ARTIST_DETAIL_INCLUDE` | official v2 | `TidalMetadataRepository.kt:110-116`, `TidalArtistDetailTest.kt` | **DELETED** in Task 6 with their owner |
| `TidalProvider.artistBio(artistId)` | internal v1 | `PlayerViewModel.kt:504` **only** | **UNWIRED** → **zero callers** → **DELETED** in Task 6 (with `artistBioUrl`, `artistBioFrom`, `TidalV1Bio`) |
| `formatTidalBio(raw)` | pure | `artistBioFrom` (`TidalInternalArtist.kt:322`) | **KEPT and REUSED** — `ARTIST_HEADER.bio.text` is the same endpoint's markup; see below |
| `TidalProvider.artistAlbums(artistId)` | internal v1 | `PlayerViewModel.kt:323` (**debug probe**), `:513` | **UNWIRED** from the page; **KEPT for the probe** |
| `TidalProvider.artistTopTracks(artistId)` | internal v1 | `PlayerViewModel.kt:324` (**debug probe**), `:515` | **UNWIRED** from the page; **KEPT for the probe** |
| `TidalProvider.searchArtistIdExact(name)` | internal v1 | `PlayerViewModel.kt:327`, `:329` (probe), `:479` (**id chain**) | **KEPT — still live**, step 3 of the id chain |
| `TidalMetadataRepository.trackRelations(id)` | official v2 | `PlayerViewModel.kt:308` (probe), `:478` (**id chain**), `:553` (**`loadAlbumDetail`**) | **KEPT — still live**, step 2 of the id chain + the album screen |
| `dedupRemoteAlbums` / `normalizeTitleForDedup` | pure | `ArtistDetailScreen.kt:126` | **KEPT — still live**, now applied per album section |
| `mergeArtistTracks` | pure | `ArtistDetailScreen.kt:120` | **KEPT — still live**, now fed `page.topTracks` |
| `AlbumSummary` / `ArtistLink` | domain | page projection, `albumDetailFrom` | **KEPT and REUSED** — never re-declared |

**`artistAlbums`/`artistTopTracks` are kept, not deleted, and the reason is specific:** `runTidalIdEquivalenceProbe` (`PlayerViewModel.kt:303-387`) calls all three internal-v1 artist reads to generate the readout that is **the documented gate for this whole series**. Deleting them breaks the gate. They stop feeding the artist page; they stay as the probe's evidence-generating calls.

**`formatTidalBio` is reused deliberately.** `ARTIST_HEADER.bio.text` and `artists/{id}/bio`'s `text` are the same content from the same service, so the same `[wimpLink]`/`<br>`/HTML markup must be assumed. `formatTidalBio` (`TidalInternalArtist.kt:298-304`) already handles it, is unit-tested, and is **idempotent on plain prose** — applying it to a bio that happens to be clean is a no-op. Not applying it risks a raw `[wimpLink artistId="123"]` reaching the screen. Its owner `artistBioFrom` dies in Task 6; the formatter outlives it.

---

## What ships, what is deferred, what is escalated

The brief is explicit: *"You do not have to ship every one — propose a sensible order and say explicitly what you deferred and why."*

### Shipping — section order

Header → Play All/Shuffle → **Biography** → **each `ALBUM_LIST` as its own titled carousel** → **each `ARTIST_LIST` as its own titled carousel (with photos)** → **Top tracks** (list, last).

**This is today's shipped order with two rows generalised**, not a new design:

- today's single "Albums" carousel → N titled carousels (`ArtistDetailScreen.kt:211-246`)
- today's "Similar artists" carousel → the `ARTIST_LIST` carousels, now with real photos (`ArtistDetailScreen.kt:247-281`)
- Biography, header, buttons and the trailing track list keep their current positions exactly.

**Preserving the shipped order is a deliberate choice, not an oversight.** See *Open question 1* — the Tidal app puts Top Tracks second, right under the header; Auxen puts tracks last. Changing that is a UX decision and I am not inventing it.

| Section | Source | Ships? |
|---|---|---|
| Biography | `ARTIST_HEADER.bio.text` → `formatTidalBio` | **YES** — the headline fix |
| Artist photo | `ARTIST_HEADER.artist.picture` @ **480** | **YES** |
| Top tracks | `TRACK_LIST` `pagedList.items`, capped at 50 | **YES** |
| Albums / EP & Singles / Live albums / Compilations / Appears On | every `ALBUM_LIST`, own title, page order | **YES** — the categorisation is the whole point |
| Fans Also Like (+ Influencers) | every `ARTIST_LIST`, own title, **`picture` @ 480** | **YES** — the photos the user asked for |

### Deferred — with the reason, not a shrug

| Section | Why it is not shipping |
|---|---|
| **Videos** (`VIDEO_LIST`, 33/10) | **DROPPED — not projected, not rendered.** Auxen has no video surface. Precedent: Slice 1 Task 4 had to filter video items out of album tracklists because a video id routed to the internal-v1 **track** stream endpoint (`getStreamInfoById`) simply fails — see `albumDetailFrom`'s KDoc (`TidalMetadataDetail.kt:255-263`): *"a silently-unplayable row is strictly worse than an absent one"*. **Do not invent a video player.** If a video surface ever arrives it wants its own projection and its own list — not a `Track` that lies about what it is. |
| **Playlists** (`MIXED_TYPES_LIST`, 79/119) | **DEFERRED — there is nowhere to navigate to.** The only playlist route is `composable("playlist/{playlistId}")` (`MainActivity.kt:371-377`), which parses `playlistId.toLongOrNull()` and opens a **local** `PlaylistEntity` from the DB. Tidal playlists are **UUID**-keyed (`"uuid": "4591d040-7a10-455f-9a1e-e31eeb6eac62"` in the probe) and have no screen. A carousel whose every tap does nothing is worse than no carousel. Shipping it means first building a Tidal playlist detail screen — its own feature, its own plan. |
| **Artist Mix** (`ARTIST_HEADER.artistMix.id` / `.mixes.ARTIST_MIX`) | **DEFERRED — same reason, no surface.** There is no mix route in `MainActivity`. Deliberately **not projected**: this plan does not add fields nothing reads. The id is one line away in `ARTIST_HEADER` whenever a mix surface lands. |
| **Fan count** | **NOT AVAILABLE — do not chase it.** The brief probed `fans`/`followers`/`numberOfFollowers`: **absent** from this payload. The artist object has `popularity` (0–100; Metallica = 89), which is **NOT** the Tidal app's "78K fans". Recorded as a known gap; do not "derive" one from `popularity`. |

### Escalated — I am not inventing these

Per the brief: *"If a section needs a real UX decision … flag it as an open question and stop."*

**Open question 1 — section order.** The Tidal app puts Top Tracks directly under the header; Auxen's shipped artist page puts the track list last, below the carousels. Both are defensible. Moving tracks up would push every carousel below a 50-row list; leaving them last diverges from the "Tidal-app parity" ask. **This plan preserves the shipped order.** If the user wants Tidal's order, it is a reordering of `item {}` blocks in Task 4 and nothing else changes.

**Open question 2 — Credits (462 entries for Metallica, 1553 for artist 3634161).** `ITEM_LIST_WITH_ROLES` items are `{item: <track>, roles: [...], type}`. How 1553 role-tagged tracks are presented on a phone is a genuine UX design problem — a flat list is unusable, and grouping/filtering/paging it is a screen, not a section. **I am not designing it.** Needs a user decision before it can be planned.

**Open question 3 — Articles (6/38) and Social.** `ARTICLE_LIST` items are `{title, link, date, images}` pointing at `tidal.com/magazine/...`; `SOCIAL` carries external profile links. Both would mean **launching an external browser out of a music player**, which no other Auxen surface does. Whether that is wanted at all is a product call, not an engineering one. **Escalating rather than building.**

**Open question 4 — the "Appears On" artist label (known limitation, shipping).** `AlbumSummary` has no artist-name field (`TidalMetadataDetail.kt:21-28`), and `TidalV1Album` models only the singular `artist` (always null here — Finding 1), not `artists`. So an "Appears On" card taps through as `onOpenAlbum(title, page.name ?: artist, summary.id)` — i.e. labelled *Metallica* when the album is really *Swizz Beatz Presents G.H.E.T.T.O. Stories*. **Impact is small and self-correcting:** `tidalAlbumId` is non-null, so `AlbumDetailScreen` fetches the album's own document and displays the correct artist; the wrong name is only the local-grouping fallback arg. **This is the same behaviour today's tidal-only album row already has** (`ArtistDetailScreen.kt:237`). The fix is additive (`AlbumSummary(..., val artistName: String? = null)` + an `artists` field on a page-album DTO) and I have deliberately left it out rather than expand scope. Flagging, not fixing.

### Known gaps recorded

- **No fan count** in this payload. `popularity` ≠ fans. (Brief item 7.)
- **`AlbumSummary.albumType` is null for every page album** — `ALBUM_LIST` items carry **no `type` key** (see the probed key list). This is **inert and arguably an upgrade**: nothing on the artist page renders `albumType` (grep: only `AlbumDetailScreen.kt:141`, off `TidalAlbumDetail`, and the debug probe at `PlayerViewModel.kt:344`), and the **module title carries information `type` cannot** — a live album and a studio album are both `type == "ALBUM"`, but Tidal files them under 'Live albums' and 'Albums'.
- **Whether `pagedList.items` is fully materialised is unverified.** The probe's count column (Top Tracks 300, Credits 1553) may be `len(items)` or `totalNumberOfItems`. **It does not affect correctness** — the projection takes what is there — but it affects payload size and how full a carousel looks. **Task 2's on-device probe prints the real counts and answers it.** That is what the gate is for.
- **The quality mapping is duplicated** from `TidalProvider.toTrack` (`TidalProvider.kt:296-303`). See Task 1 Step 5 for why, and the precedent that already sanctions it.

---

## Artist-id resolution — unchanged, restated once

`ArtistDetailScreen` is keyed by artist **name**; `pages/artist` needs an **id**. The chain is **exactly today's** (`PlayerViewModel.kt:477-480`), first hit wins — this plan changes what happens *after* the id resolves, never how it resolves:

1. **`tidalArtistId` nav arg** — already known (a similar-artist tap).
2. **Seed from a local Tidal track by this artist** → `sourceId` → `TidalMetadataRepository.trackRelations` → `artistId`. Gate-verified on-device (Slice 1 Task 1). The seed **MUST** stay `tracks.firstOrNull { it.isTidal && it.artist == artist }` (`ArtistDetailScreen.kt:113`) — the reasoning at `ArtistDetailScreen.kt:105-112` is load-bearing and unchanged.
3. **`TidalProvider.searchArtistIdExact(artistName)`** — internal-v1, **EXACT case-insensitive match only**. The desktop's `artists[0]` fallback stays deliberately un-ported (`TidalInternalArtist.kt:203-224`). Do not "restore parity" by re-adding it.
4. **Nothing resolves → no remote enrichment.** The page renders exactly what it renders today.

**The id-equivalence gate already covers this direction.** `runTidalIdEquivalenceProbe` proved on-device that an artist id read off official v2 **is accepted by internal-v1** (`PlayerViewModel.kt:343-354`) — that is the same id `pages/artist` takes as `artistId`.

---

## Global Constraints

- **Additive, read-only internal-v1 only.** This plan adds exactly **ONE** new call to `TidalProvider`: `artistPage(artistId)` → `GET api.tidal.com/v1/pages/artist`. It is a catalog GET: it resolves no stream and touches no session state.
- **Internal-v1 auth and streaming are UNTOUCHED.** `TidalAuth.kt` gets no change of any kind. `restoreSession`, `logout`, `validToken`, `get`, `search`, `getStreamInfo`, `getStreamInfoById`, `toTrack` and the existing private DTOs are **not edited** and no existing method changes behaviour or signature. Confirm with `git status` at Task 5.
- **Playback is unchanged and routes exactly where it routes today.** Tracks projected from this page are ordinary `Track(source = Source.TIDAL, sourceId = <catalog id>)` values that play through the *existing* internal-v1 pipeline (`Graph.mediaItemFor` → `auxen://tidal/<id>` → `TrackResolver` → `TidalProvider.getStreamInfoById`). **No new playback code.** Album cards carry `tidalAlbumId` into the existing `AlbumDetailScreen` (`MainActivity.kt:348-360`), whose remote tracklist is already playable. **Videos are never projected**, so no video id can ever reach the track stream endpoint.
- **Never re-declare an existing type.** Kotlin "conflicting declarations" is a compile error and has bitten this series repeatedly. Reuse `AlbumSummary`, `ArtistLink`, `TidalV1Album`, `TidalV1AlbumArtist`, `tidalV1CoverUrl`, `releaseYearOf`, `formatTidalBio`, `dedupRemoteAlbums`, `mergeArtistTracks`, `normalizeTitleForDedup`. The full existing top-level namespace of `io.github.auxen.provider.tidal` was enumerated before writing this plan; every new name below is unused.
- **Conversely: ADD THE IMPORT.** Kotlin resolves per file. New code in an existing file naming a type it does not import is a compile error — the other half of the same trap. Every step below lists its imports.
- **`TidalMetadataRepository`'s constructor is FROZEN and holds no `TidalProvider`** — that is why internal-v1 composition happens in the **ViewModel**. Follow that precedent (`TidalMetadataRepository.kt:66-80`, `TidalMetadataDetail.kt:40-42`). **`artistPage` does NOT go on the repository.**
- **Never log or print `accessToken`/`refreshToken`.** Probe readouts carry only human-readable names/titles/ids/counts — never a token, never a full request URL. PKCE is a public client and **never sends `client_secret`**.
- **Do not unit-test live HTTP.** Unit-test PURE logic only: the request shape and the body→domain projection. The suspend/network wrapper on `TidalProvider`, the `PlayerViewModel` composition and the Compose screen are NOT unit-tested and close on the Task 5 on-device checkpoint — established precedent.
- **Gradle lines MUST carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`** (the system JDK 25 breaks Gradle).
- **Git: explicit pathspecs only.** Never `git add -A` / `git add .` — another agent may be committing Kotlin concurrently.
- **Roborazzi goldens must not shift.** `AlbumCard`, `AuxenTrackRow` and `ArtworkImage` have goldens (`app/src/test/screenshots/album-card-{light,dark}.png`, `track-row-{local-explicit,tidal-favorite}-{light,dark}.png`). This plan reuses those components and **passes no new defaults into them**. **A shifted golden means a leaked default — fix the default, never re-record.**
- Every code step below shows the actual code. No `TODO`, no "add error handling", no "similar to above".

---

### Task 1: The pure page parse — `TidalArtistPage.kt` (the whole value, all of it testable)

This is where nearly all the value and all the testable logic lives: the request shape, the envelope, type-only module selection, item-level totality, the two artwork URL sizes, and the projections.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalArtistPage.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalArtist.kt` (widen `toAlbumSummary` `private` → `internal`; **nothing else**)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistPageTest.kt` (create)

**Interfaces:**
- Consumes: `AlbumSummary`, `ArtistLink` (`TidalMetadataDetail.kt`); `TidalV1Album`, `tidalV1CoverUrl`, `formatTidalBio` (`TidalInternalArtist.kt`); `releaseYearOf` (`TidalMetadataPrimitives.kt`, via `toAlbumSummary`); `io.github.auxen.model.Track` / `Source`.
- Produces: `fun artistPageUrl(apiBase, artistId, countryCode, locale = "en_US"): HttpUrl`; `fun tidalV1ArtistPictureUrl(picture: String?): String?`; `fun artistPageFrom(body: String, json: Json, artistId: String): TidalArtistPage?`; the domain types `TidalArtistPage`, `TidalArtistAlbumSection`, `TidalArtistLinkSection`; the DTOs `TidalV1PagedList`, `TidalV1PageBio`, `TidalV1PageModule`, `TidalV1PageRow`, `TidalV1ArtistPageResponse`, `TidalV1PageArtist`, `TidalV1PageTrackAlbum`, `TidalV1PageTrack`; the internals `TidalV1Quality`, `tidalV1QualityOf`, `TidalV1PageTrack.toTrack()`.
- **Do NOT re-declare:** `AlbumSummary`, `ArtistLink`, `TidalV1Album`, `TidalV1AlbumArtist`, `tidalV1CoverUrl`, `releaseYearOf`, `formatTidalBio`. All exist; naming any of them again is a compile error.
- **Do NOT touch:** `TidalProvider`'s private `TrackDto`/`SearchResponse`/`toTrack()` — they are unreachable from this file by design (Finding 1).

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistPageTest.kt`. The fixture is trimmed verbatim from the probed schema — **note there is no singular `artist` key and no `type` key on the album items; that is the real payload, not an omission**:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalArtistPageTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Trimmed from the live probe of artist 8405 (.superpowers/sdd/pages-artist-schema.md).
     * Deliberately includes:
     *  - an UNKNOWN module type (`SOCIAL`) -> must be ignored, never crash
     *  - a module with an EMPTY pagedList (`ALBUM_LIST` 'Compilations') -> absent section
     *  - a MALFORMED item (the id-less album in 'Live albums') -> dropped, sibling survives
     *  - `artists` plural with NO singular `artist`, and NO `type` on albums (Finding 1)
     */
    private val pageBody = """
        {
          "selfLink": null, "id": "artist", "title": "Metallica",
          "rows": [
            { "modules": [ {
                "type": "ARTIST_HEADER", "title": "", "width": 12,
                "artist": { "id": 8405, "name": "Metallica", "picture": "0a85112a-d1ec-49be-a618-8df9081e9e0c" },
                "bio": { "source": "TiVo", "text": "Metallica were easily the best.<br><br>See [wimpLink artistId=\"721\"]System Of A Down[/wimpLink]." },
                "artistMix": { "id": "0008a92f59f8b301581f70ca9ee481" },
                "mixes": { "ARTIST_MIX": "0008a92f59f8b301581f70ca9ee481" }
              } ] },
            { "modules": [ {
                "type": "TRACK_LIST", "title": "Top Tracks",
                "pagedList": { "limit": 10, "offset": 0, "totalNumberOfItems": 300, "items": [
                  { "id": 166790188, "title": "Enter Sandman", "duration": 332, "explicit": false,
                    "audioQuality": "LOSSLESS", "trackNumber": 1, "volumeNumber": 1,
                    "artists": [ { "id": 8405, "name": "Metallica", "type": "MAIN", "picture": "0a85112a-d1ec-49be-a618-8df9081e9e0c" } ],
                    "album": { "id": 166790187, "title": "Metallica", "cover": "d0747f21-26ff-447c-8d04-0d8f3635e205" } },
                  { "id": 166790200, "title": "One", "duration": 447, "explicit": true,
                    "audioQuality": "HI_RES_LOSSLESS",
                    "artists": [ { "id": 8405, "name": "Metallica" } ],
                    "album": { "id": 1, "title": "…And Justice for All", "cover": null } }
                ] }
              } ] },
            { "modules": [ {
                "type": "ALBUM_LIST", "title": "Albums",
                "pagedList": { "totalNumberOfItems": 16, "items": [
                  { "id": 288984589, "title": "72 Seasons", "cover": "7e907ce5-5c38-4738-8a21-95a8d362ddb4",
                    "releaseDate": "2023-04-14", "explicit": false, "numberOfTracks": 12,
                    "artists": [ { "id": 8405, "name": "Metallica" } ] }
                ] }
              } ] },
            { "modules": [ {
                "type": "ALBUM_LIST", "title": "EP & Singles",
                "pagedList": { "totalNumberOfItems": 47, "items": [
                  { "id": 531884999, "title": "Fixer (\"Fixxxer\" July '97 Rough Mix)", "cover": "1f5511c5-92a2-4448-bb40-f519210729ee",
                    "releaseDate": "1997-07-01", "explicit": false }
                ] }
              } ] },
            { "modules": [ { "type": "ALBUM_LIST", "title": "Compilations",
                "pagedList": { "totalNumberOfItems": 0, "items": [] } } ] },
            { "modules": [ {
                "type": "ALBUM_LIST", "title": "Live albums",
                "pagedList": { "totalNumberOfItems": 7, "items": [
                  { "title": "A malformed album with no id" },
                  { "id": 186461110, "title": "S&M2 (Live)", "cover": "3eb66f06-3a0d-462c-aa2b-6fa93f95a448",
                    "releaseDate": "2020-08-28", "explicit": false }
                ] }
              } ] },
            { "modules": [ { "type": "SOCIAL", "title": "Social", "somethingWeHaveNeverSeen": { "a": 1 } } ] },
            { "modules": [ {
                "type": "ARTIST_LIST", "title": "Fans Also Like",
                "pagedList": { "totalNumberOfItems": 15, "items": [
                  { "id": 721, "name": "System Of A Down", "picture": "b45f6499-0636-4874-9e41-796be9a8c2b8" },
                  { "id": 14670, "name": "Slayer", "picture": null }
                ] }
              } ] },
            { "modules": [ {
                "type": "ARTIST_LIST", "title": "Influencers",
                "pagedList": { "totalNumberOfItems": 8, "items": [
                  { "id": 15637, "name": "Diamond Head", "picture": "cccccccc-dddd-eeee-ffff-000000000000" }
                ] }
              } ] }
          ]
        }
    """.trimIndent()

    private fun page() = artistPageFrom(pageBody, json, artistId = "8405")!!

    // ---- 1. the request shape ----

    @Test
    fun `artistPageUrl sends only artistId, deviceType, locale and countryCode`() {
        val url = artistPageUrl(apiBase = "https://api.tidal.com/v1", artistId = "8405", countryCode = "US")
        assertEquals("api.tidal.com", url.host)
        assertEquals("/v1/pages/artist", url.encodedPath)
        assertEquals("8405", url.queryParameter("artistId"))
        assertEquals("BROWSER", url.queryParameter("deviceType"))
        assertEquals("en_US", url.queryParameter("locale"))
        assertEquals("US", url.queryParameter("countryCode"))
        // Exactly these four and nothing else — see the `limit` test below.
        assertEquals(setOf("artistId", "deviceType", "locale", "countryCode"), url.queryParameterNames)
    }

    @Test
    fun `artistPageUrl sends NO limit - the endpoint answers 400 when it is present`() {
        // THE POINT OF THIS TEST. tidalapi's request.request auto-injects
        // limit=1000 and pages/artist answers 400 (probed 2026-07-16). Our
        // TidalProvider.get() adds no params of its own, so the ONLY way a limit
        // reaches this endpoint is someone adding it here. Do not.
        val url = artistPageUrl("https://api.tidal.com/v1", "8405", "US")
        assertNull(url.queryParameter("limit"))
        assertNull(url.queryParameter("offset"))
    }

    // ---- 2. the header: the biography official v2 has not got ----

    @Test
    fun `artistPageFrom reads the biography out of ARTIST_HEADER and formats the markup away`() {
        // The headline fix. Official v2 returns null here at our tier (proven
        // on-device for Metallica AND Tommy Richman); ARTIST_HEADER.bio.text
        // carries it. formatTidalBio turns <br> into newlines and unwraps
        // [wimpLink] so no raw tag reaches the screen.
        assertEquals(
            "Metallica were easily the best.\n\nSee System Of A Down.",
            page().biography,
        )
    }

    @Test
    fun `artistPageFrom maps the header id and name`() {
        assertEquals("8405", page().id)
        assertEquals("Metallica", page().name)
    }

    @Test
    fun `artistPageFrom builds the artist picture at 480x480, NOT the album 640`() {
        // Finding 2. tidalapi/artist.py:283-293 -- an artist picture's valid
        // resolutions are 160/320/480/750 and image() RAISES for anything else;
        // 640 is an ALBUM size (tidalapi/album.py:243-256). The desktop builds
        // artist images at 480 (auxen/providers/tidal.py:883, :1017). Using
        // tidalV1CoverUrl (640) here would 404 every photo -- which is exactly
        // the "artist images work for some but not all" bug we are fixing.
        assertEquals(
            "https://resources.tidal.com/images/0a85112a/d1ec/49be/a618/8df9081e9e0c/480x480.jpg",
            page().artworkUrl,
        )
    }

    @Test
    fun `tidalV1ArtistPictureUrl tolerates null and blank`() {
        assertNull(tidalV1ArtistPictureUrl(null))
        assertNull(tidalV1ArtistPictureUrl(""))
        assertNull(tidalV1ArtistPictureUrl("   "))
    }

    // ---- 3. Fans Also Like -- WITH the photos the user asked for ----

    @Test
    fun `artistPageFrom carries a picture on every ARTIST_LIST item that has one`() {
        // The user's ask: "the tidal app itself shows the pics. can we make sure
        // we have the same info available?" Slice 1's similar artists were null
        // BY CONSTRUCTION -- a similar artist's profileArt is not flatly
        // include-able from an official artist document. Here it is inline.
        val fans = page().artistSections.first { it.title == "Fans Also Like" }
        assertEquals(listOf("System Of A Down", "Slayer"), fans.artists.map { it.name })
        assertEquals(listOf("721", "14670"), fans.artists.map { it.id })
        assertEquals(
            "https://resources.tidal.com/images/b45f6499/0636/4874/9e41/796be9a8c2b8/480x480.jpg",
            fans.artists[0].artworkUrl,
        )
        assertNull(fans.artists[1].artworkUrl) // picture: null -> the Person glyph
    }

    @Test
    fun `artistPageFrom keeps EVERY ARTIST_LIST under its own title, not just Fans Also Like`() {
        // Artist 3634161 has a SECOND ARTIST_LIST titled 'Influencers'. Selecting
        // on type (never on title) means it ships for free. A title-matching
        // design would have silently dropped it.
        assertEquals(listOf("Fans Also Like", "Influencers"), page().artistSections.map { it.title })
        assertEquals(listOf("Diamond Head"), page().artistSections[1].artists.map { it.name })
    }

    // ---- 4. the categorised discography -- the whole point ----

    @Test
    fun `artistPageFrom keeps Tidal's own album categories, titles and page order`() {
        // artists/{id}/albums returns 43 albums FLATTENED, which is why the
        // device showed 20 Fixxxer rough mixes as a discography. pages/artist
        // already did the curation. 'Compilations' is absent because its
        // pagedList is empty -- an empty carousel under a lonely header is worse
        // than no section.
        assertEquals(listOf("Albums", "EP & Singles", "Live albums"), page().albumSections.map { it.title })
        assertEquals(listOf("72 Seasons"), page().albumSections[0].albums.map { it.title })
        assertEquals(listOf("Fixer (\"Fixxxer\" July '97 Rough Mix)"), page().albumSections[1].albums.map { it.title })
        assertEquals(listOf("S&M2 (Live)"), page().albumSections[2].albums.map { it.title })
    }

    @Test
    fun `artistPageFrom maps album cover art at 640 and the release year`() {
        val first = page().albumSections[0].albums.single()
        assertEquals("288984589", first.id)
        assertEquals(2023, first.releaseYear)
        assertEquals(false, first.explicit)
        assertEquals(
            "https://resources.tidal.com/images/7e907ce5/5c38/4738/8a21/95a8d362ddb4/640x640.jpg",
            first.artworkUrl,
        )
    }

    @Test
    fun `artistPageFrom leaves albumType null because ALBUM_LIST items carry no type key`() {
        // Probed: ALBUM_LIST item keys have no `type`. Inert -- nothing on the
        // artist page renders albumType -- and arguably an UPGRADE: a live album
        // and a studio album are BOTH type=="ALBUM", but Tidal files them under
        // 'Live albums' and 'Albums'. The module title carries what type cannot.
        assertTrue(page().albumSections.flatMap { it.albums }.all { it.albumType == null })
    }

    // ---- 5. top tracks -- Finding 1 ----

    @Test
    fun `artistPageFrom reads the track artist from artists plural - there is no singular artist key`() {
        // FINDING 1. pages/artist track items carry `artists` and NO `artist`
        // (probed; tidalapi/media.py:239-245 confirms the singular is optional).
        // TidalProvider's private TrackDto reads the SINGULAR and defaults to
        // name="" -- routing this payload through it would blank every artist
        // name on the page. Read artists[0].
        val top = page().topTracks
        assertEquals(listOf("Enter Sandman", "One"), top.map { it.title })
        assertEquals(listOf("Metallica", "Metallica"), top.map { it.artist })
        assertTrue(top.all { it.isTidal })
        assertEquals(listOf("166790188", "166790200"), top.map { it.sourceId })
    }

    @Test
    fun `artistPageFrom maps track album, art, duration, explicit and numbering`() {
        val sandman = page().topTracks.first()
        assertEquals("Metallica", sandman.album)
        assertEquals(332.0, sandman.durationSeconds!!, 0.001)
        assertEquals(false, sandman.explicit)
        assertEquals(1, sandman.trackNumber)
        assertEquals(1, sandman.discNumber)
        assertEquals(
            "https://resources.tidal.com/images/d0747f21/26ff/447c/8d04/0d8f3635e205/640x640.jpg",
            sandman.albumArtUrl,
        )
        assertEquals(true, page().topTracks[1].explicit)
        assertNull(page().topTracks[1].albumArtUrl) // cover: null
    }

    @Test
    fun `artistPageFrom maps audioQuality so the row's quality badge matches the rest of the app`() {
        // Track.qualityLabel returns "Unknown" when format is null (Track.kt:69-79)
        // and AuxenTrackRow renders QualityBadge for every TIDAL row
        // (AuxenTrackRow.kt:118-121). Skipping this mapping would be a VISIBLE
        // regression against the shipped page.
        val (sandman, one) = page().topTracks
        assertEquals("FLAC", sandman.format)
        assertEquals(16, sandman.bitDepth)
        assertEquals("FLAC", sandman.qualityLabel)
        assertEquals("Hi-Res", one.format)
        assertEquals(24, one.bitDepth)
        assertEquals(96_000, one.sampleRateHz)
        assertEquals("Hi-Res", one.qualityLabel)
    }

    @Test
    fun `tidalV1QualityOf mirrors TidalProvider toTrack exactly`() {
        // The duplication this asserts is deliberate and precedented
        // (tidalV1CoverUrl's KDoc, TidalInternalArtist.kt:138-143). These four
        // rows ARE the sync check: TidalProvider.kt:296-303 is the original, and
        // it is private, nested and has never had a test. If the two ever drift,
        // this fails first.
        assertEquals(TidalV1Quality("Hi-Res", 96_000, 24, 4608), tidalV1QualityOf("HI_RES_LOSSLESS"))
        assertEquals(TidalV1Quality("FLAC", 44_100, 16, 1411), tidalV1QualityOf("LOSSLESS"))
        assertEquals(TidalV1Quality("AAC", 44_100, 16, 320), tidalV1QualityOf("HIGH"))
        assertEquals(TidalV1Quality("AAC", 44_100, null, 96), tidalV1QualityOf("LOW"))
        assertEquals(TidalV1Quality(null, null, null, null), tidalV1QualityOf(null))
        assertEquals(TidalV1Quality(null, null, null, null), tidalV1QualityOf(""))
    }

    // ---- 6. TOTALITY -- the parse must never crash and never lose a section ----

    @Test
    fun `artistPageFrom ignores an unknown module type`() {
        // SOCIAL is in the fixture and is not projected. This endpoint is
        // undocumented and reverse-engineered; Tidal owes us nothing and may add
        // a module type any day. An unknown type must be invisible, never fatal.
        assertNotNull(page())
        assertEquals(3, page().albumSections.size)
        assertEquals(2, page().artistSections.size)
    }

    @Test
    fun `artistPageFrom renders a missing module as an ABSENT section, never an error`() {
        val headerOnly = """
            { "rows": [ { "modules": [ {
                "type": "ARTIST_HEADER", "title": "",
                "artist": { "id": 5, "name": "Solo" } } ] } ] }
        """.trimIndent()
        val p = artistPageFrom(headerOnly, json, artistId = "5")!!
        assertEquals("Solo", p.name)
        assertNull(p.biography)
        assertNull(p.artworkUrl)
        assertTrue(p.topTracks.isEmpty())
        assertTrue(p.albumSections.isEmpty())
        assertTrue(p.artistSections.isEmpty())
    }

    @Test
    fun `artistPageFrom drops a section whose pagedList is empty`() {
        // 'Compilations' in the fixture has items: []. No header, no empty rail.
        assertTrue(page().albumSections.none { it.title == "Compilations" })
    }

    @Test
    fun `artistPageFrom drops ONE malformed item and keeps its siblings`() {
        // 'Live albums' in the fixture leads with an id-less album. Items are
        // decoded one at a time under runCatching precisely so a single bad row
        // cannot cost the user a whole section.
        val live = page().albumSections.first { it.title == "Live albums" }
        assertEquals(listOf("S&M2 (Live)"), live.albums.map { it.title })
    }

    @Test
    fun `artistPageFrom survives an explicit null items and still returns the rest of the page`() {
        // A default only applies when the KEY IS ABSENT; an explicit null in a
        // NON-nullable field throws. The page decodes in ONE pass, so a single
        // `"items": null` in a section we do not even render would otherwise cost
        // the whole page — biography and all. `items`/`modules`/`rows` are
        // nullable for exactly this. One null costs one section, never the page.
        val nullItems = """
            { "rows": [
                { "modules": [ {
                    "type": "ARTIST_HEADER", "title": "",
                    "artist": { "id": 8405, "name": "Metallica" },
                    "bio": { "text": "Still here." } } ] },
                { "modules": [ { "type": "ALBUM_LIST", "title": "Albums", "pagedList": { "items": null } } ] },
                { "modules": null },
                { "modules": [ {
                    "type": "ARTIST_LIST", "title": "Fans Also Like",
                    "pagedList": { "items": [ { "id": 721, "name": "System Of A Down" } ] } } ] }
              ] }
        """.trimIndent()
        val p = artistPageFrom(nullItems, json, artistId = "8405")!!
        assertEquals("Still here.", p.biography)
        assertTrue(p.albumSections.isEmpty())
        assertEquals(listOf("System Of A Down"), p.artistSections.single().artists.map { it.name })
    }

    @Test
    fun `artistPageFrom returns null for an error envelope, malformed JSON or a module-less body`() {
        // Every one of these means the same thing to the caller: no enrichment,
        // the page renders exactly as it does today. Never an error, never a crash.
        assertNull(artistPageFrom("""{ "status": 400, "userMessage": "limit is not allowed" }""", json, "8405"))
        assertNull(artistPageFrom("not json at all", json, "8405"))
        assertNull(artistPageFrom("""<html><body>502 Bad Gateway</body></html>""", json, "8405"))
        assertNull(artistPageFrom("""{ "rows": [] }""", json, "8405"))
        assertNull(artistPageFrom("""{ "rows": [ { "modules": [] } ] }""", json, "8405"))
    }

    @Test
    fun `artistPageFrom falls back to the requested id when the header carries no artist`() {
        val noHeaderArtist = """
            { "rows": [ { "modules": [ {
                "type": "ARTIST_LIST", "title": "Fans Also Like",
                "pagedList": { "items": [ { "id": 721, "name": "System Of A Down" } ] } } ] } ] }
        """.trimIndent()
        val p = artistPageFrom(noHeaderArtist, json, artistId = "8405")!!
        assertEquals("8405", p.id)
        assertEquals("", p.name)
    }

    @Test
    fun `artistPageFrom leaves the biography null when the bio is absent or renders to nothing`() {
        // A bio-less artist must read as "no bio" and hide the section -- never
        // as an error. The desktop swallows the identical case in a bare except
        // ("404s are expected for lesser-known artists", providers/tidal.py:911-916).
        val blank = """
            { "rows": [ { "modules": [ {
                "type": "ARTIST_HEADER", "title": "",
                "artist": { "id": 5, "name": "Solo" },
                "bio": { "source": "TiVo", "text": "<br>  <br>" } } ] } ] }
        """.trimIndent()
        assertNull(artistPageFrom(blank, json, "5")!!.biography)
    }
}
```

- [ ] **Step 2: RED — run the tests and watch them fail**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalArtistPageTest*'
```

**Expected: FAIL — the file does not compile.** Every symbol it names is unresolved: `artistPageUrl`, `artistPageFrom`, `tidalV1ArtistPictureUrl`, `tidalV1QualityOf`, `TidalV1Quality`, `TidalArtistPage`, and the `.albumSections`/`.artistSections`/`.topTracks` accessors. This is the RED state: **`TidalArtistPage.kt` does not exist yet.** If it compiles, you are in the wrong tree.

- [ ] **Step 3: Widen `toAlbumSummary` in `TidalInternalArtist.kt`**

`TidalV1Album` already models the `pages/artist` album item correctly — every field the payload has (`id`, `title`, `cover`, `releaseDate`, `explicit`, `numberOfTracks`) and defaults for the two it does not (`type`, singular `artist`). Its mapper is `private`, so the new file cannot reach it. Widen it — a **visibility change only, no behaviour change**, and its one existing caller (`artistAlbumsFrom`, line 161) is unaffected.

At `TidalInternalArtist.kt:164`, change exactly this one line:

```kotlin
private fun TidalV1Album.toAlbumSummary() = AlbumSummary(
```

to:

```kotlin
/**
 * v1 album item -> [AlbumSummary]. `internal` rather than private because
 * `pages/artist`'s `ALBUM_LIST` items are the SAME v1 album shape and
 * [artistPageFrom] maps them through here — one mapper, so the two cannot drift.
 * (Those items carry no `type` key, so `albumType` is null for them; nothing on
 * the artist page renders it, and the module title carries what `type` cannot.)
 */
internal fun TidalV1Album.toAlbumSummary() = AlbumSummary(
```

**Do not change the body.** Leave the existing in-body comments exactly as they are.

- [ ] **Step 4: Create `TidalArtistPage.kt` — the envelope, the URL, the artwork**

Create `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalArtistPage.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of `GET api.tidal.com/v1/pages/artist` — ONE internal-v1 read
 * that returns the ENTIRE Tidal artist page: the biography, the top tracks,
 * the discography **already categorised by Tidal**, and Fans Also Like **with
 * photos**.
 *
 * Probed live 2026-07-16 with the desktop's own session; the captured schema is
 * `.superpowers/sdd/pages-artist-schema.md` and it is ground truth for
 * everything in this file.
 *
 * **Why this replaced a four-call fan-out.** `artists/{id}/albums?limit=50`
 * returns 43 albums FLATTENED — which is why the user's device showed 20
 * *"Fixxxer"* rough mixes as a discography and an `S&M2 (Live)` duplicate.
 * `pages/artist` already categorises them: 'Albums' = 16 real studio albums, the
 * Fixxxer rough mixes under 'EP & Singles', `S&M2 (Live)` under 'Live albums',
 * `Garage, Inc.` under 'Compilations'. Tidal did the curation; the flat endpoint
 * just discards it. It also carries the biography official v2 has not got at our
 * tier, and the similar-artist photos that are not include-able from an official
 * artist document.
 *
 * **THE DESIGN RULE: modules are selected by `type` ONLY, never by `title`.**
 * Titles here are human-readable and locale-dependent ('Albums', 'EP & Singles',
 * 'Fans Also Like'). We send `locale=en_US` so they are stable *for us*, but this
 * is an undocumented reverse-engineered endpoint and Tidal owes us nothing. So we
 * never ask "is this module titled 'Albums'?" — we take EVERY `ALBUM_LIST` and
 * render it under whatever title Tidal gave it. Tidal renaming a category, adding
 * a sixth one, or localising the lot all just work. Artist 3634161's second
 * `ARTIST_LIST` — 'Influencers' — ships for free because of this; a
 * title-matching design would have silently dropped it. **Do not add a title
 * match here.**
 *
 * **The parse is TOTAL, at three levels.** The envelope tolerates anything (every
 * field nullable/defaulted; a non-page body yields null). An unknown module type
 * is never looked at; a missing module is an absent section; an empty `pagedList`
 * is an absent section. And items are decoded ONE AT A TIME under `runCatching`,
 * so one malformed row cannot cost the user a whole section. A failure here must
 * always degrade the artist page to its offline-safe local base — never break it.
 */

/**
 * The v1 paged-collection envelope inside a list module. Items stay raw and are
 * decoded per module type by [artistPageFrom].
 *
 * **[items] is nullable, not just defaulted, and the difference matters.** A
 * default only applies when the KEY IS ABSENT; an explicit `"items": null` makes
 * a non-nullable field THROW — and because the page decodes in one pass, that
 * would lose the ENTIRE page over one null, not one section. Nullable + `.orEmpty()`
 * is what keeps the envelope total. Same reasoning for [TidalV1PageRow.modules]
 * and [TidalV1ArtistPageResponse.rows].
 *
 * The probe shows `pagedList` also carries `limit`/`offset`/`totalNumberOfItems`.
 * They are deliberately unmodelled: nothing reads them, `ignoreUnknownKeys` drops
 * them, and an unmodelled field is one fewer way to throw.
 */
@Serializable
data class TidalV1PagedList(val items: List<JsonElement>? = null)

/** `ARTIST_HEADER.bio` — `{source, text}`. [text] is the biography, and it carries markup. */
@Serializable
data class TidalV1PageBio(val text: String? = null, val source: String? = null)

/**
 * One module. [type] is the ONLY selector (see the file KDoc); [title] is DATA,
 * rendered verbatim. [pagedList] is null on non-list modules (`ARTIST_HEADER`,
 * `SOCIAL`); [artist]/[bio] are populated on `ARTIST_HEADER` only. Everything is
 * nullable — an unknown module must decode cleanly and be ignored, never throw.
 */
@Serializable
data class TidalV1PageModule(
    val type: String? = null,
    val title: String? = null,
    val pagedList: TidalV1PagedList? = null,
    val artist: TidalV1PageArtist? = null,
    val bio: TidalV1PageBio? = null,
)

@Serializable
data class TidalV1PageRow(val modules: List<TidalV1PageModule>? = null)

/**
 * `{selfLink, id, title, rows}`. Only `rows` is modelled; `ignoreUnknownKeys`
 * drops the rest. Nullable for the reason in [TidalV1PagedList]'s KDoc.
 */
@Serializable
data class TidalV1ArtistPageResponse(val rows: List<TidalV1PageRow>? = null)

/**
 * An artist as this page renders one: `ARTIST_HEADER.artist`, an `ARTIST_LIST`
 * item, or an entry in a track's `artists`. Fields verbatim from the probe
 * (`id`, `name`, `picture`; `ARTIST_LIST` items add `artistRoles`/`artistTypes`/
 * `mixes`/`selectedAlbumCoverFallback`, which nothing here reads).
 */
@Serializable
data class TidalV1PageArtist(val id: Long, val name: String = "", val picture: String? = null)

/** A track item's `album`: `{id, title, cover, vibrantColor, videoCover, ...}` — we read three. */
@Serializable
data class TidalV1PageTrackAlbum(val id: Long? = null, val title: String = "", val cover: String? = null)

/**
 * A `TRACK_LIST` item.
 *
 * **[artists] is plural and there is NO singular `artist` field — that is the
 * real payload, not an omission here.** The probed item keys carry `artists` and
 * no `artist`, and `tidalapi` expects exactly that (`tidalapi/media.py:239-245`:
 * it reads `json_obj["artists"]` unconditionally and treats the singular as
 * optional, falling back to `artists[0]`). [TidalProvider]'s own private
 * `TrackDto` reads the SINGULAR and defaults it to `name = ""`, so routing this
 * payload through it would blank every artist name on the page. It is
 * unreachable from this file anyway — it is `private` and nested — which is
 * exactly why this DTO exists. **Do not "unify" the two without fixing that.**
 */
@Serializable
data class TidalV1PageTrack(
    val id: Long,
    val title: String = "",
    val duration: Double = 0.0,
    val explicit: Boolean = false,
    val audioQuality: String? = null,
    val trackNumber: Int? = null,
    val volumeNumber: Int? = null,
    val artists: List<TidalV1PageArtist> = emptyList(),
    val album: TidalV1PageTrackAlbum? = null,
)

/** One titled row of albums, exactly as Tidal grouped and titled it ('Albums', 'EP & Singles', 'Live albums', ...). */
data class TidalArtistAlbumSection(val title: String, val albums: List<AlbumSummary>)

/** One titled row of artists, exactly as Tidal titled it ('Fans Also Like', 'Influencers', ...). */
data class TidalArtistLinkSection(val title: String, val artists: List<ArtistLink>)

/**
 * The Tidal artist page, projected — the artist screen's SINGLE remote source.
 *
 * [albumSections] and [artistSections] are in page order and carry Tidal's own
 * titles; the screen renders each under its own heading and never inspects the
 * strings. [topTracks] are ordinary Tidal [Track]s that play through the
 * existing internal-v1 pipeline unchanged — this layer is metadata only.
 *
 * **Not here, deliberately:** videos (Auxen has no video surface, and a video id
 * routed to the track stream endpoint fails — the same reason `albumDetailFrom`
 * filters them out), playlists (Tidal playlists are uuid-keyed and there is no
 * screen to open one), the artist mix id (no mix surface), and a fan count
 * (**this payload has none** — `fans`/`followers`/`numberOfFollowers` were probed
 * for and are absent; the artist's `popularity` is 0-100 and is NOT the Tidal
 * app's "78K fans"). Do not add a field nothing reads.
 */
data class TidalArtistPage(
    val id: String,
    val name: String,
    val biography: String?,
    val artworkUrl: String?,
    val topTracks: List<Track>,
    val albumSections: List<TidalArtistAlbumSection>,
    val artistSections: List<TidalArtistLinkSection>,
)

/**
 * `GET {apiBase}/pages/artist?artistId=…&deviceType=BROWSER&locale=…&countryCode=…`.
 *
 * **These four params and NOTHING else. Sending `limit` returns 400** — probed
 * live 2026-07-16. That is why a naive call through `tidalapi` fails: its
 * `request.request` auto-injects `limit=1000`. [TidalProvider.get] sends no
 * params of its own, so the only way a limit can reach this endpoint is someone
 * adding it here. `TidalArtistPageTest` asserts its absence.
 *
 * [locale] defaults to the probe-verified `en_US`. Because module titles are DATA
 * and never selectors (see the file KDoc), passing the device locale would give
 * localised headings for free — but that is unverified against this endpoint, so
 * we ship what was probed.
 */
fun artistPageUrl(apiBase: String, artistId: String, countryCode: String, locale: String = "en_US"): HttpUrl =
    "$apiBase/pages/artist".toHttpUrl().newBuilder()
        .addQueryParameter("artistId", artistId)
        .addQueryParameter("deviceType", "BROWSER")
        .addQueryParameter("locale", locale)
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * Artist `picture` uuid -> CDN URL at **480x480**.
 *
 * **NOT [tidalV1CoverUrl], and the difference is load-bearing.** The CDN serves
 * two different size sets:
 *  - **artists**: 160 / 320 / **480** / 750 — `tidalapi/artist.py:283-293`, whose
 *    `image()` RAISES `ValueError` for anything else. **640 is not one of them.**
 *  - **albums**: 80 / 160 / 320 / **640** / 1280 — `tidalapi/album.py:243-256`.
 *
 * The desktop agrees and builds artist images at 480 in both places it does so
 * (`auxen/providers/tidal.py:883` and `:1017`), while album covers use 640
 * (`:597`, `:1502`). Reusing the 640 cover builder for a picture would request a
 * size Tidal does not render — reproducing the exact
 * *"artist images work for some but not all"* bug this rebuild exists to fix.
 * **Do not fold these two builders together.**
 */
fun tidalV1ArtistPictureUrl(picture: String?): String? =
    picture?.takeIf { it.isNotBlank() }
        ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/480x480.jpg" }
```

- [ ] **Step 5: Add the track projection to `TidalArtistPage.kt`**

Append to the same file:

```kotlin
/** The four quality facts a v1 `audioQuality` string implies. */
internal data class TidalV1Quality(
    val format: String?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val bitrateKbps: Int?,
)

/**
 * v1 `audioQuality` -> the quality fields a [Track] carries.
 *
 * **Deliberately duplicated from [TidalProvider]'s `toTrack` (TidalProvider.kt:296-303)
 * rather than refactored out of it** — the exact precedent [tidalV1CoverUrl] set
 * and documented for the same reason (`TidalInternalArtist.kt:138-143`): this
 * plan ADDS to the internal-v1 provider but **changes no existing method of it**,
 * and `toTrack` is on the live search path. `toTrack`'s copy is private, nested
 * and has never had a test; this one is covered by
 * `TidalArtistPageTest.tidalV1QualityOf mirrors TidalProvider toTrack exactly`,
 * which is the sync check between them. Fold the two together in a later change
 * that is allowed to touch `toTrack`.
 *
 * Skipping this mapping is not an option: [Track.qualityLabel] returns "Unknown"
 * when `format` is null (`Track.kt:69-79`) and `AuxenTrackRow` renders a
 * `QualityBadge` on every Tidal row (`AuxenTrackRow.kt:118-121`), so a null
 * format is a visible regression against the shipped page.
 */
internal fun tidalV1QualityOf(audioQuality: String?): TidalV1Quality {
    val quality = audioQuality.orEmpty()
    return when {
        quality.contains("HI_RES") -> TidalV1Quality("Hi-Res", 96_000, 24, 4608)
        quality.contains("LOSSLESS") -> TidalV1Quality("FLAC", 44_100, 16, 1411)
        quality.contains("HIGH") -> TidalV1Quality("AAC", 44_100, 16, 320)
        quality.isNotEmpty() -> TidalV1Quality("AAC", 44_100, null, 96)
        else -> TidalV1Quality(null, null, null, null)
    }
}

/**
 * A page track item -> a playable Tidal [Track] (`source = TIDAL`,
 * `sourceId = <catalog id>`), so it plays through the EXISTING internal-v1
 * pipeline (`Graph.mediaItemFor` -> `auxen://tidal/<id>` -> `TrackResolver` ->
 * `TidalProvider.getStreamInfoById`). No new playback code.
 *
 * `artist` comes from `artists.firstOrNull()` — see [TidalV1PageTrack]'s KDoc;
 * there is no singular `artist` key in this payload. The album cover uses the
 * **640** builder ([tidalV1CoverUrl]) because it IS an album cover; only the
 * artist picture uses 480.
 */
internal fun TidalV1PageTrack.toTrack(): Track {
    val quality = tidalV1QualityOf(audioQuality)
    return Track(
        title = title,
        artist = artists.firstOrNull()?.name.orEmpty(),
        album = album?.title,
        source = Source.TIDAL,
        sourceId = id.toString(),
        durationSeconds = duration,
        trackNumber = trackNumber,
        discNumber = volumeNumber,
        format = quality.format,
        sampleRateHz = quality.sampleRateHz,
        bitDepth = quality.bitDepth,
        bitrateKbps = quality.bitrateKbps,
        albumArtUrl = tidalV1CoverUrl(album?.cover),
        explicit = explicit,
    )
}
```

- [ ] **Step 6: Add the page projection to `TidalArtistPage.kt`**

Append to the same file:

```kotlin
/**
 * Decode each element independently, dropping the ones that fail.
 *
 * **This is the item-level half of the totality guarantee.** Decoding the whole
 * array in one call would let a single malformed row throw and take an entire
 * section with it; here one bad row is dropped and its siblings survive.
 */
private inline fun <reified T> List<JsonElement>.decodeEach(json: Json): List<T> =
    mapNotNull { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

/**
 * Pure projection: a `pages/artist` body -> [TidalArtistPage], or **null**.
 *
 * Null — never an exception — for a v1 error envelope, an HTML error page,
 * not-JSON, or a body with no modules at all. Every one of those means the same
 * thing to the caller: no remote enrichment, and the artist screen renders
 * exactly what it renders today off its local base.
 *
 * [artistId] is the id we ASKED for, used only as the fallback when the header
 * carries no artist object, so the projection always reports a usable id.
 *
 * **Selection is by `type` only** — see the file KDoc. Every `ALBUM_LIST` and
 * every `ARTIST_LIST` becomes a section carrying its own verbatim title, in page
 * order; the first `TRACK_LIST` is the top tracks. A section whose items are all
 * gone (empty `pagedList`, or every item malformed) is dropped rather than
 * rendered as a header over an empty rail.
 */
fun artistPageFrom(body: String, json: Json, artistId: String): TidalArtistPage? {
    val page = runCatching { json.decodeFromString<TidalV1ArtistPageResponse>(body) }.getOrNull() ?: return null
    val modules = page.rows.orEmpty().flatMap { it.modules.orEmpty() }
    // No modules at all is not a page. This is the case that catches a v1 error
    // envelope: `{"status":400,...}` decodes CLEANLY into rows=[] because every
    // field is defaulted, so `runCatching` above cannot reject it — this can.
    if (modules.isEmpty()) return null

    val header = modules.firstOrNull { it.type == "ARTIST_HEADER" }

    val topTracks = modules.firstOrNull { it.type == "TRACK_LIST" }
        ?.pagedList?.items?.decodeEach<TidalV1PageTrack>(json)
        ?.map { it.toTrack() }
        .orEmpty()

    val albumSections = modules.filter { it.type == "ALBUM_LIST" }.mapNotNull { module ->
        val albums = module.pagedList?.items?.decodeEach<TidalV1Album>(json)
            ?.map { it.toAlbumSummary() }
            .orEmpty()
        if (albums.isEmpty()) null else TidalArtistAlbumSection(module.title.orEmpty(), albums)
    }

    val artistSections = modules.filter { it.type == "ARTIST_LIST" }.mapNotNull { module ->
        val artists = module.pagedList?.items?.decodeEach<TidalV1PageArtist>(json)
            ?.map { ArtistLink(id = it.id.toString(), name = it.name, artworkUrl = tidalV1ArtistPictureUrl(it.picture)) }
            .orEmpty()
        if (artists.isEmpty()) null else TidalArtistLinkSection(module.title.orEmpty(), artists)
    }

    return TidalArtistPage(
        id = header?.artist?.id?.toString() ?: artistId,
        name = header?.artist?.name.orEmpty(),
        // ARTIST_HEADER.bio.text is the biography official v2 has not got at our
        // tier. formatTidalBio is REUSED (not re-declared): this is the same
        // service's markup as `artists/{id}/bio`, so the same [wimpLink]/<br>/HTML
        // must be assumed, and the formatter is idempotent on clean prose. Blank
        // after formatting reads as "no bio" and hides the section.
        biography = header?.bio?.text?.let { formatTidalBio(it) }?.takeIf { it.isNotBlank() },
        // 480, not 640 — see tidalV1ArtistPictureUrl's KDoc.
        artworkUrl = tidalV1ArtistPictureUrl(header?.artist?.picture),
        topTracks = topTracks,
        albumSections = albumSections,
        artistSections = artistSections,
    )
}
```

- [ ] **Step 7: GREEN — run the tests**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalArtistPageTest*' --tests '*TidalInternalArtistTest*'
```

**Expected: PASS.** All of `TidalArtistPageTest`, and `TidalInternalArtistTest` still green — Step 3 was a visibility widen with no behaviour change, so `artistAlbumsFrom`'s cases must be untouched. If `TidalInternalArtistTest` moved, you changed `toAlbumSummary`'s body; revert that.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalArtistPage.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalArtist.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistPageTest.kt
git commit -m "feat(android): pure projection for internal-v1 pages/artist

One endpoint returns the entire Tidal artist page: the biography official v2
has none of at our tier, Fans Also Like WITH photos, and the discography
already categorised by Tidal (Albums / EP & Singles / Live albums /
Compilations / Appears On) instead of the 43-album flat list that showed 20
Fixxxer rough mixes on device.

Modules are selected by type ONLY and rendered under Tidal's own titles, so a
rename, a new category or a localisation cannot break the parse — artist
3634161's 'Influencers' row ships for free. The parse is total at three
levels: envelope, module and item.

Artist pictures build at 480x480, NOT the album 640: tidalapi/artist.py:283
allows 160/320/480/750 only and the desktop uses 480 (providers/tidal.py:883).
Track artists read from `artists` plural — pages/artist has no singular
`artist` key (tidalapi/media.py:239-245).

Metadata only; streaming and auth untouched.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: The one internal-v1 read + the on-device probe (GATE)

Four lines on `TidalProvider` and a debug-screen probe. The probe is the gate: it proves the call works from the app's own session, and it **answers the one open payload question** — whether `pagedList.items` is fully materialised or a first page.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt` (add `artistPage`; **nothing else**)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (add `tidalArtistPageProbeStatus` + `runTidalArtistPageProbe`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt` (add the probe section)
- Modify: `android/app/src/main/res/values/strings.xml` (two probe strings)

**Interfaces:**
- Consumes: `artistPageUrl`, `artistPageFrom`, `TidalArtistPage` (Task 1); `TidalProvider`'s private `API_BASE`/`json`/`countryCode`/`validToken()`/`get()`.
- Produces: `TidalProvider.artistPage(artistId: String): TidalArtistPage?`; `PlayerViewModel.tidalArtistPageProbeStatus: StateFlow<String?>` + `runTidalArtistPageProbe(artistId: String)`.
- **Does NOT touch** `TidalMetadataRepository` — its constructor is frozen and holds no `TidalProvider`. `artistPage` belongs on the provider; composition happens in the ViewModel (Task 3).

- [ ] **Step 1: Add `artistPage` to `TidalProvider.kt`**

Add **directly after** `artistBio` (which currently ends at `TidalProvider.kt:269`), before the `// Internals` divider. It is additive: no existing method is edited.

```kotlin
    /**
     * The artist's ENTIRE Tidal page in one read-only catalog GET — biography,
     * top tracks, the discography **already categorised by Tidal**, and Fans
     * Also Like **with photos**. Metadata only: it resolves no stream and
     * touches no session state.
     *
     * This is the artist screen's single remote source. It replaces a four-call
     * fan-out ([artistBio] + [artistAlbums] + [artistTopTracks] + the official-v2
     * `artistDetail`) that on-device produced no biography, placeholder avatars,
     * and 20 *"Fixxxer"* rough mixes where a discography should be — because
     * `artists/{id}/albums` returns 43 albums FLATTENED and discards the
     * curation this endpoint keeps.
     *
     * **The request carries no `limit`, and must not** — this endpoint answers
     * **400** when one is present (probed 2026-07-16). [get] adds no params, and
     * [artistPageUrl] sends exactly the four the probe proved.
     *
     * [artistId] is a Tidal catalog artist id — the same id official v2 uses,
     * proven on-device by the id-equivalence gate.
     *
     * Returns **null** when not logged in, on any HTTP failure, or when the body
     * is not a page ([artistPageFrom]'s contract). All of those mean one thing to
     * the caller: no remote enrichment, and the artist screen renders exactly
     * what it renders today off its local base. A missing artist page must
     * degrade the screen, never break it — the same discipline as [artistAlbums]
     * and the desktop's swallow at `providers/tidal.py:899-900`.
     */
    suspend fun artistPage(artistId: String): TidalArtistPage? {
        val token = validToken() ?: return null
        val url = artistPageUrl(API_BASE, artistId, countryCode)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return null
        return artistPageFrom(body, json, artistId)
    }
```

**No import is needed** — `TidalArtistPage`, `artistPageUrl` and `artistPageFrom` are in the same package (`io.github.auxen.provider.tidal`).

- [ ] **Step 2: Add the probe to `PlayerViewModel.kt`**

Add immediately after `runTidalIdEquivalenceProbe` (which ends at `PlayerViewModel.kt:387`), before the `// ---- Slice 1: official-API detail enrichment` divider. `MutableStateFlow`/`StateFlow`/`asStateFlow`/`viewModelScope`/`launch`/`Graph` are already imported and in use in this file.

```kotlin
    private val _tidalArtistPageProbeStatus = MutableStateFlow<String?>(null)
    val tidalArtistPageProbeStatus: StateFlow<String?> = _tidalArtistPageProbeStatus.asStateFlow()

    /**
     * The artist-page-rebuild gate: prove `pages/artist` answers from the app's
     * OWN internal-v1 session and carries what the probe of 2026-07-16 saw.
     *
     * It also settles the one open payload question: whether `pagedList.items`
     * is fully materialised or a first page. The section counts below are what
     * we ACTUALLY parsed, so they answer it directly.
     *
     * Names, titles, ids and counts only — never a token, never a full URL.
     */
    fun runTidalArtistPageProbe(artistId: String) {
        viewModelScope.launch {
            val id = artistId.trim()
            _tidalArtistPageProbeStatus.value = "Fetching pages/artist for $id…"
            val page = Graph.tidal.artistPage(id)
            if (page == null) {
                _tidalArtistPageProbeStatus.value =
                    "FAIL: pages/artist returned nothing for artist id $id. Either you are not logged in " +
                        "on the internal-v1 path (Account → Log in to Tidal), or the request shape is " +
                        "wrong. Check FIRST that no `limit` param is being sent — this endpoint answers " +
                        "400 when one is present, and that is the known trap."
                return@launch
            }
            _tidalArtistPageProbeStatus.value = buildString {
                appendLine("Artist: ${page.name} (id ${page.id})")
                appendLine("Picture: ${if (page.artworkUrl != null) "present (480x480)" else "— MISSING"}")
                appendLine()
                appendLine("Biography: ${page.biography?.length?.let { "$it chars" } ?: "— MISSING"}")
                page.biography?.take(160)?.let { appendLine("  \"$it…\"") }
                appendLine(
                    if (page.biography != null) {
                        "PASS — the bio official v2 has not got at our tier. EYEBALL IT: it must read as " +
                            "prose, with NO raw [wimpLink] or <br> tags left in."
                    } else {
                        "FAIL — no biography. This is the headline fix; if it is missing for a major " +
                            "artist, do not proceed to Task 3."
                    },
                )
                appendLine()
                appendLine("Top tracks: ${page.topTracks.size}")
                page.topTracks.take(5).forEach { appendLine("  • ${it.title} — ${it.artist} [${it.qualityLabel}]") }
                appendLine(
                    if (page.topTracks.isEmpty()) {
                        "FAIL — no top tracks."
                    } else if (page.topTracks.any { it.artist.isBlank() }) {
                        "FAIL — a track has a BLANK artist name. The projection is reading the singular " +
                            "`artist` key, which this payload does not have; it must read `artists[0]`."
                    } else {
                        "PASS — EYEBALL THE ORDER: it must read like the artist's best-known songs."
                    },
                )
                appendLine()
                appendLine("Album sections: ${page.albumSections.size}")
                page.albumSections.forEach { section ->
                    appendLine("  ${section.title} (${section.albums.size})")
                    section.albums.take(3).forEach { appendLine("    • ${it.title} (${it.releaseYear ?: "—"})") }
                }
                appendLine(
                    if (page.albumSections.isEmpty()) {
                        "FAIL — no album sections."
                    } else {
                        "PASS — EYEBALL THE CATEGORIES: real studio albums under the FIRST section, and " +
                            "the Fixxxer rough mixes under 'EP & Singles' rather than in the discography. " +
                            "The first section is where local albums merge (Task 4), so confirm it IS the " +
                            "primary discography."
                    },
                )
                appendLine()
                appendLine("Artist sections: ${page.artistSections.size}")
                page.artistSections.forEach { section ->
                    val withArt = section.artists.count { it.artworkUrl != null }
                    appendLine("  ${section.title} (${section.artists.size}, $withArt with photos)")
                    section.artists.take(3).forEach { appendLine("    • ${it.name}") }
                }
                appendLine(
                    if (page.artistSections.flatMap { it.artists }.none { it.artworkUrl != null }) {
                        "FAIL — no photos on any similar artist. That is the user's actual ask."
                    } else {
                        "PASS — the photos are here. CONFIRM ON SCREEN that they LOAD: a 480x480 URL is " +
                            "only correct if the CDN renders it (tidalapi allows 160/320/480/750 for an " +
                            "artist; 640 — the ALBUM size — would 404)."
                    },
                )
                appendLine()
                appendLine(
                    "Section counts above are what we PARSED — they settle whether pagedList.items is " +
                        "fully materialised or a first page. Record them in the report.",
                )
            }
        }
    }
```

- [ ] **Step 3: Add the probe strings to `strings.xml`**

Add beside the existing `tidal_official_id_probe_*` keys:

```xml
    <string name="tidal_artist_page_probe_label">Tidal artist id for pages/artist probe</string>
    <string name="tidal_artist_page_probe_button">Probe pages/artist (internal-v1)</string>
```

- [ ] **Step 4: Add the probe section to `TidalOfficialDebugScreen.kt`**

Append below the existing id-equivalence probe block, following that section's established pattern exactly (the screen already receives `viewModel` directly, and `HorizontalDivider`/`OutlinedTextField`/`Button`/`Text`/`remember`/`mutableStateOf`/`collectAsState`/`stringResource`/`R`/`Modifier`/`MaterialTheme`/`dp` are already imported and used by it — **verify each import is present before relying on it; Kotlin resolves per file**):

```kotlin
        HorizontalDivider()

        var artistPageProbeId by remember { mutableStateOf("") }
        val artistPageProbeStatus by viewModel.tidalArtistPageProbeStatus.collectAsState()
        OutlinedTextField(
            value = artistPageProbeId,
            onValueChange = { artistPageProbeId = it },
            label = { Text(stringResource(R.string.tidal_artist_page_probe_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.runTidalArtistPageProbe(artistPageProbeId) },
            enabled = artistPageProbeId.isNotBlank(),
        ) {
            Text(stringResource(R.string.tidal_artist_page_probe_button))
        }
        artistPageProbeStatus?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
```

- [ ] **Step 5: Build + the full Tidal suite**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*Tidal*' assembleDebug
```

**Expected: PASS; APK builds.** Nothing here is unit-tested (live HTTP + Compose); this step is the compile-and-no-regression gate.

- [ ] **Step 6: THE ON-DEVICE GATE**

**Build the APK LOCALLY.** CI has no Tidal credentials — a CI APK cannot log in and this probe cannot run on it.

1. Install; log in on the internal-v1 path (**Account → Log in to Tidal**). Official login is **not** needed for this probe.
2. Settings → Advanced → the Tidal official debug screen.
3. Enter **`8405`** (Metallica — the probed artist) → **"Probe pages/artist (internal-v1)"**.
4. Repeat with **`3634161`** (the second probed artist — it is the one with the **'Influencers'** section, so it is the shape-stability check).

**Gate criteria — ALL must hold:**
- Biography: **PASS**, and the text reads as prose with **no raw `[wimpLink]` or `<br>`**.
- Top tracks: **PASS**, no blank artist name, order reads like the artist's best-known songs.
- Album sections: **PASS**, the **first** section is the real studio discography and the Fixxxer rough mixes are under 'EP & Singles', **not** in it.
- Artist sections: **PASS** with photos; `3634161` shows **both** 'Fans Also Like' **and** 'Influencers'.
- Picture: **present**.

**If it FAILS, STOP.** If the failure is `null`, the first suspect is a `limit` param (this endpoint 400s on one). Do not start Task 3 until this passes for both artists.

**Record the printed section counts in the report** — they settle whether `pagedList.items` is fully materialised or a first page, which is the plan's one open payload question.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt \
        android/app/src/main/res/values/strings.xml
git commit -m "feat(android): TidalProvider.artistPage + on-device pages/artist probe

One additive read-only internal-v1 catalog GET, plus the debug-screen probe
that gates the rebuild: it proves pages/artist answers from the app's own
session and prints the parsed section counts, biography length and
photo-bearing similar artists.

The request deliberately carries no limit — the endpoint answers 400 when one
is present, which is why a naive tidalapi call fails.

Auth and streaming untouched; no existing method edited.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: The ViewModel — four calls collapse to one

**Do not start until Task 2 Step 6 has passed on-device for both artists.**

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (replace the artist-detail flows + `loadArtistDetail`'s body)

**Interfaces:**
- Consumes: `TidalProvider.artistPage` (Task 2); `TidalMetadataRepository.trackRelations` + `TidalProvider.searchArtistIdExact` (id chain, unchanged).
- Produces: `PlayerViewModel.artistPage: MutableStateFlow<TidalArtistPage?>` (replaces `artistDetail`, `artistAlbums`, `artistTopTracks`).
- Unchanged: `artistDetailLoading`, `artistDetailKey`, `artistDetailJob`, and `loadArtistDetail`'s **signature** — `ArtistDetailScreen.kt:115` calls it and Task 4 keeps that call identical.

- [ ] **Step 1: Replace the three flows**

Delete `PlayerViewModel.kt:390-412` — the `artistDetail`, `artistAlbums` and `artistTopTracks` declarations **with their KDoc** — and put this in their place. Keep `artistDetailLoading`/`artistDetailKey`/`artistDetailJob` (lines 413-415) exactly as they are:

```kotlin
    // ---- The artist page: ONE internal-v1 read (pages/artist) ----

    /**
     * The whole Tidal artist page from ONE call — biography, top tracks, the
     * discography already categorised by Tidal, and Fans Also Like with photos.
     *
     * **This replaced three flows** (`artistDetail` from official v2,
     * `artistAlbums` and `artistTopTracks` from internal-v1) fed by four
     * sequential round-trips. On-device that produced no biography (official v2
     * has none at our tier), placeholder avatars (a similar artist's profileArt
     * is not include-able from an official artist document) and 20 *"Fixxxer"*
     * rough mixes as a discography (`artists/{id}/albums` returns 43 albums
     * flattened and discards Tidal's own curation).
     *
     * Null means "no artist page": not logged in to internal-v1, no id resolved,
     * or the call failed. The screen then renders exactly what it renders today
     * off its offline-safe local base.
     *
     * This lives here, not on [io.github.auxen.provider.tidal.TidalMetadataRepository],
     * because that class's constructor is FROZEN and holds no internal-v1
     * provider — the same reason `artistAlbums`/`artistTopTracks` were composed
     * here before it.
     */
    val artistPage = MutableStateFlow<io.github.auxen.provider.tidal.TidalArtistPage?>(null)
```

- [ ] **Step 2: Rebuild `loadArtistDetail`'s body**

Replace the body of `loadArtistDetail` (`PlayerViewModel.kt:456-521`). **Keep the signature and the whole `key`/`cancel` mechanism** — the KDoc at `:422-455` explains why every write is `key`-guarded, and that reasoning is unchanged and load-bearing. Only the fan-out becomes one call.

Keep the existing KDoc block (`:422-455`) verbatim, but replace its two body-specific paragraphs — *"The official artist document and the two internal-v1 lists are fetched off ONE resolved id and each is allowed to fail on its own: a missing discography must not cost us the biography, and vice versa."* — with:

```
     * Once the id resolves, ONE call ([io.github.auxen.provider.tidal.TidalProvider.artistPage])
     * fills the whole page. There is nothing left to fan out and nothing left to
     * fail independently: the four reads this replaced could each half-succeed
     * and leave the screen in a partial state, and now the page either lands or
     * it does not.
```

Then the body:

```kotlin
    fun loadArtistDetail(artistName: String, tidalArtistId: String?, seedTidalTrackId: String?) {
        val key = "$artistName|$tidalArtistId|$seedTidalTrackId"
        if (key == artistDetailKey) return
        artistDetailKey = key
        // Cancel the load this one supersedes. An artist page legitimately calls
        // this TWICE for the same artist: once while the local library is still
        // loading (seedTidalTrackId = null, so the id resolves via the exact-name
        // search) and again when the seed lands (resolving precisely via
        // trackRelations). Those are two different keys, so both run — and
        // LaunchedEffect restarting cancels only ITS OWN coroutine, never the
        // viewModelScope job below. Unguarded, the two race and whichever network
        // round-trip lands LAST wins: for two Tidal artists sharing an exact
        // name, that is the WRONG artist's page. Same cancel-and-relaunch idiom
        // as searchDebounceJob/loginJob.
        artistDetailJob?.cancel()
        artistPage.value = null
        artistDetailJob = viewModelScope.launch {
            artistDetailLoading.value = true
            try {
                val artistId = tidalArtistId
                    ?: seedTidalTrackId?.let { Graph.tidalMetadata.trackRelations(it).getOrNull()?.artistId }
                    ?: Graph.tidal.searchArtistIdExact(artistName)
                    ?: return@launch
                // ONE read. `artistPage` never throws (it returns null on any
                // failure), so there is nothing to catch; the try/finally exists
                // solely to clear the spinner on the early return above.
                val page = Graph.tidal.artistPage(artistId)
                if (artistDetailKey == key && page != null) artistPage.value = page
            } finally {
                if (artistDetailKey == key) artistDetailLoading.value = false
            }
        }
    }
```

**The `key` guard is the actual correctness mechanism and must not be "simplified" away.** `cancel()` alone cannot stop a superseded load dead: the provider wraps its network reads in `runCatching { get(...) }`, and `runCatching` catches `Throwable` — **including the `CancellationException` that cancellation delivers**. A cancelled loader therefore resumes with `null` in hand and runs on until its next real suspension point; a `MutableStateFlow` write is not one. Without the guard it would clobber the winner's page and clear the winner's spinner early.

- [ ] **Step 3: Build — the compiler finds every consumer**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android assembleDebug
```

**Expected: FAIL, and the failures are the worklist for Task 4.** `ArtistDetailScreen.kt:101-104` reads `viewModel.artistDetail`, `viewModel.artistAlbums` and `viewModel.artistTopTracks`, which no longer exist. That is the intended RED for Task 4 — the three unresolved references are exactly the call sites that must move to `artistPage`.

**Do not commit this task on its own** — the tree does not build between Task 3 and Task 4. Land them as **one commit** (Task 4 Step 5). Both tasks are in `PlayerViewModel.kt`/`ArtistDetailScreen.kt` only, so the pathspec stays small and the concurrent-Kotlin-agent risk stays low.

---

### Task 4: The screen — sections under Tidal's own titles

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt`

**Interfaces:**
- Consumes: `PlayerViewModel.artistPage` + `artistDetailLoading` (Task 3); `dedupRemoteAlbums`, `mergeArtistTracks` (unchanged, reused); `AlbumCard`, `ArtistRow`, `AuxenTrackRow`, `ArtworkImage`, `SectionHeader`, `EmptyState`, `LoadingState` (all unchanged).
- Produces: no new public API.
- **Imports to REMOVE:** `io.github.auxen.model.Source` is still needed (the Tidal album cards pass `Source.TIDAL`); `dedupRemoteAlbums` and `mergeArtistTracks` are still needed. **Nothing is removed.** No import is added — `TidalArtistPage` is only read through `viewModel.artistPage`, whose type is inferred.

- [ ] **Step 1: Replace the state reads and the derived values**

Replace `ArtistDetailScreen.kt:101-132` (from `val detail by viewModel.artistDetail.collectAsState()` through `val similarArtists = detail?.similarArtists.orEmpty()`). The `LaunchedEffect` and the seed comment (`:105-116`) stay **exactly** as they are — the id chain is unchanged:

```kotlin
    val page by viewModel.artistPage.collectAsState()
    val artistDetailLoading by viewModel.artistDetailLoading.collectAsState()
    // The seed MUST be a track whose PRIMARY artist is this screen's artist.
    // `tracks` is filtered on `it.artist == artist || it.albumArtist == artist`,
    // and trackRelations takes related(track,"artists").first() — the primary
    // artist. So seeding off an albumArtist-only match (a compilation, a
    // "Various Artists" album) would resolve a DIFFERENT artist's id and hang
    // the wrong page on this screen. `it.artist` is the main-artist name for
    // Tidal tracks (TidalProvider.toTrack maps TrackDto.artist.name), so this
    // comparison is exactly the right guard.
    val seedTidalTrackId = tracks.firstOrNull { it.isTidal && it.artist == artist }?.sourceId
    LaunchedEffect(artist, tidalArtistId, seedTidalTrackId) {
        viewModel.loadArtistDetail(artist, tidalArtistId, seedTidalTrackId)
    }

    // Tidal top tracks (popularity order) first, then local tracks not among
    // them — the desktop's merge (window.py:1409-1422). With no page this
    // returns `tracks` unchanged, so the offline base is never at risk.
    //
    // CAPPED AT 50. pages/artist's 'Top Tracks' module reported 300 items and
    // this endpoint REFUSES a `limit` param (400), so the cap is ours to apply.
    // 50 is what the artist page shipped with (artistTopTracks(limit = 50)) and
    // what the desktop uses (get_top_tracks(limit=50)) — this is deliberately
    // not a behaviour change.
    val topTracks = remember(page) { page?.topTracks.orEmpty().take(TOP_TRACKS_DISPLAY_LIMIT) }
    val displayTracks = remember(topTracks, tracks) { mergeArtistTracks(topTracks, tracks) }

    // Every ALBUM_LIST Tidal gave us, in page order, under ITS OWN title —
    // 'Albums', 'EP & Singles', 'Live albums', 'Compilations', 'Appears On'. We
    // never match on those strings (see TidalArtistPage.kt's KDoc): a rename, a
    // new category or a localisation all just work.
    //
    // The user's LOCAL albums merge into the FIRST section, which assumes the
    // first ALBUM_LIST is the primary discography — true for both probed
    // artists. It is a POSITION assumption, deliberately chosen over a TITLE
    // match because position survives renaming and localisation. If it is ever
    // wrong the failure is cosmetic (local albums under a row headed 'EP &
    // Singles'), never data loss.
    //
    // Each section is deduped against the RAW local titles — dedupRemoteAlbums
    // normalizes both sides itself, and pre-lowercasing here would be a lossy
    // half of what it already does.
    val localTitles = remember(albums) { albums.map { it.album }.toSet() }
    val albumSections = remember(page, localTitles) {
        page?.albumSections.orEmpty().map { section ->
            section.title to dedupRemoteAlbums(localTitles = localTitles, remote = section.albums)
        }
    }
    val artistSections = page?.artistSections.orEmpty()
    val totalAlbums = albums.size + albumSections.sumOf { (_, remote) -> remote.size }
```

- [ ] **Step 2: Update the header counts and the artwork fallback**

At `ArtistDetailScreen.kt:143`, `detail?.artworkUrl` becomes `page?.artworkUrl`:

```kotlin
                ArtworkImage(
                    // The page's own profile art (480x480 — the artist size; the
                    // 640 album size does not render for a picture) wins when
                    // present; the local album art is the offline fallback.
                    model = page?.artworkUrl ?: tracks.firstNotNullOfOrNull { it.albumArtUrl },
                    contentDescription = null,
                    glyph = Icons.Filled.Person,
                    glyphSize = 40.dp,
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                )
```

And at `:155`, the counts describe every album now on screen:

```kotlin
                    Text(
                        // Counts describe what is actually on screen, across
                        // every album section, not the local subset.
                        "$totalAlbums albums • ${displayTracks.size} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
```

- [ ] **Step 3: Rewrite the Biography, Albums and Similar-artists blocks**

Replace `ArtistDetailScreen.kt:188-281` (the biography block through the end of the similar-artists block). Everything before (header, buttons) and after (the track list) is untouched — **the shipped section order is preserved on purpose**; see the plan's *Open question 1*:

```kotlin
        // A null biography renders nothing at all — no header, no placeholder,
        // no error. This text comes from ARTIST_HEADER.bio.text, the biography
        // official v2 has none of at our tier, and it is display-ready:
        // artistPageFrom already ran formatTidalBio over the endpoint's
        // `<br>`/`[wimpLink]` markup, so no raw tag can reach this Text.
        page?.biography?.let { biography ->
            item { SectionHeader("Biography") }
            item {
                Text(
                    biography,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (bioExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bioExpanded = !bioExpanded }
                        .padding(horizontal = 16.dp),
                )
            }
        }
        if (albumSections.isEmpty()) {
            // No Tidal page (not logged in, no id resolved, the call failed) —
            // the offline-safe local base, exactly as it renders today.
            if (albums.isNotEmpty()) {
                item { SectionHeader("Albums") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(albums, key = { "${it.album}|${it.albumArtist}" }) { album ->
                            AlbumCard(
                                title = album.album,
                                artist = null,
                                artUrl = album.artUrl,
                                source = album.tracks.firstOrNull()?.source,
                                onClick = { onOpenAlbum(album.album, album.albumArtist, null) },
                                onPlay = { viewModel.playAll(album.tracks) },
                                // Carousel item — a fill-width card has no width
                                // constraint inside a LazyRow, so pin it here.
                                modifier = Modifier.width(150.dp),
                            )
                        }
                    }
                }
            }
        } else {
            albumSections.forEachIndexed { index, (sectionTitle, remoteAlbums) ->
                // The user's local albums join the FIRST section only — see the
                // position-assumption note above. Sections after it are Tidal-only.
                val localHere = if (index == 0) albums else emptyList()
                if (localHere.isEmpty() && remoteAlbums.isEmpty()) return@forEachIndexed
                // Tidal's OWN title, verbatim. Never matched on, only rendered.
                item(key = "section-header-$index") { SectionHeader(sectionTitle) }
                item(key = "section-row-$index") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(localHere, key = { "${it.album}|${it.albumArtist}" }) { album ->
                            AlbumCard(
                                title = album.album,
                                artist = null,
                                artUrl = album.artUrl,
                                source = album.tracks.firstOrNull()?.source,
                                onClick = { onOpenAlbum(album.album, album.albumArtist, null) },
                                onPlay = { viewModel.playAll(album.tracks) },
                                modifier = Modifier.width(150.dp),
                            )
                        }
                        items(remoteAlbums, key = { "tidal:${it.id}" }) { summary ->
                            AlbumCard(
                                title = summary.title,
                                artist = null,
                                artUrl = summary.artworkUrl,
                                source = Source.TIDAL,
                                // KNOWN LIMITATION (plan, Open question 4): the
                                // artist arg is this screen's artist, which is
                                // WRONG for an 'Appears On' album (that is a
                                // different artist's record). It self-corrects —
                                // tidalAlbumId is non-null, so AlbumDetailScreen
                                // fetches the album's own document and shows the
                                // real artist; this arg only seeds the local
                                // grouping fallback. Same behaviour the shipped
                                // tidal-only row already has.
                                onClick = { onOpenAlbum(summary.title, page?.name ?: artist, summary.id) },
                                // No local tracks to enqueue — a tap opens the
                                // album, whose remote tracklist is playable.
                                onPlay = null,
                                modifier = Modifier.width(150.dp),
                            )
                        }
                    }
                }
            }
        }
        // Every ARTIST_LIST, under its own title — 'Fans Also Like', and
        // 'Influencers' for the artists that have one. Selecting on type rather
        // than title is what makes the second row ship at all.
        artistSections.forEachIndexed { index, section ->
            item(key = "artists-header-$index") { SectionHeader(section.title) }
            item(key = "artists-row-$index") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // These now carry REAL photos (ArtistLink.artworkUrl from the
                    // item's `picture` at 480x480 — the artist size; 640 is the
                    // album size and does not render for a picture). Slice 1's
                    // similar artists were null by construction and fell back to
                    // ArtistRow's Person glyph; that glyph is still the fallback
                    // for an artist whose `picture` is null.
                    //
                    // Deliberately UNKEYED. This API demonstrably repeats a
                    // linkage entry (the probe of artist 8405 returned
                    // `Fixxxer (Remastered)` twice through the same mechanism;
                    // see dedupRemoteAlbums' KDoc), and keying on `link.id`
                    // turns any duplicate into a hard "key was already used"
                    // CRASH. The cost of the default index key here is nil (the
                    // list is short, static and never reordered); the cost of
                    // being wrong is the whole screen.
                    items(section.artists) { link ->
                        ArtistRow(
                            name = link.name,
                            artUrl = link.artworkUrl,
                            onClick = { onOpenArtist(link.name, link.id) },
                            // Carousel item — ArtistRow fills its parent's width,
                            // which is unbounded inside a LazyRow.
                            modifier = Modifier.width(200.dp),
                        )
                    }
                }
            }
        }
```

Add the constant at the bottom of the file, after `ArtistDetailScreen`:

```kotlin
/**
 * How many of `pages/artist`'s 'Top Tracks' the screen renders.
 *
 * The module reported 300 items and the endpoint REFUSES a `limit` param (it
 * answers 400), so the cap is the client's to apply. 50 is what this page
 * shipped with (`artistTopTracks(limit = 50)`) and what the desktop uses
 * (`get_top_tracks(limit=50)`) — deliberately not a behaviour change.
 */
private const val TOP_TRACKS_DISPLAY_LIMIT = 50
```

- [ ] **Step 4: Update the track-list block's stale comments**

At `ArtistDetailScreen.kt:284-311` the logic is correct and unchanged, but two comments now name deleted things. In the `displayTracks.isEmpty()` branch, replace the parenthetical *"(That round-trip is no longer padded by the official biography fallback — deleted 2026-07-16 — but it is still id-resolution + detail + bio + two lists.)"* with:

```kotlin
                // (That round-trip is now id-resolution + ONE pages/artist call,
                // where it used to be id-resolution + four sequential reads.)
```

The `SectionHeader(if (topTracks.isNotEmpty()) "Top tracks" else "Tracks")` at `:311` stays: `pages/artist`'s 'Top Tracks' module is popularity-ordered, so the label is still honest, and with no page the list is purely local and "Tracks" is honest there.

- [ ] **Step 5: Build, full suite, goldens — then commit Tasks 3+4 together**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest assembleDebug
```

**Expected: PASS — the whole suite, including every Roborazzi golden.**

**The goldens are a real assertion here, not ceremony.** `album-card-{light,dark}.png` and `track-row-{local-explicit,tidal-favorite}-{light,dark}.png` cover the components this screen reuses. **A shifted golden means a leaked default — fix the default, never re-record.** This task passes no new arguments into `AlbumCard`/`ArtistRow`/`AuxenTrackRow`, so nothing should move; if something did, you changed a component, and that is out of scope.

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt
git commit -m "feat(android): rebuild the artist page on pages/artist

Four sequential reads (official-v2 artistDetail + internal-v1 artistBio +
artistAlbums + artistTopTracks) collapse to one internal-v1 pages/artist call.

What the user gets: the biography (official v2 serves none at our tier), real
photos on Fans Also Like (not include-able from an official artist document),
and Tidal's own categorised discography — Albums / EP & Singles / Live albums /
Compilations / Appears On — instead of a 43-album flat list that surfaced 20
Fixxxer rough mixes as the discography.

Each ALBUM_LIST and ARTIST_LIST renders under Tidal's own title, selected by
module type and never by title string, so a rename or a new category cannot
break the screen; artists with an 'Influencers' row get it for free. Local
albums merge into the first section and the shipped section order is preserved.

Playback is unchanged: page tracks are ordinary Source.TIDAL tracks on the
existing internal-v1 stream path. Videos and playlists are not projected —
Auxen has no surface for either.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: The on-device checkpoint (GATE for Task 6)

No code. This is where the rebuild is proven against the three bugs it exists to fix, and it gates the deletion in Task 6.

- [ ] **Step 1: Confirm nothing forbidden moved**

```bash
git status --short
git diff --stat HEAD~2 -- android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalAuth.kt
```

**Expected: `TidalAuth.kt` has ZERO changes.** `TidalProvider.kt`'s diff must show **one added method** (`artistPage`) and no edit to `restoreSession`/`logout`/`validToken`/`get`/`search`/`getStreamInfo`/`getStreamInfoById`/`toTrack` or any existing DTO.

- [ ] **Step 2: Build the APK LOCALLY and run the checkpoint**

CI has no Tidal credentials. Build locally, install, log in on the internal-v1 path.

Open **Metallica**'s artist page (the artist behind every reported bug) and confirm, against the shipped behaviour:

| # | The bug that shipped | What must now be true |
|---|---|---|
| 1 | **No biography at all** | The Biography section is present, reads as prose, and expands on tap. **No raw `[wimpLink]` or `<br>`.** |
| 2 | **Placeholder avatars on similar artists** | 'Fans Also Like' shows **real photos that LOAD**. (A 480x480 URL is only right if the CDN renders it — this is the visual proof of Finding 2.) |
| 3 | **20 "Fixxxer" rough mixes as the discography** | The **first** albums row is the real studio discography (*72 Seasons*, …) **with the user's local Metallica albums merged in**; the Fixxxer rough mixes are under **'EP & Singles'**; `S&M2 (Live)` is under **'Live albums'**; `Garage, Inc.` under **'Compilations'**; **'Appears On'** is its own row. |

Then confirm the rest:
- Header photo present; counts read sensibly.
- Top tracks: ~50 rows, **artist names NOT blank** (Finding 1), quality badges present and correct (Finding: the duplicated quality mapping), order reads like the best-known songs.
- **Tap a top track → it PLAYS.** This is the playback proof: an ordinary `Source.TIDAL` track on the existing internal-v1 path.
- **Tap an album card in every section, including 'Appears On' → the album opens and its tracklist plays.**
- **Tap a Fans Also Like artist → their artist page opens, with ITS photo and bio.**
- **No Videos row. No Playlists row.** (Both deliberately absent — see the deferral table.)
- Open an artist with **no** Tidal presence → the page renders its local content, no spinner stuck, no error.
- Open an artist while offline → local content only, no crash.

Then open **artist 3634161** and confirm **both** 'Fans Also Like' **and 'Influencers'** render — the payoff of type-only selection.

**Gate criterion: bugs 1, 2 and 3 are all fixed, and nothing in the second list regressed.** Paste the result back to close the loop.

**If any of the three is NOT fixed, STOP. Do not proceed to Task 6** — Task 6 deletes the fallback path, and while it is still in the tree this whole rebuild is one revert away.

---

### Task 6: Delete the path that is now unreachable

**Do not start until Task 5's gate has passed.** This is the one destructive task in the plan, and it is gated deliberately: everything it removes is what the artist page would fall back to if `pages/artist` misbehaved on-device in a way Task 5 caught.

**Scope is exactly what the grep in *What this REPLACES* proved has zero callers after Task 3, and nothing else.**

**Files:**
- Modify: `.../provider/tidal/TidalMetadataRepository.kt` (delete `artistDetail` + `ARTIST_DETAIL_INCLUDE`)
- Modify: `.../provider/tidal/TidalMetadataDetail.kt` (delete `TidalArtistDetail` + `artistDetailFrom`)
- Modify: `.../provider/tidal/TidalInternalArtist.kt` (delete `artistBioUrl`, `TidalV1Bio`, `artistBioFrom`; **KEEP `formatTidalBio` and its regexes**)
- Modify: `.../provider/tidal/TidalProvider.kt` (delete `artistBio`)
- Modify: `.../test/.../TidalArtistDetailTest.kt` (delete the `artistDetailFrom` + `ARTIST_DETAIL_INCLUDE` cases **only**)
- Modify: `.../test/.../TidalInternalArtistTest.kt` (delete the `artistBioUrl` + `artistBioFrom` cases **only**)

- [ ] **Step 1: Delete the official-v2 artist document path**

In `TidalMetadataRepository.kt`: delete `artistDetail(...)` (lines 90-116, **with its KDoc**) and the `ARTIST_DETAIL_INCLUDE` constant (lines 180-201, **with its comment block**).

**KEEP, and do not touch:** `artistOverview` and `ARTIST_INCLUDE` (line 177) — `runTidalMetadataProbe` (`PlayerViewModel.kt:280`) and `runTidalIdEquivalenceProbe` (`:316`) both call `artistOverview`. Its `"biography"` include stays precisely so that probe keeps **reporting** the emptiness that motivated this whole rebuild. `trackRelations`, `albumDetail`, `trackDetail`, `currentUserId` and their includes are all still live.

In `TidalMetadataDetail.kt`: delete `TidalArtistDetail` (lines 30-55) and `artistDetailFrom` (lines 189-237), both with their KDoc, plus the orphaned `biographyTextFrom`-was-deleted comment at lines 239-245 (its subject is now gone too).

**KEEP, and do not touch:** `ArtistLink` and `AlbumSummary` (the page projection reuses both), `AlbumItemMeta`, `TidalAlbumDetail`, `CreditsAttributes`, `LyricsAttributes`, `TrackCredit`, `TidalTrackDetail`, `artworkUrlFor`, `trackFrom`, `albumSummaryFrom`, `albumDetailFrom`, `trackDetailFrom`, `normalizeTitleForDedup`, `dedupRemoteAlbums`, `mergeArtistTracks`.

- [ ] **Step 2: Delete the internal-v1 bio read, keeping the formatter**

In `TidalProvider.kt`: delete `artistBio(...)` (lines 246-269, with its KDoc).

**KEEP:** `artistAlbums`, `artistTopTracks`, `searchArtistIdExact` — all three still feed `runTidalIdEquivalenceProbe` (`PlayerViewModel.kt:323`, `:324`, `:327`, `:329`), which is the documented gate for this series, and `searchArtistIdExact` is still step 3 of the live id chain.

In `TidalInternalArtist.kt`: delete `artistBioUrl` (lines 233-255), `TidalV1Bio` (lines 257-259) and `artistBioFrom` (lines 306-326), all with their KDoc.

**KEEP `formatTidalBio` and its five regex constants (lines 261-304)** — `artistPageFrom` reuses the formatter on `ARTIST_HEADER.bio.text`. Update its KDoc's opening line, which names the endpoint that just died:

```kotlin
/**
 * Tidal bio markup → display text, mirroring the desktop's `_format_bio`
 * (`auxen/views/artist_detail.py:1282-1325`). Tidal returns markup, not prose —
 * that the desktop bothers to parse it is the proof the payload is real, rich
 * content.
 *
 * Called by [artistPageFrom] on `ARTIST_HEADER.bio.text`. It formerly served
 * `artists/{id}/bio` via `artistBioFrom`; that read was deleted when the artist
 * page moved to `pages/artist`, which carries the same service's bio — and so
 * the same `[wimpLink]`/`<br>`/HTML markup — inline. The formatter outlived its
 * first caller because the markup did.
 *
```
*(the numbered rules and the divergence notes that follow stay verbatim)*

- [ ] **Step 3: Delete the orphaned tests — surgically**

Both test files **mix dead cases with live ones**. Delete only the dead.

`TidalArtistDetailTest.kt` — delete these seven, and **nothing else**:
- `artistDetailFrom maps name, biography and best-fit profile art` (:40)
- `artistDetailFrom maps similar artists` (:49)
- `artistDetailFrom dedups similarArtists by id, keeping the first and the order` (:58)
- `artistDetailFrom tolerates an artist with no relationships` (:85)
- `artistDetailFrom returns null when there is no primary artist` (:95)
- `artistDetailFrom leaves biography null when the resource is linked but not included` (:108)
- `ARTIST_DETAIL_INCLUDE does not ask official v2 for a biography it has not got` (:124)

Delete the now-unused `artistDoc` fixture with them. **KEEP every `dedupRemoteAlbums`, `normalizeTitleForDedup` and `mergeArtistTracks` case (:136 onward) and the `albumSummary`/`tidalTrack`/`localTrack` helpers** — all three functions are still live. The file keeps its name: it still tests artist-detail *merge* logic.

`TidalInternalArtistTest.kt` — delete only the `artistBioUrl` and `artistBioFrom` cases. **KEEP every `formatTidalBio` case** (the formatter is live and now has a new caller), and keep every `artistAlbumsUrl`/`artistAlbumsFrom`/`artistTopTracksUrl`/`artistSearchUrl`/`artistIdFromSearch`/`tidalV1CoverUrl` case — all still live.

- [ ] **Step 4: Verify — the compiler is the proof the deletion was safe**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest assembleDebug
```

**Expected: PASS, full suite + goldens.** A clean build **is** the proof that everything deleted had zero callers — that is why the deletion is safe to do mechanically and why it is worth doing at all. **If anything fails to resolve, you deleted something still in use: restore it and re-run the grep from the plan's *What this REPLACES* table rather than chasing the error.**

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalArtist.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistDetailTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalArtistTest.kt
git commit -m "refactor(android): drop the artist path pages/artist made unreachable

Deleted, each with zero callers after the rebuild and gated on the on-device
checkpoint: the official-v2 artistDetail + artistDetailFrom + TidalArtistDetail
+ ARTIST_DETAIL_INCLUDE, and the internal-v1 artistBio + artistBioUrl +
artistBioFrom + TidalV1Bio. Official v2 now contributes nothing to the artist
page beyond trackRelations in the id chain.

Kept deliberately: formatTidalBio (artistPageFrom reuses it — pages/artist
serves the same service's markup), artistAlbums/artistTopTracks/
searchArtistIdExact (the id-equivalence probe is this series' documented gate
and calls all three), artistOverview + ARTIST_INCLUDE with its 'biography'
(that probe exists to keep reporting the emptiness that motivated this).

A clean build is the proof the deletion was safe.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Scope

**Six tasks** — the brief's stated ceiling, and I am flagging that it sits right at it rather than pretending otherwise. Task 6 is the only removable one: it is pure cleanup, gated on Task 5, and dropping it would leave ~120 lines of unreachable code and their tests in the tree. Tasks 1–5 are the rebuild and are not divisible below this.

Three sections that would have pushed it over — Credits, Articles, Social — are **escalated, not built**, and Playlists/Videos/Artist Mix are **deferred with concrete reasons** (no surface to render them into). Those decisions are what keep this at six.
</content>
</invoke>
