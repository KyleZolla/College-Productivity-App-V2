-- Roadmap confidence label for complex tasks (High | Medium | Low).
-- Run in Supabase → SQL Editor after deploying the app change.

alter table public.tasks
  add column if not exists "roadmapConfidence" text;

comment on column public.tasks."roadmapConfidence" is
  'Heuristic roadmap quality signal set at complex task creation: High, Medium, or Low.';
