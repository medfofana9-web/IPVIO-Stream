-- Fof TV: one account per household, profiles inside it (T-007).
-- Run once in the Supabase SQL editor after schema.sql. Safe to run again.
--
-- ACCOUNT  = the Supabase user (email + password). Owns profiles, addons, provider login, devices.
-- PROFILE  = fof_profiles row. Own PIN (hashed, checked here), own data (fof_profile_data).
-- SESSION  = one signed-in device (JWT claim session_id). Unlocks at most one profile at a time.
-- Every table has row level security. PIN hashes, unlocks, revoked sessions and encrypted secrets have no
-- client policies at all: they are only reached through the security-definer functions below.

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------- tables

create table if not exists public.fof_profiles (
  id uuid primary key default gen_random_uuid(),
  account_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  slot int not null check (slot between 0 and 99),
  name text not null default '' check (length(name) <= 40),
  avatar text not null default '' check (length(avatar) <= 600),
  photo text not null default '' check (length(photo) <= 200000),
  deleted boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (account_id, slot)
);

create table if not exists public.fof_profile_pins (
  profile_id uuid primary key references public.fof_profiles(id) on delete cascade,
  hash text not null,
  fails int not null default 0,
  locked_until timestamptz
);

create table if not exists public.fof_profile_data (
  profile_id uuid primary key references public.fof_profiles(id) on delete cascade,
  account_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  data jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

-- Which profile each signed-in device (session) has opened with its PIN
create table if not exists public.fof_profile_unlocks (
  session_id text primary key,
  account_id uuid not null references auth.users(id) on delete cascade,
  profile_id uuid not null references public.fof_profiles(id) on delete cascade,
  at timestamptz not null default now()
);

create table if not exists public.fof_account_config (
  account_id uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  settings jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

-- Addon links, debrid token, provider playlist: encrypted with a key kept in Supabase Vault
create table if not exists public.fof_account_secrets (
  account_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (name in ('addons', 'debrid', 'playlist')),
  enc bytea not null,
  updated_at timestamptz not null default now(),
  primary key (account_id, name)
);

create table if not exists public.fof_devices (
  id uuid primary key,
  account_id uuid not null references auth.users(id) on delete cascade,
  session_id text not null default '',
  name text not null default '' check (length(name) <= 40),
  kind text not null default '' check (length(kind) <= 60),
  app_version text not null default '' check (length(app_version) <= 30),
  last_seen timestamptz not null default now(),
  revoked boolean not null default false,
  created_at timestamptz not null default now()
);

create table if not exists public.fof_revoked_sessions (
  session_id text primary key,
  account_id uuid not null references auth.users(id) on delete cascade,
  at timestamptz not null default now()
);

alter table public.fof_profiles enable row level security;
alter table public.fof_profile_pins enable row level security;
alter table public.fof_profile_data enable row level security;
alter table public.fof_profile_unlocks enable row level security;
alter table public.fof_account_config enable row level security;
alter table public.fof_account_secrets enable row level security;
alter table public.fof_devices enable row level security;
alter table public.fof_revoked_sessions enable row level security;

drop trigger if exists fof_profiles_touch on public.fof_profiles;
create trigger fof_profiles_touch before insert or update on public.fof_profiles for each row execute function public.touch_updated_at();
drop trigger if exists fof_profile_data_touch on public.fof_profile_data;
create trigger fof_profile_data_touch before insert or update on public.fof_profile_data for each row execute function public.touch_updated_at();
drop trigger if exists fof_account_config_touch on public.fof_account_config;
create trigger fof_account_config_touch before insert or update on public.fof_account_config for each row execute function public.touch_updated_at();

-- ---------------------------------------------------------------- helpers (security definer, fixed search path)

create or replace function public.fof_session() returns text language sql stable as $$
  select coalesce(auth.jwt() ->> 'session_id', '')
$$;

-- False for a device the account signed out remotely: its requests reach nothing any more
create or replace function public.fof_ok() returns boolean language sql stable security definer set search_path = public as $$
  select auth.uid() is not null and not exists (select 1 from fof_revoked_sessions r where r.session_id = public.fof_session())
$$;

-- May this device read and write the profile's data? Yes when it opened the profile with the PIN,
-- or while the profile has no PIN yet (existing profiles until their PIN is set)
create or replace function public.fof_unlocked(p uuid) returns boolean language sql stable security definer set search_path = public as $$
  select public.fof_ok() and exists (select 1 from fof_profiles f where f.id = p and f.account_id = auth.uid()) and (
    not exists (select 1 from fof_profile_pins x where x.profile_id = p)
    or exists (select 1 from fof_profile_unlocks u where u.session_id = public.fof_session() and u.profile_id = p)
  )
$$;

-- The account owner's profile: the lowest slot
create or replace function public.fof_owner_profile() returns uuid language sql stable security definer set search_path = public as $$
  select id from fof_profiles where account_id = auth.uid() and not deleted order by slot limit 1
$$;

-- Signed in with the password in the last 10 minutes (refreshing a session keeps the old sign-in time)
create or replace function public.fof_recent_password() returns boolean language sql stable as $$
  select coalesce((select max((x ->> 'timestamp')::bigint) from jsonb_array_elements(coalesce(auth.jwt() -> 'amr', '[]'::jsonb)) x
    where x ->> 'method' = 'password'), 0) > extract(epoch from now()) - 600
$$;

create or replace function public.fof_can_manage(p uuid) returns boolean language sql stable security definer set search_path = public as $$
  select public.fof_unlocked(p) or public.fof_unlocked(public.fof_owner_profile()) or (public.fof_ok() and public.fof_recent_password())
$$;

-- ---------------------------------------------------------------- policies

drop policy if exists fp_read on public.fof_profiles;
create policy fp_read on public.fof_profiles for select using (account_id = auth.uid() and public.fof_ok());
drop policy if exists fp_insert on public.fof_profiles;
create policy fp_insert on public.fof_profiles for insert with check (account_id = auth.uid() and public.fof_ok());
drop policy if exists fp_update on public.fof_profiles;
create policy fp_update on public.fof_profiles for update using (account_id = auth.uid() and public.fof_can_manage(id))
  with check (account_id = auth.uid());

drop policy if exists fpd_rw on public.fof_profile_data;
create policy fpd_rw on public.fof_profile_data for all using (account_id = auth.uid() and public.fof_unlocked(profile_id))
  with check (account_id = auth.uid() and public.fof_unlocked(profile_id));

drop policy if exists fac_rw on public.fof_account_config;
create policy fac_rw on public.fof_account_config for all using (account_id = auth.uid() and public.fof_ok())
  with check (account_id = auth.uid() and public.fof_ok());

drop policy if exists fd_read on public.fof_devices;
create policy fd_read on public.fof_devices for select using (account_id = auth.uid() and public.fof_ok());

-- At most 10 profiles per account
create or replace function public.fof_profiles_limit() returns trigger language plpgsql security definer set search_path = public as $$
begin
  if (select count(*) from fof_profiles where account_id = new.account_id and not deleted) >= 10 then
    raise exception 'An account can have up to 10 profiles.';
  end if;
  return new;
end $$;
drop trigger if exists fof_profiles_limit on public.fof_profiles;
create trigger fof_profiles_limit before insert on public.fof_profiles for each row execute function public.fof_profiles_limit();

-- ---------------------------------------------------------------- PINs

create or replace function public.fof_pin_status() returns table (profile_id uuid, pin_set boolean, wait_s int, unlocked boolean)
language sql stable security definer set search_path = public as $$
  select f.id, x.profile_id is not null,
    greatest(0, ceil(extract(epoch from (coalesce(x.locked_until, now()) - now()))))::int,
    exists (select 1 from fof_profile_unlocks u where u.session_id = public.fof_session() and u.profile_id = f.id)
  from fof_profiles f left join fof_profile_pins x on x.profile_id = f.id
  where f.account_id = auth.uid() and not f.deleted and public.fof_ok()
$$;

create or replace function public.fof_unlock(p uuid) returns void language sql security definer set search_path = public as $$
  insert into fof_profile_unlocks (session_id, account_id, profile_id, at) values (public.fof_session(), auth.uid(), p, now())
  on conflict (session_id) do update set profile_id = excluded.profile_id, account_id = excluded.account_id, at = now()
$$;

-- Checks the PIN and opens the profile on this device. 5 wrong tries lock it for 1 minute, doubling up to 64 minutes.
create or replace function public.fof_verify_pin(p_profile uuid, p_pin text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare r fof_profile_pins%rowtype;
begin
  if not public.fof_ok() or public.fof_session() = '' then return jsonb_build_object('ok', false, 'err', 'signed_out'); end if;
  if not exists (select 1 from fof_profiles where id = p_profile and account_id = auth.uid() and not deleted) then
    return jsonb_build_object('ok', false, 'err', 'no_profile');
  end if;
  select * into r from fof_profile_pins where profile_id = p_profile for update;
  if not found then return jsonb_build_object('ok', false, 'err', 'no_pin'); end if;
  if r.locked_until is not null and r.locked_until > now() then
    return jsonb_build_object('ok', false, 'err', 'locked', 'wait_s', ceil(extract(epoch from (r.locked_until - now())))::int);
  end if;
  if r.hash = crypt(coalesce(p_pin, ''), r.hash) then
    update fof_profile_pins set fails = 0, locked_until = null where profile_id = p_profile;
    perform public.fof_unlock(p_profile);
    return jsonb_build_object('ok', true);
  end if;
  update fof_profile_pins set fails = r.fails + 1,
    locked_until = case when r.fails + 1 >= 5 then now() + make_interval(mins => power(2, least(r.fails + 1 - 5, 6))::int) else null end
    where profile_id = p_profile;
  return jsonb_build_object('ok', false, 'err', case when r.fails + 1 >= 5 then 'locked' else 'wrong' end,
    'left', greatest(0, 4 - r.fails), 'wait_s', case when r.fails + 1 >= 5 then 60 * power(2, least(r.fails + 1 - 5, 6))::int else 0 end);
end $$;

-- Sets a profile's PIN (4 to 6 digits). Allowed: first PIN of a profile; inside the profile; the owner's profile;
-- or right after signing in with the account password (forgotten PIN). The device then has the profile open.
create or replace function public.fof_set_pin(p_profile uuid, p_pin text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
begin
  if not public.fof_ok() or public.fof_session() = '' then return jsonb_build_object('ok', false, 'err', 'signed_out'); end if;
  if p_pin is null or p_pin !~ '^[0-9]{4,6}$' then return jsonb_build_object('ok', false, 'err', 'format'); end if;
  if not exists (select 1 from fof_profiles where id = p_profile and account_id = auth.uid() and not deleted) then
    return jsonb_build_object('ok', false, 'err', 'no_profile');
  end if;
  if not public.fof_can_manage(p_profile) then return jsonb_build_object('ok', false, 'err', 'denied'); end if;
  insert into fof_profile_pins (profile_id, hash, fails, locked_until) values (p_profile, crypt(p_pin, gen_salt('bf', 8)), 0, null)
  on conflict (profile_id) do update set hash = excluded.hash, fails = 0, locked_until = null;
  perform public.fof_unlock(p_profile);
  return jsonb_build_object('ok', true);
end $$;

-- ---------------------------------------------------------------- account secrets (Vault key)

do $$ begin
  if not exists (select 1 from vault.secrets where name = 'fof_account_key') then
    perform vault.create_secret(encode(extensions.gen_random_bytes(32), 'hex'), 'fof_account_key', 'Fof TV account secrets');
  end if;
end $$;

create or replace function public.fof_key() returns text language sql stable security definer set search_path = public as $$
  select decrypted_secret from vault.decrypted_secrets where name = 'fof_account_key' limit 1
$$;
revoke all on function public.fof_key() from public, anon, authenticated;

create or replace function public.fof_secrets_get() returns table (name text, value text, updated_at timestamptz)
language sql stable security definer set search_path = public, extensions as $$
  select s.name, pgp_sym_decrypt(s.enc, public.fof_key()), s.updated_at
  from fof_account_secrets s where s.account_id = auth.uid() and public.fof_ok()
$$;

create or replace function public.fof_secret_put(p_name text, p_value text) returns timestamptz
language plpgsql security definer set search_path = public, extensions as $$
declare t timestamptz := clock_timestamp();
begin
  if not public.fof_ok() then raise exception 'signed out'; end if;
  if length(coalesce(p_value, '')) > 100000 then raise exception 'too long'; end if;
  insert into fof_account_secrets (account_id, name, enc, updated_at) values (auth.uid(), p_name, pgp_sym_encrypt(coalesce(p_value, ''), public.fof_key()), t)
  on conflict (account_id, name) do update set enc = excluded.enc, updated_at = t;
  return t;
end $$;

-- ---------------------------------------------------------------- devices

create or replace function public.fof_register_device(p_id uuid, p_name text, p_kind text, p_version text) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return jsonb_build_object('ok', false); end if;
  if not public.fof_ok() then return jsonb_build_object('ok', false, 'revoked', true); end if;
  insert into fof_devices (id, account_id, session_id, name, kind, app_version, last_seen, revoked)
  values (p_id, auth.uid(), public.fof_session(), left(coalesce(p_name, ''), 40), left(coalesce(p_kind, ''), 60), left(coalesce(p_version, ''), 30), now(), false)
  on conflict (id) do update set session_id = excluded.session_id, kind = excluded.kind, app_version = excluded.app_version,
    last_seen = now(), revoked = false, account_id = excluded.account_id,
    name = case when fof_devices.account_id = excluded.account_id and fof_devices.name <> '' then fof_devices.name else excluded.name end;
  return jsonb_build_object('ok', true);
end $$;

-- Callable by a signed-out device too: tells it to sign itself out
create or replace function public.fof_session_ok() returns boolean language sql stable security definer set search_path = public as $$
  select public.fof_ok()
$$;

create or replace function public.fof_rename_device(p_id uuid, p_name text) returns void language sql security definer set search_path = public as $$
  update fof_devices set name = left(trim(coalesce(p_name, '')), 40) where id = p_id and account_id = auth.uid() and public.fof_ok()
$$;

create or replace function public.fof_revoke_device(p_id uuid) returns void language plpgsql security definer set search_path = public as $$
declare s text;
begin
  if not public.fof_ok() then return; end if;
  select session_id into s from fof_devices where id = p_id and account_id = auth.uid();
  if s is null then return; end if;
  if s <> '' then insert into fof_revoked_sessions (session_id, account_id) values (s, auth.uid()) on conflict do nothing; end if;
  delete from fof_profile_unlocks where session_id = s;
  update fof_devices set revoked = true where id = p_id and account_id = auth.uid();
end $$;

-- After "sign out all other devices" (auth logout scope=others): mark them in the list
create or replace function public.fof_revoke_others() returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.fof_ok() then return; end if;
  insert into fof_revoked_sessions (session_id, account_id)
    select session_id, auth.uid() from fof_devices where account_id = auth.uid() and session_id <> public.fof_session() and session_id <> ''
    on conflict do nothing;
  delete from fof_profile_unlocks where account_id = auth.uid() and session_id <> public.fof_session();
  update fof_devices set revoked = true where account_id = auth.uid() and session_id <> public.fof_session();
end $$;

-- ---------------------------------------------------------------- grants

revoke all on public.fof_profile_pins, public.fof_profile_unlocks, public.fof_account_secrets, public.fof_revoked_sessions from anon, authenticated;
revoke all on public.fof_profiles, public.fof_profile_data, public.fof_account_config, public.fof_devices from anon;
grant select, insert, update on public.fof_profiles to authenticated;
grant select, insert, update on public.fof_profile_data to authenticated;
grant select, insert, update on public.fof_account_config to authenticated;
grant select on public.fof_devices to authenticated;
revoke all on function public.fof_verify_pin(uuid, text), public.fof_set_pin(uuid, text), public.fof_pin_status(),
  public.fof_secrets_get(), public.fof_secret_put(text, text), public.fof_register_device(uuid, text, text, text),
  public.fof_session_ok(), public.fof_rename_device(uuid, text), public.fof_revoke_device(uuid), public.fof_revoke_others(),
  public.fof_unlock(uuid) from public, anon;
grant execute on function public.fof_verify_pin(uuid, text), public.fof_set_pin(uuid, text), public.fof_pin_status(),
  public.fof_secrets_get(), public.fof_secret_put(text, text), public.fof_register_device(uuid, text, text, text),
  public.fof_session_ok(), public.fof_rename_device(uuid, text), public.fof_revoke_device(uuid), public.fof_revoke_others()
  to authenticated;
revoke all on function public.fof_unlock(uuid) from authenticated;

-- ---------------------------------------------------------------- realtime (LiveSync, 1.3.92)
-- Changes of fof_profile_data reach other TVs of the same profile instantly (row level security applies to the feed).
do $$ begin
  if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'fof_profile_data') then
    alter publication supabase_realtime add table public.fof_profile_data;
  end if;
end $$;
