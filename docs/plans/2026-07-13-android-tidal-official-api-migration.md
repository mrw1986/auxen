# Tidal — Migrate to the Official Open API (openapi.tidal.com/v2)

**Why:** User priority is minimizing liability. The app has a **registered official Tidal developer app "Auxen"** (scopes: collection.read/write, playlists.read/write, recommendations.read, search.read/write, user.read, entitlements.read, **playback**; redirect `auxen://auth-callback` + loopback). The official Open API is the *sanctioned* path (Tidal developer terms); the internal `api.tidal.com/v1` reverse-engineering we do today is unsanctioned/higher-liability. So migrate the Tidal layer onto the official API and retire internal-v1 — provided full-length streaming works for this app (the one empirical unknown). Spec cached: `.superpowers/sdd/tidal-openapi-v2.json` (v1.10.55, JSON:API).

**Hard constraints:**
- **Keep OUR Media3 pipeline for playback** (DSP: EQ/AutoEq/bass/balance/limiter/reverb). Do NOT use the Tidal SDK `player` module (black-box ExoPlayer fork, no DSP hook). Feed our pipeline the direct URL from `/trackFiles/{id}` (FLAC/FLAC_HIRES) or the manifest from `/trackManifests/{id}`.
- **Never break working playback on a guess** — build official auth ALONGSIDE the current login, validate streaming on-device, THEN migrate. Nothing internal is removed until the official path is proven.
- Secret hygiene: `auxen.tidalClientId`/`Secret` in gradle.properties → BuildConfig; never commit/print the secret. Prefer PKCE public-client; reassess whether the secret should ship in a distributed app.

---

### Task 1 (FOUNDATION + the go/no-go spike): Official-API auth + streaming validation

**Goal:** prove whether this app can stream full-length Hi-Res via the official API before any migration. Additive only — no existing code removed.

**Files:** new `providers/TidalOfficialAuth.kt` (or use `com.tidal.sdk:auth`), new `providers/TidalOfficialApi.kt` (thin JSON:API client), a temporary validation entry (e.g. a hidden Settings "Try official login" action or a debug path), `build.gradle.kts` (deps), manifest (redirect intent-filter).

1. **PKCE authorization-code auth** for the Auxen app: authorize URL at Tidal's OAuth endpoint with `response_type=code`, `code_challenge` (S256), `redirect_uri=auxen://auth-callback`, `scope=collection.read collection.write playlists.read playlists.write recommendations.read search.read user.read entitlements.read playback`. Open in a Custom Tab; catch the redirect via an intent-filter on `auxen://auth-callback`; exchange code+verifier for tokens at the token endpoint. Store tokens encrypted (or use the SDK auth module which does this + refresh). Evaluate `com.tidal.sdk:auth` vs rolling our own — the SDK handles storage/refresh but adds a dep; decide and note.
2. **Minimal JSON:API client** (`openapi.tidal.com/v2`): GET with the bearer token, parse the `data/attributes/relationships` envelope. Just enough for the spike: `GET /tracks/{id}` and `GET /tracks/{id}/relationships/... ` and the **manifest/file** fetch.
3. **Streaming validation** — the decisive test: for a known Hi-Res track id, fetch `/trackFiles/{id}` (and/or `/trackManifests/{id}`) with the user's token and inspect `trackPresentation` (FULL vs PREVIEW) + `previewReason`. If FULL and format FLAC/FLAC_HIRES → feed the `url` to a throwaway Media3 play to confirm it actually plays through our sink. Surface the result plainly (log + a visible status) so the user can report FULL vs PREVIEW.
4. **Report the go/no-go:** does full-length Hi-Res play for this app+subscription via the official API? This decides Task 2's scope (full migration vs metadata-only + internal streaming).

Testable: PKCE code-challenge/verifier generation, JSON:API envelope parsing, token exchange request shape (unit tests). The actual streaming outcome is an on-device user check (un-CI-testable, like the reverb work) — ship with a clear status readout.

### Task 2+ (branch on Task 1's result):
- **If full streaming works:** migrate search + streaming to the official API; rebuild the Tidal data layer as JSON:API DTOs; make the official login the primary Tidal auth; retire internal-v1 usage. Then favorites/collection (userCollections r/w), enrichment (similarAlbums, artistBiographies, artists radio, track credits/radio/similarTracks), discovery (dynamicPages/dynamicModules, userRecommendations/userDailyMixes) — all on the documented official API (this REPLACES the reverse-engineered sub-batches B/C/D and kills their risk).
- **If only previews:** keep internal-v1 for search+streaming (flag the residual liability), use the official API for favorites/enrichment/discovery; and note the user can request a higher access tier from Tidal to unlock full official streaming later.

Plan the concrete Task 2+ breakdown after Task 1's streaming verdict.

### Notes
- This supersedes the internal-API assumptions in `docs/plans/...parity-A...` sub-batches B/C/D (A = Settings/Queue is unaffected, no Tidal).
- JSON:API means DTO rewrites vs the current flat-v1 DTOs — scoped in Task 2 once the direction is confirmed.
