# Dolby Vision Compatibility (DV7 → HDR10 base layer) — Reference

Strip path only: a native libdovi DV7→DV8.1 conversion is deliberately NOT implemented —
the interface seams for it remain in the vendored extractor for a future phase.

**Problem:** DV profile-7 BluRay-remux MKVs on devices without a DV decoder reach READY, play
audio, but render zero video frames (`black video recovery … size=0x0`), causing source-failover
churn. The DV7 RPU rides in Matroska **BlockAdditional**, which stock media3 discards before any
`TrackOutput` — there is no app-level seam, hence a vendored extractor.

**Code map**

| Concern | Location |
|---|---|
| Vendored media3 1.9.0 MKV extractor + DV hooks | `app/src/main/dvmkv-java/com/arflix/tv/player/dvmkv/` (own `java.srcDir`; see its `package-info.java` for the re-vendoring procedure on media3 bumps) |
| RPU/EL NAL stripping (types 62/63, `nuh_layer_id>0`) | `player/dv/HevcDvRpuStripper.kt` |
| HDR10+ SEI stripping (optional, off) | `player/dv/HevcHdr10PlusStripper.kt` |
| Strip-mode transformer (per-sample) | `player/dv/DolbyVisionStripTransformer.kt` |
| Wrapping ExtractorsFactory (MKV-only swap) | `player/dv/DolbyVisionStripExtractorsFactory.kt` |
| Device policy (display HDR caps + decoder probe) | `player/dv/DolbyVisionBaseLayerPolicy.kt` (called with `bridgeReady=false` → CONVERT branches dormant) |
| Wiring + per-URL forcing | `PlayerScreen.kt` (`dvPolicy` / `dvStripExtractorsFactory` / `dvForcedStripUrls`, factories block; black-video watchdog last-resort branch) |
| Setting `dolby_vision_compat` (default ON) | Settings → Playback row 40; SettingsViewModel; PlayerViewModel → `PlayerUiState.dolbyVisionCompatEnabled`; CloudSyncRepository (**`has()` guard** — old-version backups must not reset it) |

**How it works**
1. `DolbyVisionBaseLayerPolicy.resolve()` (once per player composition): display `HdrCapabilities`
   + `MediaCodecList` DV-profile probe. Devices with a real DV display+P7 decoder (Shield-class) →
   `NATIVE_DV7` → factory is null → **zero change to playback**. Everything else → strip available.
2. `DolbyVisionStripExtractorsFactory` wraps `DefaultExtractorsFactory` and is installed at three
   seams: `directProgressiveFactory`'s DV twin (heavy/debrid MKV — the main path),
   `mediaSourceFactory`, `preloadMediaSourceFactory` (constructor arg — media3 has no setter).
   The `enabledProvider` is consulted per `createExtractors()` (= per prepare), so the user
   toggle / per-URL force apply without factory rebuilds. It also refreshes the process-wide
   `DolbyVisionCompatibility.setHdr10BaseLayerModeActive` flag that gates the vendored
   extractor's format rewrite (`video/dolby-vision` → `video/H265` + container's base-layer
   codec string).
3. **Profile-7 gate (vendored extractor):** the DV transform/relabel path only engages for tracks
   whose parsed DV config is **Profile 7** (`Track.dolbyVisionProfile == 7`, set in
   `initializeFormat`). Non-DV HEVC (no DV config) and **Profile 5** tracks fall through to the
   stock streaming write — no full-frame materialization, no per-frame allocation (a normal 4K
   remux is byte-for-byte stock behavior), and the format is NOT relabeled to HEVC (P5 has no
   HDR10 base layer — relabeling it would render broken colours). Gate covers all three sample
   sites + `handleBlockAdditionalData` + the codec relabel.
4. Per sample (P7 only), `DolbyVisionStripTransformer` drops the BlockAdditional RPU and strips
   RPU/EL NALs (`HevcDvRpuStripper`). Any strip failure returns the ORIGINAL sample; the extractor
   additionally try/catches the transformer per sample — degradation, never hard error.
5. Last-resort ladder: if black-video recovery exhausts its stages on a `isLikelyDolbyVisionStream`
   source while strip was NOT active (native-DV policy or toggle off), the URL is added to
   `dvForcedStripUrls` and the source is re-prepared once with strip forced, before advancing to
   the next source.

**Scope notes**
- MKV-only by design: DV P7 in the wild = BluRay remux MKV. MP4 WEB-DLs are P8 (media3's built-in
  DV→HEVC decoder mapping already handles) or P5 (cannot be stripped). MP4/fMP4/TS sample
  interception would only matter for a future RPU-conversion path — out of scope.
- Pure JVM: ships in BOTH flavors (play + sideload), no NDK.
- `compileOnly("org.checkerframework:checker-qual")` exists solely for the vendored sources.

**Log workflow** (`adb logcat -s DvCompat PlaybackStartup:*`)
- `policy=STRIP_TO_HDR10 … DV strip available` — device qualifies (logged once per player).
- `DV strip active: first sample rewritten (N -> M bytes)` — the pipeline is actually rewriting.
- `black-video exhausted — forcing DV strip for this source` — the last-resort ladder fired.
- Success = first frame renders on a DV7 remux with NO `black video recovery` lines.
