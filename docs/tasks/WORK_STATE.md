# Work state

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
