# Testing

Source: refactoring audit of 2026-11-11, section 7, the T-002 note and [quality/baseline-2026-11-11.md](quality/baseline-2026-11-11.md). Unit tests are in `app/src/test/java/com/ipvio/stream/`.

## Commands

Run in Android Studio, terminal in the project folder (Windows):

| Command | Purpose |
|---|---|
| `gradlew.bat testDebugUnitTest` | all unit tests |
| `gradlew.bat lintDebug` | Android Lint; report `app/build/reports/lint-results-debug.html` |

CI (`.github/workflows/build.yml`) runs unit tests and a debug build on every push. Status of the runs: PENDING (check the Actions tab on GitHub; the audit expected it to be red since 1.1 because of the `PhoneImportTest` expectation, which T-001 fixed, and the `org.json` stub, which T-001b fixed).

## Known state

- The audit counted 231 `@Test` functions in 31 test files.
- `PhoneImportTest`: 10 of 10 passed in the maintainer's run on 2026-11-11 (T-001 done).
- `Round25Test.parsesTraktJsonTolerantly` (`Round25Test.kt:70`) was red (expected `[Heat, Lost]`, was `[]`) because `org.json` is only a stub in local unit tests. T-001b added `testImplementation("org.json:json:20250517")` (commit `75e1892`). After that the maintainer reported a successful test run on 2026-11-11.
  - `org.json:json` is used for unit tests only; it is not part of the APK. Its "JSON License" is not OSI-approved (see [SECURITY.md](SECURITY.md), item 14).
- Exact Gradle totals ("X tests completed, Y failed") after T-001b: PENDING.
- Lint: 9 errors, 203 warnings, 10 hints (baseline 2026-11-11). Details in the baseline file.

## Tests that cannot run as plain JVM tests

`PlayerLogic.kt` imports Media3. Therefore `PlayerLogicTest`, `PlayerBufferTest` and `CoreLogicTest` depend on Media3 classes. The audit could not run them outside Gradle. Detaching `PlayerLogic` from Media3 is a prerequisite for splitting `PlayerScreen.kt` (P5). All other `*Logic.kt` files have no Android imports.

## Only meaningful on a device

Unit tests cannot cover these. Each needs a test step for the maintainer after a change:

| Area | Why | Source |
|---|---|---|
| Phone import, transfer and phone search over real Wi-Fi | HTTP server on the TV, access code, QR code. The emulator test used `adb forward` and a PC browser (T-002) | `PhoneImport.kt`, `RemoteHub.kt` |
| Software audio (headphones, Bluetooth) | FFmpeg decoder is loaded by reflection; Media3 1.11.1 with decoder 1.9.0+1 is untested | `SoftAudio.kt:24`, `app/build.gradle.kts:96-102` |
| Enabling R8 | the earlier R8 build did not start | [ADR 0001](adr/0001-no-r8-for-now.md) |
| Playback of HLS, DASH and provider streams | needs real providers | `PlayerScreen.kt` |
| Live rewind, recording, second stream, multi view | player and storage behaviour | `LiveTimeshift.kt`, `Recorder.kt`, `DualView.kt`, `MultiView.kt` |
| Remote-control focus and navigation | TV focus handling | `Nav.kt` |
| Alarms and reminders after reboot | `BootReceiver`, `ReminderReceiver` | `AndroidManifest.xml:39-71` |
| App update install | signature check and installer | `AppUpdate.kt` |
| Devices without Android Keystore | plaintext fallback in `Secrets` | `Secrets.kt:14-15` |
| TLS fallback with real providers | hostname mismatch case | `TlsFallback.kt:47-65` |
| Low-memory devices (1 GB) | memory use, `largeHeap` | `Mem.kt` |

## Rules

- Never report a test as passed unless it was run (CLAUDE.md section 3).
- Test names and some test classes are still German or named `Round17Test` to `Round25Test`; renaming is planned in P4.

## Test TV and release gate (Fof TV, since 1.3.61)

Workflow `.github/workflows/perf-test.yml` (push to branch `perf-test`) runs the app on an Android TV emulator shaped like
the maintainer's setup: 23,773 channels with provider-style names ("US: TNT East", groups "USA | Sports"), 20,000 episode
and 100,000 movie lines that the app must skip, a 24,000-channel guide, 2 GB of memory and a 256 MB app heap.

The run fails unless every check passes (lines `CHECK PASS` / `CHECK FAIL` in the Summary step):

- provider movies/series skipped (search index under 30,000 titles)
- no frozen screen (no `FofFreeze` log from the freeze watcher)
- app stays open through fast search typing and Live TV
- no crash
- Home shows posters within 15 s of a restart
- the Live TV guide shows the categories
- search finds a person

Process: push to `perf-test` first; only a passing run goes to `main` (which releases).

Not covered by the test TV (needs the real TV): playing real streams, the provider's real channel names for game
matching, live ESPN games at the moment of the run.

## Playback safety net (T-011, since 1.3.282)

Four layers, so a source problem is caught before a viewer meets it:

1. **Playback scenarios on an emulator** – `.github/workflows/playback-test.yml` (every push to `main` and `auto-fix`).
   `.github/scripts/playback_fixture.py` serves two fixture addons ("Dragon Fixture", "HD Fixture") and media that
   misbehaves like real sources: no range requests and slow (DragonStreams on Resume), HTTP 403 (expired link), headers
   then nothing (hanging), an addon that answers after 9 s, a remembered source that fails before the other addon
   answered. Each scenario resumes a movie at 1:00 through the Continue watching path (test-build broadcast
   `com.fof.tv.TAB --es resume`); `playback_check.py` fails the run unless every one shows a picture near 1:00.
2. **Rules as tested logic** – `StartWatchLogic` (when to give up on a source, what comes next) with
   `StartWatchLogicTest`; `SourceHealthLogic`, `SourceCheckLogic` with their tests.
3. **On the TV** – `SourceHealth` puts sources that did not start (or cannot seek, when resuming) after the others for
   6 h; `SourceCheck` checks Continue watching sources in the background every 15 minutes and every addon once a day
   (reported as `addon: …`).
4. **Release gate** – `promote.yml` moves a build from the early channel to every TV only when its commit passed the
   playback scenarios and the early-channel TVs reported no crash, freeze, forced close or stream that did not start
   (`.github/scripts/promote_gate.py`); otherwise it opens "[auto] promotion held".

Rule for changes to the player, Resume, sources or addons: add or extend a scenario in `playback_fixture.py` or a case in
`StartWatchLogicTest` for the behaviour changed, and do not push to `main` before `playback-test.yml` passed on
`auto-fix`.

Not covered: real addons' content, debrid torrents (never opened by the checks), live TV channels, series episodes in
the emulator scenarios (movies only), real TV hardware and Wi-Fi.

## What to try on the TV after an update

1. Start the app: posters on Home quickly; Settings → Diagnostics → Speed or the phone page → App health.
2. Search: type fast, delete fast, switch All / Movies / TV Shows / Live TV, open a result, Back.
3. Sports: live games at the top, open a live game, it plays; a game shows its channels (e.g. TNT / TBS).
4. Live TV: guide over the playing channel; pick another channel; Back twice.
5. Multi view with 4 games: a tile that fails switches to another channel of the same game.
