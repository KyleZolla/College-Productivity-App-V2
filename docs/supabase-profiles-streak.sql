-- Streak columns on `profiles` (camelCase — matches the Android app and PostgREST JSON keys).
-- In PostgreSQL, camelCase identifiers must be double-quoted.

alter table public.profiles
  add column if not exists "currentStreak" integer not null default 0;

alter table public.profiles
  add column if not exists "lastCompletedDate" date null;

alter table public.profiles
  add column if not exists "lastCompletedDateBackup" date null;

comment on column public.profiles."currentStreak" is 'Updated by the app when the user completes all steps for the calendar day; reset by Edge Function update-streak if needed.';
comment on column public.profiles."lastCompletedDate" is 'Calendar day of the plan you last fully finished (today or a future day done early). Streak uses this with currentStreak only when completing today’s plan.';
comment on column public.profiles."lastCompletedDateBackup" is 'Backup of lastCompletedDate captured right before today was first marked complete; used to undo completion if a step is unchecked after completion (streak is decremented).';
