-- Watching now (1.3.26x): what each profile is playing right now, so the others on the account (or in the
-- household) see it on Home and can join. One row per profile, refreshed about every 25 s while playing;
-- a row older than ~75 s counts as "not watching". Holds only title and position, never stream addresses.
-- Safe to run again.
create table if not exists public.fof_presence (
  profile_id uuid primary key references public.fof_profiles(id) on delete cascade,
  account_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null default '' check (length(name) <= 40),
  avatar text not null default '' check (length(avatar) <= 600),
  item_id text not null default '' check (length(item_id) <= 300),
  title text not null default '' check (length(title) <= 200),
  season int not null default 0,
  ep int not null default 0,
  pos_ms bigint not null default 0,
  dur_ms bigint not null default 0,
  img text not null default '' check (length(img) <= 600),
  device text not null default '' check (length(device) <= 60),
  updated_at timestamptz not null default now()
);
alter table public.fof_presence enable row level security;

-- Read: own account, or an account in the same household
drop policy if exists fpr_read on public.fof_presence;
create policy fpr_read on public.fof_presence for select using (
  public.fof_ok() and (account_id = auth.uid() or exists (
    select 1 from public.members a join public.members b on a.household_id = b.household_id
    where a.user_id = auth.uid() and b.user_id = fof_presence.account_id)));
-- Write: only your own account, only for a profile this device may use (PIN-locked profiles need their PIN)
drop policy if exists fpr_insert on public.fof_presence;
create policy fpr_insert on public.fof_presence for insert with check (account_id = auth.uid() and public.fof_ok() and public.fof_unlocked(profile_id));
drop policy if exists fpr_update on public.fof_presence;
create policy fpr_update on public.fof_presence for update using (account_id = auth.uid() and public.fof_ok() and public.fof_unlocked(profile_id))
  with check (account_id = auth.uid());
drop policy if exists fpr_delete on public.fof_presence;
create policy fpr_delete on public.fof_presence for delete using (account_id = auth.uid() and public.fof_ok());
