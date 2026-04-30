-- Create a per-user achievements record.
-- Run this in the Supabase SQL editor.

create table if not exists public.user_achievements (
  "userId" uuid primary key references auth.users(id) on delete cascade,
  first_task_completed boolean not null default false,
  getting_ahead boolean not null default false,
  halfway_through_current_tasks boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Keep updated_at current.
create or replace function public.set_updated_at()
returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql;

drop trigger if exists set_user_achievements_updated_at on public.user_achievements;
create trigger set_user_achievements_updated_at
before update on public.user_achievements
for each row execute procedure public.set_updated_at();

-- Enable RLS and allow users to read/write their own row.
alter table public.user_achievements enable row level security;

drop policy if exists "read own achievements" on public.user_achievements;
create policy "read own achievements"
on public.user_achievements
for select
using (auth.uid() = "userId");

drop policy if exists "upsert own achievements" on public.user_achievements;
create policy "upsert own achievements"
on public.user_achievements
for insert
with check (auth.uid() = "userId");

drop policy if exists "update own achievements" on public.user_achievements;
create policy "update own achievements"
on public.user_achievements
for update
using (auth.uid() = "userId")
with check (auth.uid() = "userId");

