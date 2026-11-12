# Work state

Last update: 2026-11-11. Read `CLAUDE.md` first, then this file.

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
