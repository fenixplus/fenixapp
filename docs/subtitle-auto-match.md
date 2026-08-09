# Subtitle Auto-Match ("Find Best Match") — Design & Behavior Reference

Complete reference for the subtitle auto-matching feature and its interaction with AI subtitle
translation. Written to be self-contained: hand this file to a fresh session/developer for full
context.

**Code map**

| Concern | File |
|---|---|
| Scan orchestration, selection flows, caches, settings gating | `app/src/main/kotlin/com/arflix/tv/ui/screens/player/PlayerViewModel.kt` (`findBestSubtitleMatch`, `runFindBestMatch`, `activateAiSubtitle`, `applyPreferredSubtitle`, `MATCH_*` constants) |
| Cue download/parse + timing/word scoring | `.../player/SubtitleSyncMatcher.kt` |
| Subtitle menu, selection application, media item rebuild, startup watchdog | `.../player/PlayerScreen.kt` |
| AI batch translation (Groq/Gemini generateContent) | `.../player/SubtitleTranslationManager.kt`, `SubtitleTranslationService.kt` |
| Live hearing (Gemini Live WS, audio → target-language text) | `.../player/GeminiLiveTranslationService.kt`, `AudioCaptureProcessor.kt` |
| Renderer hooks (buffered-cue reflection, pre-translation, cue offset) | `.../player/AiSubtitleRenderersFactory.kt` |
| Settings UI (toggle lives in **Subtitles** section, not AI) | `.../settings/SettingsScreen.kt` (TV row id **38**), `SettingsViewModel.kt` |
| Cloud backup/restore of settings | `.../data/repository/CloudSyncRepository.kt` |

---

## 1. Settings and what they control

| Setting | DataStore key | Default | Controls |
|---|---|---|---|
| Preferred subtitle language | `default_subtitle` (profile-scoped) | — | The **target language** of matching and AI translation (`targetSubtitleLangCode`, `matchLanguageName`). |
| Auto Find Best Subtitle Match | `subtitle_ai_find_best_match` | `false` | Auto-runs the scan on playback. **AI-independent** (works with no API key). Off by default — users opt in. Key name kept for backward compat. |
| AI subtitle translation (master) | `subtitle_ai_enabled` | `false` | Enables AI features: translation option in menu, AI interim during scans, hearing fallback. |
| Auto-Select AI Translation | `subtitle_ai_auto_select` | `false` | Allows AI translation to activate **automatically** (incl. as the scan's on-screen interim). |
| Model | `subtitle_ai_model` | `GROQ_LLAMA_70B` | Groq or Gemini for batch translation. **Hearing requires Gemini.** |
| API key | `subtitle_ai_api_key` (global) | — | One key used for both batch translation and Gemini Live. |

All of the above (except profile-scoped language) sync via `CloudSyncRepository`
(`subtitleAiFindBestMatch` field in the backup JSON).

### Behavior matrix (all combinations)

| AI master | Auto-select AI | Find-match | On playback |
|---|---|---|---|
| any | any | **off** | Classic flow only: best release-name-scored sub in preferred language. Manual "Find Best Match" menu entry still available. |
| off | — | on | Classic pick shows immediately → **silent background scan** (subtitles hidden during it) → verified match replaces pick, else pick stays. Zero AI/API usage. |
| on | off | on | Same silent scan (user opted out of auto-AI). AI translate remains a manual menu option. |
| on | on | off | AI translation activates directly (no scan). |
| on | on | on | Scan with **AI translation as on-screen interim**; if no match, AI stays (source re-resolved to prefer embedded). |

Menu entries (player → subtitle menu, inside the preferred-language group):
- **"Find Best Match"** (badge `Auto`) — always present when a preferred language is set (`matchLanguageName`), AI-independent.
- **"<Language>" AI translate** (badge `AI`) — only when AI available (`isAiAvailable`).
- D-pad index math: headers = `(match? 1 : 0) + (ai? 1 : 0)`, subs follow.

---

## 2. The scan pipeline

```mermaid
flowchart TD
    A[Scan requested] --> B{Trigger}
    B -->|auto: applyPreferredSubtitle, once per stream<br/>latch: autoMatchAttempted| C
    B -->|manual: menu entry<br/>clears remembered cache entry, useCache=false| C
    C[Wait for sources<br/>continuous-playback clock: embedded grace 8s,<br/>candidates wait 15s, absolute 90s] --> D{Embedded track in<br/>preferred language?}
    D -->|yes| E[Select it — muxed = guaranteed sync<br/>toast: Matched embedded]
    D -->|no| F[Candidates: external subs in pref language,<br/>sorted by release-name score, capped at 10]
    F -->|none| G[toast: no well-synced subtitle]
    F --> H{Remembered match for this exact stream?<br/>only when useCache}
    H -->|hit| I[Re-download text → local file → select<br/>toast: remembered]
    H -->|miss| J[Download all candidates in parallel<br/>keep raw text + parsed cues]
    J --> K{Reference source?}
    K -->|any embedded non-forced non-bitmap track,<br/>English preferred| L[Timing scan]
    K -->|none + AI on + key + Gemini| M[Hearing scan:<br/>Gemini Live audio→text, word overlap,<br/>threshold 0.30]
    K -->|none, otherwise| N[Inconclusive]
    L --> O{Best score ≥ 0.70?}
    M --> O2{Best ≥ 0.30?}
    O -->|yes| P[Select via LOCAL FILE copy<br/>+ write per-stream cache<br/>toast: Matched · N%]
    O -->|no| Q[Fallback ladder]
    O2 -->|yes| P
    O2 -->|no| Q
    N --> Q
    Q --> R{AI translating on screen?}
    R -->|yes| S[Keep AI, re-resolve source<br/>toast: keeping AI translation + best N%]
    R -->|no| R2{AI feature available?<br/>master on + key + source<br/>NOT gated on auto-select}
    R2 -->|yes| S2[Activate AI translation<br/>toast: using AI translation]
    R2 -->|no| T{Current selection already<br/>in preferred language?}
    T -->|yes| U[Keep it<br/>toast: best N%]
    T -->|no| V[Select top release-name candidate<br/>toast: sync unverified]
```

Fallback rationale: AI timing comes from the built-in track (verified), so it beats any
unverified addon pick. Auto-select only governs *unprompted* activation at playback start; a
failed scan explicitly asked for the best available subtitle. Same ladder applies when zero
target-language candidates exist.

### Timing scan (reference collection loop, 300ms ticks)

- Merge **buffered upcoming cues** (reflection into the text renderer; typically only 2–12
  visible) with **realtime rendered-cue intervals** (`onPlayerCues`), deduped by overlap.
  ⚠ media3 picks a cue resolver per the track's cue-replacement behavior. For REPLACE tracks
  (`ReplacingCuesResolver`, e.g. MKV SubRip) the `CuesWithTiming.durationUs` is **C.TIME_UNSET**
  (large negative) — each cue lasts until replaced. The interval converter must coerce
  non-positive durations to a nominal ~2s, or every item is rejected (`end < start`) and the
  buffered path silently returns 0 for that whole content category: scans crawl at realtime pace,
  and realtime windows carry a systematic ~600ms render-lag skew that suppresses well-synced subs
  to ≈ the 0.70 threshold. Realtime reference intervals ≠ authored cue times — buffered is the
  trustworthy source. (When debugging extraction, temporarily log resolver class/field/item
  counts inside `extractBufferedIntervals` — that's how both resolver bugs were found.)
- Guards on the buffered read: candidate-time-range sanity filter; **self-match guard by TEXT** —
  sample up to 8 buffered cue texts and drop the read if ≥half match the previously displayed
  candidate's own (normalized) lines. Guard history, do not regress:
  1. exact-coincidence timing (±60ms both edges) — defeated by the reflection's uniform
     stream-offset shift; a candidate scored 0.86 against itself;
  2. constant-delta timing (median ±80ms) — false-positived on subs cut from the same master
     (Hebrew vs Czech share cue timings), discarding legit references and freezing scans at
     realtime pace;
  3. **text comparison (current)** — language-discriminating, immune to both failure modes.
  The track switch to the reference also propagates asynchronously: ~1.5s settle delay, target
  indices re-resolved from current state, and the embedded override retries up to 10s with fresh
  indices (one-shot appliers are fragile after MediaItem rebuilds), disabling text while invalid
  instead of falling back to preferred-language (which silently kept the displayed sub rendering).
- Realtime intervals: capped at 20s each (longer = seek artifact), reset on backward seek.

**Exit conditions** (first hit wins):

| Condition | Values |
|---|---|
| Target reached | ≥ 8 intervals AND span ≥ 30s **AND ≥ 4 buffered** |
| Early accept | ≥ 4 intervals, span ≥ 30s, some candidate ≥ 0.8 |
| Fast accept | ≥ 5 intervals, span ≥ 15s, some candidate ≥ 0.85 |
| Give-up (no speech yet) | 4 min |
| Give-up (stagnation) | 90s since last new interval (clock **frozen while paused**) |
| Absolute ceiling | 10 min |

Post-loop: `< 2` intervals or span `< 15s` ⇒ inconclusive (fall to ladder). Also inconclusive:
best score `< 0.70` with `< 4` buffered intervals — a REJECT from a realtime-dominated reference
is untrustworthy (July 2026, From S03E03: a verified-synced sub scored 0.65 on buffered=2/9 refs
collected in a sparse-dialogue recap, then 0.85 for ALL candidates once buffered coverage
existed). Accepts remain reference-agnostic — only the reject verdict needs buffered quorum
(`MATCH_MIN_BUFFERED_FOR_REJECT = 4`).

### Scoring (`SubtitleSyncMatcher.scoreByTiming`) — HYBRID, calibrated

Per reference window, averaged:
- Window ≤ 7s (a real cue): **best single-cue overlap** — this discriminates offsets; union
  coverage here scored *everything* 0.9+ in dense dialogue (offset subs have cue chains touching
  any short window).
- Window > 7s (realtime merges back-to-back cues): **union-of-cues coverage** — a synced sub
  needs several cues to span it; single-cue unfairly caps at ~0.3.

Calibration (user-verified, July 2026): synced subs score 0.71–0.98; a confirmed-offset sub
scored 0.65. Accept threshold **0.70** (timing) / **0.30** (hearing). The good/bad boundary is
narrow — do not nudge thresholds or the metric without fresh A/B evidence.

**Two robustness adjustments to the accept score (July 15 2026, branch `find_best_match_offset`)** —
both apply to accept/reject scoring ONLY, never to the offset search (§ below), which stays strict:
- **Overlap tolerance** (`scoreByTiming(cues, refs, toleranceMs)`, `MATCH_OVERLAP_TOLERANCE_MS` =
  400): each candidate cue is widened ±400ms before measuring overlap, absorbing sub-second caption
  pre-roll and cue-boundary differences (a finely-split SDH reference vs a sub that merges lines).
  Without it a genuinely-perfect sub scored 0.61 on an SDH reference. Kept modest so a sub ≥~1s off
  still fails here and is caught by the offset search instead.
- **Orphan-drop** (`scoreCandidatesWithOffsets`): reference windows covered by NO candidate are
  dropped before scoring — almost always SDH/non-dialogue reference cues the dialogue subs lack.
  Each would score a hard 0 for everyone and crater a perfect sub, worst on sparse refs.

### Constant-offset rescue (`estimateOffsetMatch` + `scoreCandidatesWithOffsets`)

A candidate that scores badly at offset 0 but is a **perfectly-cut sub with a UNIFORM delay** (a
common addon defect — right words, globally-shifted timing) is rescued instead of rejected. During
the *same* scan every candidate is tested for a single constant delay that lines it up.

- **Detection is an offset SEARCH, not cue-start clustering.** `estimateOffsetMatch` sweeps the
  offset directly, maximizing the strict `scoreByTiming`: coarse ±10s @250ms, then fine ±250ms
  @25ms. A cluster-of-`refStart−cueStart`-deltas approach was tried FIRST and failed on real data —
  the reference is a finely-split English CC track while addon subs merge dialogue into fewer/longer
  cues, so cue *starts* never line up 1:1 even at the correct offset (Dutton Ranch S01E01: a flat
  +1000ms was visually perfect yet start-deltas scattered +583/−617/−2208). Overlap-max is
  segmentation-agnostic. Offsets `< MATCH_OFFSET_MIN_MS` (300) are "already aligned" — ignored.
- **Cross-candidate corroboration is the acceptance signal.** The candidates are the same episode
  from different addons, so a real global offset appears in ≥2 of them; a search-noise outlier does
  not. Each candidate votes its best offset (if it beats its strict base and clears `FLOOR` 0.62);
  votes within `CORROBORATE_MS` (400) are clustered; if ≥`CORROBORATE_MIN` (2) agree, the median is
  the trusted offset. Every candidate is then re-scored (tolerant) at that shared shift and accepts
  at `CORROBORATED_ACCEPT` (0.68) — **decoupled from the 0.70 timing bar** because segmentation caps
  a perfect-but-differently-cut sub around 0.71. `findBestSubtitleMatch`'s accept test is therefore
  `score ≥ successThreshold OR (offsetMs ≠ 0 && score ≥ 0.68)`. Real example that both rescued the
  true offset and rejected the outlier: `votes=[−4400, 1000, 1000, 1000] → 1000ms (support=3)`.
- A **lone** candidate (no corroboration possible) still rescues at the stricter `MATCH_OFFSET_ACCEPT`
  (0.80). Mid-scan, once a corroborated offset already yields an acceptable winner, collection ends
  early (`refs ≥ MATCH_OFFSET_EARLY_REFS`, buffered quorum).
- The offset is **baked into the served local file** (`shiftTimestamps` in `localizeSubtitle`),
  never the player's delay knob — so it can't leak across track/source switches. The served copy's
  id gets a `#ofs<ms>` marker (`MATCH_OFFSET_ID_MARKER`): a distinct track id that forces the
  preload path to rebuild onto the shifted file rather than override to the un-shifted preloaded
  copy sharing the base id. The menu matches rows by **base id** (`subtitleBaseId` /
  `isSameSubtitleTrack` in PlayerScreen) and shows `· +1.0s` on the selected row. The per-stream
  cache stores `offsetMs` and re-bakes it on remembered hits. Toast: `… (auto-offset +1.0s)`.
- Log (tag `SubMatch`): the per-candidate verdict line carries the outcome —
  `[builtin] candidate … score=<final> offset=<ms>` (offset shown only when one was applied).

---

## 3. Selection & the local-file trick

Selecting an **external** subtitle rebuilds the MediaItem (re-prepare) — *unless* the track was
side-loaded at startup by **Preload Subtitles** mode (see §3b), in which case selection is a pure
`TrackSelectionOverride` with no rebuild. Economics of the rebuild path:
- Video re-open: debrid streams are deliberately **not disk-cached** (I/O bottleneck) — usually
  fine, occasionally slow/stale.
- Subtitle download during rebuild was the common stall (slow addon proxies) ⇒ matched subs are
  served from a **local cache file** (`cacheDir/matched_subs/`, ≤40 files):
  the scan already downloaded the text; `localizeSubtitle()` writes UTF-8 (gunzipped) and selects
  a `file://` copy (same id). The data source chain wraps OkHttp in `DefaultDataSource` for
  file support. The tracks-changed remap keeps `file:` selections as-is (would otherwise swap
  back to the remote URL and re-trigger a rebuild).
- **Do NOT preload all REMOTE subs into the MediaItem** — tried historically and reverted:
  ExoPlayer eagerly downloads every side-loaded config at prepare (30+ requests), plus encoding
  issues. Preload Subtitles mode (§3b) is the sanctioned exception because it attaches **local
  file copies only** — the eager read is free and encoding is already normalized.

## 3b. Preload Subtitles mode (July 2026)

Setting **"Preload Subtitles"** (`subtitle_preload_enabled`, global, cloud-synced, row 39
in Settings → Subtitles, default **ON** since July 2026). Scoped to the preferred
language, downloading the files ourselves before prepare.

Flow:
1. `PlayerViewModel.preloadSubtitles()` runs after every addon-subtitle merge (all 3 fetch sites
   + the no-imdbId branch): downloads preferred-language external subs (cap
   `MAX_PRELOAD_SUBS = 15`) via `SubtitleSyncMatcher.loadRaw` → `localizeSubtitle` (same
   `matched_subs/` files the scan serves) and publishes them as
   `PlayerUiState.preloadedSubtitles` + `subtitlePreloadComplete` (always set, even on empty —
   the gate must never hang; also pre-set when no preferred language).
2. PlayerScreen's prepare effect **gates** the initial `setMediaSource` on
   `subtitlePreloadComplete` (cap 14s), attaches all `file://` configs, then **re-anchors
   `streamSelectedTime` and clears `startupRecoverAttempted`** — otherwise a slow subtitle addon
   would eat the startup watchdog budget and trigger source failover. The primary time bound is
   upstream: in preload mode the addon-list fetch gets a **10s soft deadline**
   (`fetchSubtitlesForSelectedStream(softDeadlineMs)`) — finished addons are harvested, slow ones
   are cancelled and dropped for this playback, and the pending addon names stream to the loading
   screen ("Loading subtitles… (addon)") so slow addons identify themselves to the user.
3. Sidecar configs only merge through a `DefaultMediaSourceFactory`; the dedicated
   `preloadMediaSourceFactory` streams video **uncached** (debrid I/O rule) while supporting
   `file://` for the subs. HLS keeps working via the M3U8 mime hint (loses only chunkless prep);
   DASH needs the explicit `APPLICATION_MPD` mime because DefaultMediaSourceFactory doesn't
   recognize the "/dash"/"format=dash" URL shapes.
4. Selection: the subtitle-switch effect first looks for an attached text track whose format id
   matches `subtitleTrackId(subtitle)` and applies a `TrackSelectionOverride` (no rebuild). This
   catches the **find-best-match winner** too: `subtitleTrackId` is provider|id-based (never a
   URL hash for id-carrying subs), so the scan's localized `file://` copy resolves to the same
   attached track. Non-attached picks fall back to the rebuild path, which in preload mode
   re-attaches the preloaded set + the new sub and then pins the exact track by id (language
   preference alone is ambiguous with several same-language tracks attached).
- `subtitleTrackId` ids are provider-qualified with `|` — never `:`, because ExoPlayer prefixes
  side-loaded format ids with `periodIndex:` and matching strips at the last `:`.
- Mid-session source switches don't reset `subtitlePreloadComplete` — the files are per-title,
  the gate passes instantly and the new MediaItem re-attaches the same copies.
- ⚠ Ordering side effect: the gate guarantees the scan starts BEFORE the player exposes embedded
  tracks (prepare waits for the very fetch that triggers selection). Two AI-interim fixes hang on
  this (pre-preload both were masked because prepare raced ahead of the fetch):
  1. No source at scan start → interim silently skipped and `autoMatchAttempted` blocks re-runs ⇒
     late activation from `updatePlayerTextTracks` (scan running + auto-select + embedded source
     just arrived). `activateAiTranslation` also resolves a blank `aiTargetLanguageName` from the
     preferred-language code — the silent path never populated it, and a blank target made the
     manager translate to nothing.
  2. Interim activated early with an EXTERNAL English source → the scan's reference-track switch
     (`scoreAgainstBuiltIn`) used to hard-set `isAiTranslating = false` when the AI source ≠
     reference, killing the interim mid-scan. It now MIGRATES the interim onto the reference
     (embedded non-bitmap = the preferred AI source) instead of disabling it.

Subtitle MIME sniffing strips trailing slashes (`…/sub.vtt/?q=` is VTT — AIOStreams shape);
default is SRT for extensionless URLs.

## 4. Per-stream remembered cache

- Key = stream identity, **not** title: `infoHash:fileIdx` → `videoHash` → `filename:size` →
  URL hash. Same episode from a different source ⇒ different key ⇒ fresh scan (sync is a property
  of the file).
- Value = candidate `provider|id` (not URL — addon URLs are ephemeral). Stored as JSON list in
  `subtitle_match_cache_v1` (global DataStore), LRU 50. **Not cloud-synced** (deliberate).
- Written on verified match only (never for unverified fallback picks).
- Manual track pick in the target language **overwrites** the entry; picking anything else
  clears it. Manual "Find Best Match" click clears it and scans fresh (`useCache=false`).

## 5. State resets (bugs lived here)

- `loadMedia`: resets all selection flags, `autoMatchAttempted`, AI source, **and the subtitles
  list** — externals accumulate additively during one video; without clearing, a new video scans
  the previous title's candidates (references vs wrong-show subs ⇒ uniform ~0.1 scores while the
  correct fresh sub isn't scanned at all).
- Provided-URL loads with **no resolvable IMDb id** must still run `scheduleSubtitleSelection`
  (embedded tracks + AI exist without addon subs). The silent else-branch here was one cause of
  the "plays with subtitles Off, no scan, nothing" regression.
- The deeper cause: the subtitle fetch/selection block was the **last child coroutine of the
  load job** — an uncaught exception in ANY sibling (VOD/home-server/stream appenders) cancels
  all siblings, killing the subtitle flow before its first line. It now runs on `viewModelScope`
  directly (as `subtitleRefreshJob`, cancelled by `loadMedia`), and each sibling is
  failure-isolated with a `load child '<name>' failed` log. Rule: anything subtitle-critical
  must not share a Job with flaky background appenders.
- `selectStream` (mid-session source switch — does NOT pass through `loadMedia`): must reset
  selection flags, purge **embedded** subtitle entries (per-file!), clear selection with a nonce
  bump, cancel a running scan. Missing pieces here caused: wrong-track selection (stale indices),
  "selected but not rendering" (no nonce bump), auto-flow never re-running (stale
  `hasManualSubtitleSelection`).
- During the scan, subtitles are hidden (`subtitleView.visibility`) when
  `isFindingBestMatch && !isAiTranslating` — display-only; cue collection listens on the player.
- Startup watchdog failover must reset `streamSelectedTime` optimistically before `continue`,
  or it burns the whole source list in milliseconds (stale-clock burst).

## 6. AI specifics

- Batch translation pre-fetch (`triggerPreTranslation`/`preTranslateWindow`) is gated on
  `translationManager.isEnabled` — without this it spends API requests whenever any track renders
  (the 401-toast-with-AI-off bug).
- **AI translation source is a built-in (embedded) ENGLISH track ONLY** (`findAiSourceSubtitle`,
  July 15 2026). Never an external addon sub, never a non-English embedded track. So "no embedded
  English ⇒ no AI translation": on such a source the AI interim doesn't activate, which (a) matches
  the intended feature scope and (b) frees the single Gemini pipeline so the **hearing** scan can
  run — previously the AI interim activated off an *external* English sub, grabbed Gemini, and the
  `isAiTranslating -> null` dispatch branch silently skipped hearing so "Find Best Match" never ran.
  Ladder on a no-built-in source: hearing (needs AI on + Gemini key + model) → else the top
  release-name-scored addon sub (`selectLastResort`). The timing-scan reference (`builtInReference`)
  is separate and may still use a non-English embedded track. Tradeoff: a source with only an
  external English sub (no embedded English, no target-language addon) no longer gets AI Hebrew.
- AI interim during scans: only when `aiSubtitleEnabled && aiSubtitleAutoSelect`. Source is
  re-resolved on every activation (embedded English only, per above) — a stale source caused
  mistimed translations.
- Hearing fallback: Gemini model + key + AI on; aborts on WS ERROR; same progress-anchored
  timers as the timing scan.
- Gemini Live target language comes from the preference (`targetLanguageCode`), default `he`.

## 7. Provider quirks (see also memory: project_aiostreams)

- **AIOStreams**: subtitles endpoint slow (8–12s) / 502s cold → subtitle fetch is parallel,
  30s timeout, one retry. Its streams are exempt from quality sorting (`keepsOwnStreamOrder`) —
  user pre-sorts server-side. Sub URLs end `…/sub.vtt/`.
- **OpenSubtitles v3+ proxy** (`opensubtitles.stremio.homes`): VTT with double `WEBVTT` header,
  a branding banner cue at 0:01–0:06, recap lines as NOTE comments. Scanner's parser is tolerant;
  keep an eye on ExoPlayer's stricter parser if display anomalies appear.

## 8. Known-good log workflow

Debug via `adb logcat` tag `SubMatch`:
- `reference source=…` — scan entered scoring (after source-wait).
- `align src=buffer|realtime|buffer+realtime buffered=N total=M refs=[…] candNear=[…]` —
  reference intervals vs the first candidate's cues nearest the scan window; `src=buffer` +
  score **exactly 1.00** = self-match red flag; `buffered=0` = extraction dead (see §2 warning).
- `[builtin] candidate … score=X` / `[hearing] candidate …` — per-candidate verdicts.
- Rare warnings that flag real faults: `realtime self-cue interval dropped` (reference track
  switch didn't land), `subtitle fetch skipped: no imdbId`, `load child '<name>' failed`,
  `hearing aborted`, `match cache write failed`.
Toast decoder: "Matched · N%" = verified; "(remembered)" = cache hit; "(sync unverified)" =
fallback pick; "No well-synced … (best N%)" = rejection with evidence.
