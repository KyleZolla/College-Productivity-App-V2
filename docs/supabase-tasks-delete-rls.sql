-- Allow each user to DELETE their own tasks (PostgREST + Android app).
-- Without this, DELETE returns 200 with [] and the app reports "could not delete".
--
-- Assumes `public.tasks` has a "userId" uuid column (same as insertTask in the app).
-- Run in Supabase → SQL Editor. Adjust the column name if your table uses user_id instead.

alter table public.tasks enable row level security;

drop policy if exists "delete own tasks" on public.tasks;
create policy "delete own tasks"
on public.tasks
for delete
using (auth.uid() = "userId");
