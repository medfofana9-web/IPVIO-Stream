-- Watch Together (1.3.102). Applied in the Supabase SQL editor on 15 Nov 2026. Safe to run again.
create table if not exists public.fof_parties (
  code text primary key check (code ~ '^[0-9]{6}$'),
  host uuid not null default auth.uid() references auth.users(id) on delete cascade,
  host_device text not null default '',
  host_name text not null default '' check (length(host_name) <= 40),
  item_id text not null default '' check (length(item_id) <= 200),
  title text not null default '' check (length(title) <= 200),
  kind text not null default '' check (length(kind) <= 10),
  season int not null default 0, ep int not null default 0,
  live boolean not null default false,
  playing boolean not null default false,
  pos_ms bigint not null default 0,
  state_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  ended boolean not null default false
);
create table if not exists public.fof_party_members (
  code text not null references public.fof_parties(code) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  device text not null default '',
  name text not null default '' check (length(name) <= 40),
  joined_at timestamptz not null default now(),
  left_at timestamptz,
  primary key (code, user_id, device)
);
create table if not exists public.fof_party_msgs (
  id bigint generated always as identity primary key,
  code text not null references public.fof_parties(code) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null default '' check (length(name) <= 40),
  body text not null check (length(body) between 1 and 200),
  at timestamptz not null default now()
);
alter table public.fof_parties enable row level security;
alter table public.fof_party_members enable row level security;
alter table public.fof_party_msgs enable row level security;

-- Member of the party (or its host), on a session that was not signed out remotely
create or replace function public.fof_in_party(p text) returns boolean language sql stable security definer set search_path = public as $$
  select public.fof_ok() and (exists (select 1 from fof_parties where code = p and host = auth.uid())
    or exists (select 1 from fof_party_members where code = p and user_id = auth.uid() and left_at is null))
$$;

drop policy if exists fpa_read on public.fof_parties;
create policy fpa_read on public.fof_parties for select using (public.fof_in_party(code));
drop policy if exists fpa_insert on public.fof_parties;
create policy fpa_insert on public.fof_parties for insert with check (host = auth.uid() and public.fof_ok());
drop policy if exists fpa_update on public.fof_parties;
create policy fpa_update on public.fof_parties for update using (host = auth.uid() and public.fof_ok()) with check (host = auth.uid());
drop policy if exists fpm_read on public.fof_party_members;
create policy fpm_read on public.fof_party_members for select using (public.fof_in_party(code));
drop policy if exists fpm_leave on public.fof_party_members;
create policy fpm_leave on public.fof_party_members for update using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists fmsg_read on public.fof_party_msgs;
create policy fmsg_read on public.fof_party_msgs for select using (public.fof_in_party(code));
drop policy if exists fmsg_send on public.fof_party_msgs;
create policy fmsg_send on public.fof_party_msgs for insert with check (user_id = auth.uid() and public.fof_in_party(code));

-- Joining needs the code (the invite); parties last 24 hours
create or replace function public.fof_join_party(p_code text, p_device text, p_name text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare r fof_parties%rowtype;
begin
  if not public.fof_ok() then return jsonb_build_object('ok', false, 'err', 'signed_out'); end if;
  select * into r from fof_parties where code = p_code and not ended and created_at > now() - interval '24 hours';
  if not found then return jsonb_build_object('ok', false, 'err', 'no_party'); end if;
  insert into fof_party_members (code, user_id, device, name) values (p_code, auth.uid(), left(coalesce(p_device, ''), 60), left(coalesce(p_name, ''), 40))
  on conflict (code, user_id, device) do update set left_at = null, name = excluded.name, joined_at = now();
  return jsonb_build_object('ok', true);
end $$;
revoke all on function public.fof_join_party(text, text, text) from public, anon;
grant execute on function public.fof_join_party(text, text, text) to authenticated;

revoke all on public.fof_parties, public.fof_party_members, public.fof_party_msgs from anon;
grant select, insert, update on public.fof_parties to authenticated;
grant select, update on public.fof_party_members to authenticated;
grant select, insert on public.fof_party_msgs to authenticated;

do $$ begin
  if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and tablename = 'fof_parties') then
    alter publication supabase_realtime add table public.fof_parties; end if;
  if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and tablename = 'fof_party_msgs') then
    alter publication supabase_realtime add table public.fof_party_msgs; end if;
  if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and tablename = 'fof_party_members') then
    alter publication supabase_realtime add table public.fof_party_members; end if;
end $$;
