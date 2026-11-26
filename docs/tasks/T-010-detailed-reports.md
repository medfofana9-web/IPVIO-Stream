# T-010 More detailed automatic problem reports

Status: implemented, CI pending at time of writing; device check pending.

## Goal
Every auto-report says what the TV was doing and, where possible, the exact source line, so the daily fix run can act
without guessing. Asked for by the maintainer on 26 Nov 2026 ("do them all", seven points).

## What changed
| # | Change | Files |
|---|--------|-------|
| 1 | Raw frames (`class.method:line`) sent with freezes and crashes; `tools/auto_reports.py` retraces them with the release's R8 mapping (artifact `mapping-<run>`) and R8 retrace, groups issues without line numbers, keeps a `Versions seen:` line per issue | `FreezeWatch.kt`, `Mem.kt` (CrashLog), `tools/auto_reports.py`, `.github/workflows/auto-reports.yml` (`actions: read`, `dry_run`, `retrace_test` inputs) |
| 2 | Freeze watch samples every 500 ms until the main thread runs again (max 12 s): total length, how often each location was seen, most common location as the name; idle-only samples (`nativePollOnce`) are no freeze | `FreezeWatch.kt` |
| 3 | Frame summaries: frames drawn with no key for 3 s (`idle`), place of slow frames (`detail`, `settings`, `search`), Lite/Motion/clean look, display mode and UI size, memory. New `idle-draw:` report: a still screen drawing ≥ 15 fps for 10 s is sampled for 5 s once per screen | `FrameStats.kt`, `IdleDraw.kt` (new), `ReportCtx.kt` (new), `DetailScreen.kt`, `SettingsHub.kt`, `SearchScreen.kt` |
| 4 | Playback error: error code, HTTP status, stream kind from the address shape (`live xtream ts`), format, whether it had played, failovers | `PlayerScreen.kt` (listener only), `StartTrace.kt` |
| 5 | Low-memory / ANR close: rss, importance and the 30-second snapshot taken before (screen, heap, native heap, picture cache, uptime, playlist size, last steps) | `Mem.kt`, `ReportCtx.kt` |
| 6 | Last 12 steps (screen changes, places, OK, first picture, player error) in crash, freeze, start, stream and error reports | `ReportCtx.kt`, `StartTrace.kt` |
| 7 | Fix tracking: `Versions seen:` per issue (see 1) | `tools/auto_reports.py` |

## Format
`<name> ‖ <details>`. The table `fof_reports.kind` is limited to 400 characters, so longer reports are a head row
`<name> ‖#<id>` plus up to four rows `+#<id> <n> <text>` (`ReportLogic.split`), joined again by the script. No schema change.
The once-a-day limit on the TV now uses the name only. New prefs: `fof_reportctx` (snapshot), key `details` in
`ipvio_crash`; existing keys unchanged.

## Privacy
Only screen names, step names, numbers, class/method/line and settings switches. Addresses are never sent (only their
shape, e.g. `xtream ts`); links are replaced by `<link>` before sending. Stream failure reports keep the title as before.

## Risks
- Issue titles change (no line numbers, start reports grouped by slowest step): existing open issues are not continued; new ones open.
- Up to 5 rows per report; daily cap of 30 reports per TV unchanged.
- R8 retrace downloads R8 from Google Maven in the workflow; without it the raw frames stay in the issue.

## Test results
- `ReportLogicTest`, `FreezeLogicTest` (new cases): CI.
- Retrace path: `auto-reports.yml` with `retrace_test` on the branch.
- Not run: device. Maintainer: on the onn and Fire TV, leave Home still for a minute, open a title page, play a channel;
  then check the next `perf:`/`idle-draw:` issues for `idle`, `where`, display and memory parts.
