-- Fof TV "Play on TV": the iPhone / Mac app sends a title, channel or remote button to one of the account's TVs.
-- Run once in the Supabase SQL editor after accounts.sql. Safe to run again.
-- The table has row level security and no client policies: it is only reached through the two functions below.

create table if not exists public.fof_cast (
  id bigint generated always as identity primary key,
  account_id uuid not null references auth.users(id) on delete cascade,
  to_device uuid not null,
  cmd jsonb not null check (octet_length(cmd::text) <= 4000),
  created_at timestamptz not null default now(),
  taken boolean not null default false
);
create index if not exists fof_cast_to on public.fof_cast (to_device, taken, created_at);
alter table public.fof_cast enable row level security;

-- Phone / Mac: send a command to a TV of this account (old commands of the account are cleared on the way)
create or replace function public.fof_cast_send(p_to uuid, p_cmd jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or not public.fof_ok() then return jsonb_build_object('err', 'Signed out'); end if;
  if not exists (select 1 from public.fof_devices d where d.id = p_to and d.account_id = auth.uid() and not d.revoked) then
    return jsonb_build_object('err', 'That TV is not signed in to this account');
  end if;
  delete from public.fof_cast where account_id = auth.uid() and (taken or created_at < now() - interval '10 minutes');
  insert into public.fof_cast (account_id, to_device, cmd) values (auth.uid(), p_to, p_cmd);
  return jsonb_build_object('ok', true);
end $$;

-- TV: take the commands for this device that are not older than a minute (each one is handed out once)
create or replace function public.fof_cast_take(p_device uuid) returns setof jsonb
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or not public.fof_ok() then return; end if;
  return query
    with t as (
      update public.fof_cast c set taken = true
      where c.account_id = auth.uid() and c.to_device = p_device and not c.taken and c.created_at > now() - interval '60 seconds'
      returning c.id, c.cmd
    ) select t.cmd from t order by t.id;
end $$;

revoke all on function public.fof_cast_send(uuid, jsonb) from public, anon;
revoke all on function public.fof_cast_take(uuid) from public, anon;
grant execute on function public.fof_cast_send(uuid, jsonb) to authenticated;
grant execute on function public.fof_cast_take(uuid) to authenticated;
