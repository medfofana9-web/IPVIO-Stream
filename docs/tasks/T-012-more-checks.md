# T-012 More checks before an update reaches every TV

Status: implemented; soak and performance runs on main pending at time of writing; device check pending.
Asked by the maintainer on 26 Nov 2026 ("do it all") after T-011.

| # | Part | Files |
|---|------|-------|
| 1 | Episodes and live TV in the playback scenarios | `playback_fixture.py`, `playback_steps.py`, `playback_check.py`, `MainSampler.kt` hooks (`resume` with season/episode, `live`), `FofPlay tick` log (test builds) in `PlayerScreen.kt` |
| 2 | Soak test (1 h, memory per round); background low-memory closes are a `memory:` report, not a crash | `soak-test.yml`, `soak_steps.sh`, `soak_check.py`, `Mem.kt` (`lastExit`) |
| 3 | Rollback | `rollback.yml` |
| 4 | Performance gate | `perf-test.yml` (main/auto-fix, `perf_compare.py`), `promote_gate.py` |
| 5 | Compose state check | `tools/check_compose_state.py`, `tools/compose_state_baseline.txt` (76 existing objects), `StateWarmUp.kt`, `pr-quality.yml`; `FofState` log in test builds |
| 6 | Alerts | `tools/auto_reports.py` (`urgent`, owner mention) |

Repo hygiene: `__pycache__/` ignored and removed from the index.

Risks: the gate holds builds when a test is flaky (re-run the workflow or promote by hand); about 2 hours of emulator
time per app change (public repository, no cost); the owner gets a GitHub notification for every urgent report batch.
