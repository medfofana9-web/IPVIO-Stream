# T-007 – One account per household, profiles with PINs, account addons, devices

Status: implemented in 1.3.84 (prerelease first). Approved by the maintainer on 15 Nov 2026 (one account for the
household; existing personal sign-ins move into it).

## Goal
Netflix-style account: one email and password per household, signed in once per TV; up to 10 profiles with their own
server-checked PIN and their own data; Stremio addons, debrid token and provider playlist belong to the account;
Connected Devices with remote sign-out.

## Findings (before)
- Each profile on each TV signed in on its own (`fof_account` per profile) – the cause of repeated QR sign-ins.
- Profiles existed only on the TV (`ipvio_profiles`); the cloud held one person's data per login (`user_data`).
- Addons, debrid token and playlist were device-wide local stores; only the owner's "Share" sent them to a family.
- The only PIN was the local parental PIN.
- Continue Watching already merged per title by time stamp (`SyncLogic.mergeResume`).

## Changes
- Backend `docs/supabase/accounts.sql` (applied 15 Nov 2026): fof_profiles, fof_profile_pins (bcrypt), fof_profile_data,
  fof_profile_unlocks (one open profile per session), fof_account_config, fof_account_secrets (pgcrypto with a Vault key),
  fof_devices, fof_revoked_sessions; RLS on all; PIN, secrets and device changes only through security-definer functions.
- `FofAccount`: device-wide sign-in (`fof_device_account`), taken once from the per-profile stores (owner first);
  serialized token refresh; a refused refresh signs the TV out; `confirmPassword` for a forgotten PIN.
- `AccountCloud` (new): session check, device registration, profile lining-up (`AccountCloudLogic.plan`), names and
  pictures both ways, PIN state, verify/set PIN, account addons/debrid/playlist, devices, merge of earlier personal
  `user_data` into the local profile once.
- `CloudSync`: per profile through fof_profile_data; skips a profile this TV has not opened.
- UI: `ProfileGate` (create PIN, enter PIN, forgot PIN), PinPad 4–6 digits, "Sign in" and "Change PIN" on Who's
  watching, Settings > Account & devices (`AccountPage`).

## Data protection
Nothing is deleted: per-profile sign-ins stay in their stores (read once), `user_data` and the household tables stay.
Local profiles that match no account profile are added to the account.

## Tests
- Server: `docs/supabase/accounts_test.sql` (rolled back), 26 checks, all as expected on 15 Nov 2026.
- JVM: `AccountCloudLogicTest` (profile lining-up, sync sides, PIN rules, addons, two simulated TVs: resume at 47 min,
  stale TV after offline, separate profiles). Run by CI.
- Not possible here: two real TVs. Maintainer steps are in the release notes of the task report.

## Open
- Single-device sign-out ends the device's access to every Fof table at once (RLS), but its auth session only ends
  with "Sign out all other devices" (the public key cannot end one session).
- Phones and browsers: no Fof TV app there yet; the backend is ready.
- Provider (Xtream) connection limits still apply per login.
