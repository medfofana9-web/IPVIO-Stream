# Release readiness (16 Nov 2026, v1.3.115)

## Verification
| Area | Result | Evidence |
|---|---|---|
| Account isolation, PIN hashing (bcrypt), PIN lockout, device revoke, 10-profile limit, secrets unreadable | PASS | docs/supabase/accounts_test.sql, 26/26 as expected, run 16 Nov 2026 |
| Watch Together tables and RPCs | PASS | party SQL checks 10/10, 15 Nov 2026 |
| Unit tests (sync merge, resume match, recovery backoff, search typos/genres, IPTV title match, Surprise, sign-in rules, party logic) | PASS | GitHub Actions release build |
| Start, playlist load, every main screen, no crash, no frozen screen, search, Home after restart | PASS | Emulator journey (perf-test.yml), 1.3.107+ |
| Cross-TV Continue Watching | PASS (maintainer, two real TVs, 1.3.110) | Reported by the maintainer |
| Remote focus/Back on real TVs, long sessions, multiview decoder limits, network drop on device, Fire TV speed | NOT TESTED | Needs physical devices |
| Password reset by email | BLOCKED | Needs a public reset page (Supabase redirect URL); not built |

## Backup and recovery
- All account data lives in Supabase project msooylkizqvdrgxrdjww (tables fof_*). Profile data is one JSON row per profile (fof_profile_data), so a row export restores a profile completely.
- Supabase Free plan: no point-in-time recovery. Practical backup: weekly `pg_dump` of the public schema (or Table Editor → Export CSV of fof_* tables) kept off the project. Each TV also keeps a full local copy of its profile and pushes it back after a server restore (sync merges, newest progress wins).
- Account deletion only via fof_delete_account after a fresh password check.

## Content rights (before sharing beyond the family)
- Fof TV ships no content. Streams come from the user's own IPTV subscription and Stremio addons the user adds; an addon being available does not mean it has rights to the streams. Each household must use sources it is entitled to.
- Artwork and metadata: Cinemeta, TMDB (needs attribution and a TMDB API key per their terms), ESPN public scoreboard endpoints (unofficial; may change or be restricted).
- No DRM handling or bypass is in the app. Watch Together only syncs position; each TV plays from its own source.
- Character avatars are not included (would need licenses).
