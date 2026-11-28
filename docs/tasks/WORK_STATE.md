# Work state

## 28 Nov 2026 (4) – search fixes (Med: "search should be and look better"; fixes now, redesign preview next)
- `SearchScreen`: scroll to the top on every query change after opening (the keyboard keeps focus on its keys, so the old
  `fieldFocused` condition never held); auto-saved searches only when the last word is whole in a shown title;
  opening a result saves `SearchAllLogic.historyTerm` (last word completed from the title, null for fragments).
- `SearchIndex.find`: word-start matches first; inside-word matches after them for movies/series, dropped for channels
  (`SearchIndex.wordStart`). Tests in SearchLogicTest, SearchAllLogicTest (logic also run locally with kotlinc).
- Not changed: "On TV" (EPG) matching, row order, card design (redesign preview pending Med's approval).

## 28 Nov 2026 (3) – source stops mid-show (#82, Med: "Connection lost – reconnecting" on black)
- Cause (#82, Fire TV, 1.3.295): playlist VOD (m3u, mkv, 4K HEVC) played 50 min, then IO_UNSPECIFIED; every later try
  of the same file failed in about 5 s ("no format yet"), also after restarting the app. The TV was online. The app took
  it for an internet drop: up to 12 tries with growing pauses (~3 min of black), then 3 more, only then another source.
- Fix: `PlayerRecoveryLogic.askInternet` (movie/episode, online, after `ONLINE_TRIES` = 2): `DeviceCaps.internetWorks`
  (generate_204, 4 s); answers → `giveUp(sourceDown)` = the existing switchSource → Marathon → stremioFallback chain at
  the same spot; no answer → wait as before. A source that played and then dropped is not counted as a dead link.
- Test: `PlayerRecoveryLogicTest`; playback scenario tt9000008 (`cutoff` media: drops after 1 MB + 15 s, then empty
  answers; the other copy must show a picture past the saved spot and play at the end).
- Why the provider stopped: unknown from the report (server side; a connection limit or the file host). Not tested on a TV.

## 28 Nov 2026 (2) – tighter automatic subtitle sync (asked by Med)
- Med: "best match" and version 2 were a bit off, the sync said "Couldn't match the voices clearly", version 3 was right.
- `SubSync.kt`: `SpeechTap` keeps voice-band (250–3500 Hz, `SubSyncLogic.Band`) energy of the centre (5.1 centre
  channel, stereo mid minus side, floor 5 % of mid), 150 s. `SubSyncLogic.speech`: log-scale threshold between 10th and
  97th percentile, blips < 60 ms dropped, pauses ≤ 200 ms filled. `align`: scores the first 1.2 s of each line and
  penalises speech in the 400 ms before it; a frame-rate stretch must beat 1.0 by 10 %; `clear` at 0.2.
- Offline check (Python port, LibriSpeech clips from librosa's example data over 3 real music tracks, 108 mixes, known
  offsets and a 25/23.976 stretch): old 17 right / 7 wrong / 84 left alone; new 86 within 150 ms, 8 within 400 ms,
  0 wrong, 14 left alone. Not committed (scratch script).
- `SubSyncLogicTest`: filter coefficients, voice band, pauses, whole chain with a centre voice under wide noise;
  cue test data made irregular (evenly spaced lines match one line over). Run locally with kotlinc and a JUnit stand-in;
  SpeechTap compiled against stubs and fed simulated stereo. Playback scenarios cannot serve online subtitles.
- Not tested on a TV or with real films.

## 28 Nov 2026 – daily auto-report run
- `FreezeWatch`: a watch-thread sleep that overslept by a freeze length (`FreezeLogic.paused`) means the process was
  paused (cached-app freezer), not a freeze; measuring restarts (#77, 155 s "in" `performRestart`). Test in FreezeLogicTest.
- `Ambient`: no crossfade on Lite (#78: slow frames were the 400 ms fades at GPU 79 %).
- Closed: #57 (Sports, fixed 1.3.286, no newer report), #69/#70 (old freezes, no report since 1.3.241/1.3.238),
  #79 (addon's link host: timeout + HTTP 429).
- Open with causes: #49/#56 (GPU-bound navigation, needs a design decision), #50/#80 (player draws at the video's
  ~29 fps with no key, cause not confirmed), #55/#68 (wait for 1.3.293+ reports).
- Not tested on a device.

## 27 Nov 2026 (6) – essentials (approved by Med)
- Promotion: stable channel was still on 1.3.189 (23 Nov). A gate change (accept passes on later commits with the same
  app code) was blocked by the session's safety check and NOT made. The way out is a build whose own app commit passes
  playback, soak and perf: this one (9974024), then a hand promotion (promote.yml "now"). 1.3.291 passes the gate too.
- `LiveDeadLogic` + LaunchedEffect in `PlayerScreen`: live, under 64 KiB after 3 s → other quality (`liveAlts`), after
  8 s → message, `deadLive` (no AutoReport: provider's fault, would hold promotion), Up/Down zap from the message.
  Scenario live-dead (fixture "dead" live channel).
- `Mem.planHeap()` (192 MB tier 1, 128 MB tier 0) for `heapTight`, playlist budget, buffer bytes. Test in PlayerBufferTest.
- `HomeRow.kt`: Watch Next via the platform TV provider (raw column names), `AppNav.openResume` → `resumeSaved`.
  **Unverified**: whether Google TV shows rows from this app without extra permission; not tested on a device (CI
  emulator has no TV provider).

## 27 Nov 2026 (5) – Skip more, intro by sound (approved by Med)
- Skip more: `moreOn`/`skipMore` in `PlayerScreen`, root OK takes it for 10 s after each press; learning waits until
  25 s after the last change.
- `IntroPrint.kt`: `IntroPrintLogic` (Haitsma–Kalker style 31-bit codes per 100 ms over 0.5 s, 32 bands 300–3000 Hz at
  about 8 kHz, energy floor −40 dB, dead zone 1e-3; `common` = longest shared run 15 s–3 min, `locate` = live match of
  the last 4 s), `IntroTap` (via `AudioTaps` in `SoftAudio`'s tee; anchored by the 500 ms position loop), `IntroPrints`
  (files/intro_prints, print + template per show/season, max 120 files). Detected intros become ordinary `Seg`s.
- Parameters checked offline in Python (numpy) on synthetic audio and on the AAC fixture files: intro found within
  ±0.5 s, recognised about 2 s after the theme starts, steady tone and different noise rejected.
- Tests: `IntroPrintLogicTest`; playback scenarios tt9100003:1:1–3 (fixture makes intro_a/b/c.mp4 with ffmpeg at
  start), Skip more in tt9100002:1:1. Not tested on a real TV or real shows; Dolby passthrough cannot be heard.

## 27 Nov 2026 (4) – Skip intro for every show (approved by Med)
- `IntroLearn.kt`: `IntroSkipLogic` (pure: window 12 min / half the episode, jump = learned length or 60 s, learning from
  where the viewer lands within 25 s, SkipDB parsing; `IntroSkipLogicTest`) and `IntroLearn` (show intro length as a new
  key in `fof_intro_marks_v1`, per profile, synced by CloudSync; nothing renamed).
- `IntroDb.skipDb` (api.skipdb.tv/api/segments by IMDb id, no key; response shape from its docs, **unverified** from
  here: the session cannot reach the API). TheIntroDB v3 kept (frozen but working; v4 is keyed by TVDB).
- `PlayerScreen`: `learnSkip` offer, "Skip intro" pill above the bar focused when the bar opens while playing (Play
  keeps focus when OK paused), `learnJump`, `noteSeek` at both seek commits, learning effect also saves the episode window
  via `IntroMarks`. Playback scenario tt9100002:1:1.
- Not tested on a TV.

## 27 Nov 2026 (3) – player redesign (approved by Med from the preview canvas)
- `PRoundButton` (square, showLabel; transparent at rest), VOD bar in `PlayerScreen` (centre transport 48/60 dp, options
  46 dp right, catch-up left; sized for 960 dp), `PTimeRow(speed)` end time, subtitles lifted via
  `SubtitleView.setBottomPaddingFraction` while the bar shows, `PPanelFrame`/`PPanelRow` one look (`PT.PanelBg`, `PT.Hint`).
- Panels: `TracksPanel` "Adjust subtitles" sub-view; `EpisodesPanel` bottom strip with `PEpisodeCard`; Settings short list
  + `menuMore`; `PlayerSourcePicker` top 3 + "Show all"; `WatchPartyPanel` same card look. Live bar keeps its layout.
- Playback scenario tt9000007 (pause with OK, resume from the bar's Play button). Not tested on a TV.

## 27 Nov 2026 (2) – causes of all open auto-reports, fixes on branch `auto-fix-reports`
- Every open auto-report issue has a cause comment (file:line, confidence). Closed #51/#59 (guide sort, fixed 1.3.271).
- Fixed (branch `auto-fix-reports`, own branch because `auto-fix` carries the unreleased subtitle work of another
  session): #72 `Calendar.kt` playlist lookup off the main thread; #53 `MainActivity` does not draw `AppContent` under
  the screensaver; #62 `SportsStartupLogic.noData` (64 KiB) + trickling dead feed in `hang_server.py`, check in
  `sports_check.py`; #50 `PlayerScreen` no composition read of `pos` (Animatable glide, derived `nearEnd`), seek scenario
  tt9000006 in `playback_fixture.py`; #49/#56 Lite skips the billboard crossfade, glow 300 ms; #55/#68
  `Mem.imageCacheBytes`, `BufferLogic.byteLimit(maxBytes)`, `AreaCache`/`LiveBundleLogic.clear` in `setItems` and `Mem.trim`.
- Not changed (would hide titles or catch-up guide data): playlist budget share, EPG parse limit, `Xmltv.progs` trim.
- Not tested on a device. Next: watch reports from the new version for #49/#50/#53/#56/#57/#69/#70.

## 27 Nov 2026 – daily auto-report run: Sports page freeze
- Fixed #71/#73/#75: `SportPins.base/score` compiled 10 regexes per playlist channel inside `SportSplit` composition;
  now precompiled, and `SportSplit` resolves pins with `produceState` on `Dispatchers.Default`
  (`SportPins.resolveAll/cached`). Test: `SportPinsTest`.
- Closed #58/#60/#67 (pick phase fixed in 1.3.254, no newer report) and #74 (provider net+decode).
- Still open: #57 (Sports slow frames, probably the same freeze – check after release), #72 tab11, GPU-bound perf
  #49/#50/#53/#56, old freezes #51/#59/#69/#70, low memory #55/#68, onn failover #61/#62.

## 26 Nov 2026 – 1.3.279: detailed reports (T-010), resume fix; online subtitles on branch `auto-fix`
- Released 1.3.279: T-010 (see `docs/tasks/T-010-detailed-reports.md`), movie Resume asks addons for a fresh link
  (`ResumeFreshLogic.movieUrl`, `DetailScreen` Resume uses `targetFresh`), #63 and #64–#66 fixes (1.3.271).
- Branch `auto-fix` (CI green, not released): online subtitles (`OnlineSubs.kt`, `PlayerScreen.kt` LaunchedEffect after
  first READY, `OnlineSubsLogicTest`). OpenSubtitles v3 answer format checked (url, 3-letter `lang`, `pob`).
- Not tested on a device: all of the above. Next: check the first `idle-draw:`/`perf:` issues with the new details.

## 24 Nov 2026 – NBA start speed + series layout C (release candidate, `[device-test]`)
- Serial waits removed between OK and picture: ConnGuard provider round trip (cached count, prefetch via `StartPrep`),
  SmartDns (parallel DoH, 1.5 s budget, stale cache), live start buffer (`BufferLogic.liveStartMs/learnedNow`),
  SportFresh identical-name rebuilds; hanging first feed left after 2.5 s without data (`SportsStartupLogic.NO_DATA_MS`).
- `StartTrace`: OK → pick → guard → ui → failed feeds → net+decode; logcat tag `FofStart`, report `start:` when ≥ 3 s.
- launch-test job `sports-start`: synthetic provider for tonight's live NBA game (ESPN scoreboard), dead first feed,
  team channel, feed of another game. Emulator result on Pistons–Magic (before the 2.5 s change): cold 7.4 s
  (4.6 s on the dead feed, 2.5 s net+decode), repeat 2.1 s, right feed both times, never the other game.
- Emulator after the bytes-based dead-feed rule (Celtics–Wizards, 24 Nov 21:3x ET): cold 5.3 s (dead feed 2.5 s,
  net+decode 2.0 s), repeat 2.3 s, right feed both times. A first version judged "no data" by buffered video and dropped a
  working feed; replaced by `StreamBytes` (bytes read in `HostFailover.DataSourceWrapper`).
- Series page layout C (`DetailScreen.kt` eppane: `SeasonPill`, `EpisodeCard`, `EpisodePanel`, `SynopsisDialog`).
- Not measured: real onn 4K / Fire TV, real provider (DNS, ConnGuard, redirects), TiviMate comparison.

## 24 Nov 2026 – Sports playback startup
- Live game cards queue background source matching. Switching games cancels the prior selection. Lookup errors use the source picker and existing diagnostics.
- The fresh channel-name cache uses the identity of the published map to avoid hashing all its entries per lookup.
- Local whitespace validation passed. Gradle download was blocked. Verify Android CI and test game launch, rapid selection, and Back on onn 4K and Fire TV.

## 24 Nov 2026 – Reliability overhaul, batch 1 (release candidate, `[device-test]`)
- Done: addon sync no longer drops/removes DragonStreams on a failed manifest read (`AccountCloud.syncSecret/applyAddons`,
  `AccountCloudLogic.covers`); stale DragonStreams answer reset per lookup (`Stremio.lookupStreams`), 12 s wait
  (`StremioUi`); sports event-identity guard (`SportGuard`, `SportWatch.guard`); start refresh at most every 4 h with
  30 min failure backoff (`SettingsLogic.startRefreshDue`, `General.markRefreshFailed`, `PlaylistStore.lastLoadFromNetwork`);
  spinner/skeleton animations off on Lite/Motion off (`Ui.kt`).
- Tests added: `SportGuardTest`, `AccountCloudLogicTest` (covers/addonHost), `SettingsLogicTest` (startRefreshDue). Run in CI.
- Evidence used: auto-reports #41–#43 (onn 4K: Home frozen 3.4 s, Sports 97 % slow frames with GPU/sync ~81 %).
- Not done / next: device profiling of Sports GPU cost and Home freezes; Live TV guide redesign; player reuse on zapping
  review; diagnostics export. Device test checklist in the release notes.

## 15 Nov 2026 – 1.3.84: one account per household (T-007)
- See `docs/tasks/T-007-one-account-profiles.md`. Backend applied and tested (`docs/supabase/accounts_test.sql`).
- Next: maintainer test on two TVs (sign in, PIN per profile, resume on the other TV, Connected Devices).

## 15 Nov 2026 – 1.3.72: personal data sync (CloudSync)
- `CloudSync.kt` (Android) + `CloudSyncLogic.kt` (`SyncLogic`, pure, tested in `SyncLogicTest`).
- Stores: ipvio_watch, ipvio_eprog, ipvio_hist, ipvio_lib, fof_catalog_items, ipvio_catfavs, ipvio_chlists of the
  profile linked at sign-in (`fof_account` keys sync_profile, sync_hash, sync_at).
- Supabase `user_data` (one row per person, RLS self only) + trigger `user_data_touch` (server time). Live-tested with a
  throwaway user (save, update, read back, anon blocked); test user deleted.
- Not synced: sports teams, settings, avatar (device-wide).

## Last session (15 Nov 2026) – release 1.3.70
- Done: full audit on the test TV (all CHECK PASS, screenshots of every screen reviewed; fixed German design names,
  "0 seasons", sports row order), premium neutral accent (no red), Home live row NBA + soccer, join-family on first start,
  owner restore, problem reports (Supabase table `reports`, owner list on the phone page), update channels
  (prerelease → promote.yml after 24 h), key-rotation signing (release.yml + secret `FOF_SIGNING`), install link
  is.gd/getfoftv, test TV screenshots on branch `perf-shots`.
- Signing: secret `FOF_SIGNING` added by the owner (15 Nov); `signing/fof-tv.jks` removed from the repository,
  release.yml has no fallback any more (a missing secret stops the release).
- Lint: 13 errors left, none crash risks (NonObservableLocale, RememberInComposition, RememberReturnType, RestrictedApi,
  UnspecifiedRegisterReceiverFlag in test code).

# Work state

Last update: 2026-11-12. Read `CLAUDE.md` first, then this file.

## Fof TV (fork) – state on 2026-11-12

Released builds: `v1.3.15` to `v1.3.20` (GitHub Actions `release.yml`, every push to `main`). See `CHANGELOG.md` for the list.
New files: `Catalog.kt` (Cinemeta catalog, disk cache, playlist matching), `SportSession.kt` (sports failover, live-game switching,
score bug and ticker), `SettingsWeb.kt` (settings website `/s`), `FofPrefs.kt` (one-click play), `DictFof.kt` (missing English texts),
`app/src/main/baseline-prof.txt`.
Checks run: Gradle build and unit tests in CI; emulator launch test (`launch-test-r8`) passed with R8 on.
**Not tested on a device:** scrolling feel, one-click stream choice, sports failover timing (9 s), Up/Down game switching,
settings website on a phone, multiview with the provider's connection limit.
Next step: device test with the maintainer, then fix what it finds.

## Latest (1.3.33)
- Search over everything: `SearchAll.kt` (new), `SearchScreen.kt`, `Catalog.search`, `TmdbExtra.person`, `openInHub` in `SportHub.kt`, menu migration `menu_ver` 3 in `Store.kt`, `MenuLogic.DEFAULT_HIDDEN` = "6"
- Test: `SearchAllLogicTest` (runs in CI). Not checked on a device yet.

## Current task

**T-009 Xtream backup servers** (see `docs/tasks/T-009-xtream-backup-servers.md`): merged into `main` through #37 (#3 rebased with the review fixes from #35, without the commit of #2; #3 and #35 closed). CI passed. Device test PENDING before the next release.

None active. **P2 batch 8** (final comment sweep) is done in the working copy; the commit by the maintainer is pending. The restructuring plan U-0 is recorded in `docs/REFACTORING-PROGRESS.md`.

## Completed steps

- P2 batches 1-7 committed. Batch 8: a tokenizer read every comment in all `*.kt` and `*.kts` files (strings and raw strings skipped), plus XML, TOML and the `#` lines in the dictionaries. 57 comment lines in 33 files translated, plus 4 lines in files that were already changed in the working copy. `RemoteUi.kt:39` repaired (the UI key "Beenden" was lost in an earlier edit).
- Checks: same line count in every file; every changed line is a comment, a `#` line in `DictNl5` (skipped by the parser, `I18nCore.kt` lines 26 and 65) or an XML comment; code before trailing comments is byte-identical; string literals unchanged.
- Restructuring plan U-0 with decisions 1-5 (see `docs/REFACTORING-PROGRESS.md`).

## Changed files (batch 8)

`AppIcons.kt`, `CatchupLogic.kt`, `DesignK.kt`, `DesignPreview.kt`, `DetailScreen.kt`, `DictNl1.kt` to `DictNl5.kt`, `General.kt`, `HomeLayout.kt`, `HomeParts.kt`, `Live.kt`, `LiveTimeshift.kt`, `LoadingOverlay.kt`, `Mem.kt`, `ParentalLogic.kt`, `PlaybackLogic.kt`, `PlayerControls.kt`, `PlayerGuide.kt`, `PlayerLogic.kt`, `PlayerScreen.kt` (comments only), `RemoteLogic.kt`, `RemoteUi.kt`, `SettingsExtra.kt`, `SportAlerts.kt`, `SportTab.kt`, `Ui.kt`, `Updater.kt`, `ViewSettings.kt`, `Visuals.kt`, `res/drawable/ic_launcher_background.xml`, `res/drawable/ic_launcher_foreground.xml`. Documents: `CLAUDE.md` (sections 3, 13, 15), `docs/REFACTORING-PROGRESS.md`, this file.

## Test results

Not run by the assistant (no Gradle). The maintainer runs `gradlew.bat assembleDebug` and `gradlew.bat testDebugUnitTest`.

## Open problems

- `Backup.kt:17` still has a German comment; it is translated with T-006.
- `PlayerLogic.kt`: a KDoc block about `cleanEpg` sits above `xmltvTime` (misplaced, not moved in a comment-only batch). Fix in U-1.
- T-004: baseline values PENDING (Gradle totals, CI status).
- CHANGELOG content for 1.2 and 1.3: PENDING.
- T-002: device test on TV or Fire TV.

## Next step

U-1: move the Media3-dependent code out of `PlayerLogic.kt` (see `docs/REFACTORING-PROGRESS.md`). T-004 and T-006 stay open.

## 2026-11-14 – Fof TV 1.3.51 (search, stream accuracy, player sources)
- Done: stream identity check (`StremioLogic.matchCheck`, `Stremio.rank/bestVerified`), `SourceMemory`/`NowSources`, in-player Sources panel with revert, Wrong content action, auto/ask setting on the TV Stremio page, resume tied to item id, search exact-title-first + dedupe.
- Checks run: CI release build + unit tests (incl. `StreamMatchTest`, `SearchAllLogicTest`) passed; emulator journey test passed with no crash. Not run: real addon playback (no addons on the emulator).
- Open: on-device test of source switching and Wrong content; source picker sort/filter; Supabase sync waits for URL + anon key.

## 2026-11-14 – Reliability pass (1.3.54 – 1.3.61)
- Done: search freeze (lock on the main thread), provider live-only (parse, download and saved cache), Netflix-style search
  with sections and filters, sports (3 sports, clean overview, flat tiles, ESPN/NBA TV, usual US networks, East/West feeds),
  Live TV opens the TiviMate-style guide, multiview tile fallback, freeze watcher and exit reasons, Speed timings,
  App health on the phone page, realistic test TV with pass/fail checks (docs/TESTING.md).
- Checks: release build + unit tests pass; test TV run 43649087244: all 7 checks pass.
- Open: real-TV numbers after 1.3.61 (Speed / App health); which unused features to remove (list in the report);
  ESPN single live request for Sports; search typo tolerance for catalog titles; Back/focus rules of the search brief.

## 1.3.105 (released, latest)
- Done: search typo tolerance (SearchAllLogic.typo/dist), curated Home rows, Surprise me (SurpriseMe.kt), sports game quick menu with multiview + My Teams, Watch Together text chat.
- Tests: CI unit tests and release build passed (SearchAllLogicTest.typoTolerance, SurpriseLogicTest new). No device or emulator run for this batch.
- Open: device check of the sports menu, Surprise me and party chat on Fire TV; scroll smoothness (520/1088 slow frames on d18ee8f).

## Master prompt (16 Nov 2026) – status by phase
- P0 sync: done in code (pause, leave, seek, episode/source change, ~10 s ticks; conflict-safe PATCH on updated_at; realtime; offline kept locally). Verified on the server: only the onn box (1.3.85) had ever signed in before. Two-TV test reported working by the maintainer after 1.3.110.
- P0 accounts/profiles: welcome + sign-in (QR with progress/expiry, email on TV), Who's watching cleaned, local-only profiles explicit in Manage, kids flag synced, PINs bcrypt on the server, devices list + revoke + password re-check.
- P0 playback: network recovery (12 tries, ≤5 min), Retry / Change source on failure, IPTV sources next to addons, DragonStreams last resort.
- P1 navigation: focus restore on sports cards; quick menu everywhere (hold OK or Menu key). OPEN: full audit of Back targets and row focus jumps on device.
- P1 search: typo tolerance, filters, people. OPEN: genre/team/league results, suggestions, trending.
- P2 home: taste rows, curated rows, Show less / Not interested. OPEN: hidden gems, favorite-teams row on Home.
- P2 sports: quick menu, multiview from a game. OPEN: score overlay toggle, RedZone mode (needs event data).
- P3 Watch Together: rooms, codes, sync, reactions, text chat. OPEN: voice (not planned yet).
- Perf: emulator baseline 520/1088 slow frames (d18ee8f) → 202/537 on 1.3.107 run. OPEN: measure on a real Fire TV.
