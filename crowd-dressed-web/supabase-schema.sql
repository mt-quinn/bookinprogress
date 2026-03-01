-- ============================================================
-- Crowd Dressed – Supabase schema
-- Run this once in the Supabase SQL editor for your project.
-- ============================================================

-- Sessions: one row per creator, keyed by their shareable code.
create table if not exists sessions (
  id         uuid primary key default gen_random_uuid(),
  code       text unique not null,
  created_at timestamptz default now()
);

-- Votes: one row per (viewer, session, slot).
-- Upserting on the unique constraint lets viewers change their vote per slot.
create table if not exists votes (
  id         uuid primary key default gen_random_uuid(),
  session_id uuid not null references sessions(id) on delete cascade,
  voter_id   text not null,   -- anonymous UUID stored in browser localStorage
  slot       text not null,   -- 'HEAD' | 'CAPE' | 'AMULET' | 'WEAPON' | 'TORSO' | 'SHIELD' | 'LEGS' | 'HANDS' | 'BOOTS'
  item_id    integer not null,
  item_name  text not null,   -- stored for convenience so the web can show standings without an extra lookup
  voted_at   timestamptz default now(),
  unique (session_id, voter_id, slot)
);

create index if not exists votes_session_slot_item on votes (session_id, slot, item_id);

-- ----------------------------------------------------------------
-- View: top-voted item per slot per session, with session code
-- joined in so the plugin can query directly by code.
-- ----------------------------------------------------------------
create or replace view top_votes_per_slot as
select distinct on (v.session_id, v.slot)
  s.code,
  v.session_id,
  v.slot,
  v.item_id,
  v.item_name,
  count(*) over (partition by v.session_id, v.slot, v.item_id) as vote_count
from votes v
join sessions s on s.id = v.session_id
order by v.session_id, v.slot, vote_count desc;

-- ----------------------------------------------------------------
-- Row Level Security
-- The anon key is intentionally public; RLS is the access gate.
-- ----------------------------------------------------------------
alter table sessions enable row level security;
alter table votes    enable row level security;

-- Anyone can create a session (creator flow)
create policy "anon insert sessions"
  on sessions for insert
  with check (true);

-- Anyone can read sessions (needed to resolve code → id)
create policy "anon read sessions"
  on sessions for select
  using (true);

-- Viewers can insert votes
create policy "anon insert votes"
  on votes for insert
  with check (true);

-- Viewers can update their own vote (upsert changes the item_id / item_name)
create policy "anon update own votes"
  on votes for update
  using (true);

-- Anyone can read vote tallies
create policy "anon read votes"
  on votes for select
  using (true);
