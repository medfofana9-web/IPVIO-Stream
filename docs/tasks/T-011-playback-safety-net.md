# T-011 Playback safety net

Status: implemented; emulator run pending at time of writing; device check pending.

## Goal
Catch playback problems of movies and shows before a viewer meets them (asked by the maintainer after Inside Man failed
to resume twice, 26 Nov 2026).

## Changes
| Part | Files |
|------|-------|
| Emulator scenarios (resume at 1:00 against a server that cannot seek, an expired link, a hanging server, a late addon, a remembered source failing before the other addon answered) | `.github/workflows/playback-test.yml`, `.github/scripts/playback_fixture.py`, `playback_steps.py`, `playback_check.py`, test-build hooks in `MainSampler.kt` (`addon`, `good`, `resume`), `FofPlay` log lines in `PlayerScreen.kt` (test builds only) |
| Rules as tested logic | `StartWatchLogic.kt` (+ test), used by the start watchdog and `stremioFallback` in `PlayerScreen.kt` |
| Source health on the TV | `SourceHealth.kt` (+ test), `Stremio.rank`, one-click `known` in `StremioUi.kt`, marks in `PlayerScreen.kt` |
| Background checks on the TV | `SourceCheck.kt` (+ test), loop in `MainActivity.kt`, `StreamHeaders.headersFor` |
| Release gate | `promote.yml` (`actions: read`, `issues: write`, Supabase key for reports), `.github/scripts/promote_gate.py` |

New prefs: `fof_source_health`, `fof_sourcecheck`. Existing keys unchanged.

## Risks
- Background checks use some bandwidth (≤ 3 links × 64 KB per title, 3 titles every 15 min; addons once a day).
- The gate can hold builds on the early channel; the owner can still promote by editing the release.
- Emulator scenarios are movies only and use fixture addons, not the real ones.
