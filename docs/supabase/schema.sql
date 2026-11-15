-- Fof TV household accounts (Supabase project "Fof TV").
-- One owner account holds the shared setup (provider playlist, addons, stream limit); family members join with an
-- invite code and get their own sign-in, name and avatar. Every table is protected by row level security:
-- the public (anon) key in the app can only read and change what the signed-in person is allowed to.

create table if not exists public.households (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null references auth.users(id) on delete cascade,
  name text not null default 'My family',
  max_streams int not null default 4,
  created_at timestamptz not null default now()
);

create table if not exists public.members (
  household_id uuid not null references public.households(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'member')),
  display_name text not null default '',
  avatar text not null default '',
  created_at timestamptz not null default now(),
  primary key (household_id, user_id)
);
create unique index if not exists members_one_household on public.members(user_id);

create table if not exists public.invites (
  code text primary key,
  household_id uuid not null references public.households(id) on delete cascade,
  uses_left int not null default 1,
  expires_at timestamptz not null default now() + interval '14 days',
  created_at timestamptz not null default now()
);

create table if not exists public.household_config (
  household_id uuid primary key references public.households(id) on delete cascade,
  playlist_url text not null default '',
  addons jsonb not null default '[]'::jsonb,
  updated_at timestamptz not null default now()
);

-- Each person's own settings (profiles, My list, Continue watching, home rows): only they can read or write it
create table if not exists public.user_data (
  user_id uuid primary key references auth.users(id) on delete cascade,
  data jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

alter table public.households enable row level security;
alter table public.members enable row level security;
alter table public.invites enable row level security;
alter table public.household_config enable row level security;
alter table public.user_data enable row level security;

-- Helpers (security definer: they read members without running into the members policies themselves)
create or replace function public.my_household() returns uuid
language sql stable security definer set search_path = public as
$$ select household_id from public.members where user_id = auth.uid() limit 1 $$;

create or replace function public.i_am_owner(h uuid) returns boolean
language sql stable security definer set search_path = public as
$$ select exists(select 1 from public.households where id = h and owner = auth.uid()) $$;

-- Policies
drop policy if exists hh_read on public.households;
create policy hh_read on public.households for select using (id = public.my_household());
drop policy if exists hh_update on public.households;
create policy hh_update on public.households for update using (owner = auth.uid()) with check (owner = auth.uid());

drop policy if exists mem_read on public.members;
create policy mem_read on public.members for select using (household_id = public.my_household());
drop policy if exists mem_update_self on public.members;
create policy mem_update_self on public.members for update using (user_id = auth.uid()) with check (user_id = auth.uid() and household_id = public.my_household());
drop policy if exists mem_delete_owner on public.members;
create policy mem_delete_owner on public.members for delete using (public.i_am_owner(household_id) and user_id <> auth.uid());

drop policy if exists inv_owner on public.invites;
create policy inv_owner on public.invites for all using (public.i_am_owner(household_id)) with check (public.i_am_owner(household_id));

drop policy if exists cfg_read on public.household_config;
create policy cfg_read on public.household_config for select using (household_id = public.my_household());
drop policy if exists cfg_write on public.household_config;
create policy cfg_write on public.household_config for all using (public.i_am_owner(household_id)) with check (public.i_am_owner(household_id));

drop policy if exists ud_self on public.user_data;
create policy ud_self on public.user_data for all using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Owner: create the household once (the signed-in person becomes its owner)
create or replace function public.create_household(p_name text, p_display text, p_avatar text) returns uuid
language plpgsql security definer set search_path = public as $$
declare h uuid;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  if exists(select 1 from members where user_id = auth.uid()) then raise exception 'already in a household'; end if;
  insert into households(owner, name) values (auth.uid(), coalesce(nullif(trim(p_name), ''), 'My family')) returning id into h;
  insert into members(household_id, user_id, role, display_name, avatar) values (h, auth.uid(), 'owner', left(coalesce(p_display, ''), 40), left(coalesce(p_avatar, ''), 40));
  insert into household_config(household_id) values (h);
  return h;
end $$;

-- Owner: a new invite code (6 letters/digits without look-alikes), usable [p_uses] times for 14 days
create or replace function public.create_invite(p_uses int default 1) returns text
language plpgsql security definer set search_path = public as $$
declare h uuid; c text; alphabet text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; i int;
begin
  select id into h from households where owner = auth.uid();
  if h is null then raise exception 'only the owner can invite'; end if;
  loop
    c := '';
    for i in 1..6 loop c := c || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1); end loop;
    exit when not exists(select 1 from invites where code = c);
  end loop;
  insert into invites(code, household_id, uses_left) values (c, h, greatest(1, least(coalesce(p_uses, 1), 20)));
  return c;
end $$;

-- Family member: join with a code, with their own name and avatar
create or replace function public.join_household(p_code text, p_display text, p_avatar text) returns uuid
language plpgsql security definer set search_path = public as $$
declare inv invites%rowtype; n int;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  if exists(select 1 from members where user_id = auth.uid()) then raise exception 'already in a household'; end if;
  select * into inv from invites where code = upper(trim(p_code)) for update;
  if inv.code is null or inv.uses_left < 1 or inv.expires_at < now() then raise exception 'invite code not valid'; end if;
  select count(*) into n from members where household_id = inv.household_id;
  if n >= 20 then raise exception 'household is full'; end if;
  insert into members(household_id, user_id, role, display_name, avatar) values (inv.household_id, auth.uid(), 'member', left(coalesce(p_display, ''), 40), left(coalesce(p_avatar, ''), 40));
  update invites set uses_left = uses_left - 1 where code = inv.code;
  return inv.household_id;
end $$;

revoke all on function public.create_household(text, text, text) from anon;
revoke all on function public.create_invite(int) from anon;
revoke all on function public.join_household(text, text, text) from anon;
grant execute on function public.create_household(text, text, text) to authenticated;
grant execute on function public.create_invite(int) to authenticated;
grant execute on function public.join_household(text, text, text) to authenticated;

-- ---------------------------------------------------------------- Problem reports (added in 1.3, T-026)
-- A family TV sends its last crash, freeze or forced close here; only the owner can read them.
create table if not exists public.reports (
  id bigint generated always as identity primary key,
  household_id uuid not null references public.households(id) on delete cascade,
  user_id uuid not null default auth.uid(),
  member_name text not null default '' check (length(member_name) <= 60),
  kind text not null check (length(kind) <= 400),
  version text not null default '' check (length(version) <= 40),
  device text not null default '' check (length(device) <= 80),
  happened_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);
alter table public.reports enable row level security;
drop policy if exists rep_insert on public.reports;
create policy rep_insert on public.reports for insert to authenticated
  with check (user_id = auth.uid() and household_id = public.my_household());
drop policy if exists rep_owner_read on public.reports;
create policy rep_owner_read on public.reports for select to authenticated using (public.i_am_owner(household_id));
drop policy if exists rep_owner_delete on public.reports;
create policy rep_owner_delete on public.reports for delete to authenticated using (public.i_am_owner(household_id));
revoke all on public.reports from anon;
create index if not exists reports_household on public.reports (household_id, happened_at desc);

-- ---------------------------------------------------------------- Personal data sync (added in 1.3, CloudSync)
-- The server sets the time of every change, so TVs with wrong clocks still sync in the right order.
create or replace function public.touch_updated_at() returns trigger
language plpgsql set search_path = public as $$ begin new.updated_at = now(); return new; end $$;
drop trigger if exists user_data_touch on public.user_data;
create trigger user_data_touch before insert or update on public.user_data for each row execute function public.touch_updated_at();
revoke all on public.user_data from anon;
