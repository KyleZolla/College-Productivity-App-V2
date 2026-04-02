# Supabase Auth Setup (Task 2.1)

This project reads Supabase config from `local.properties` via `BuildConfig`.

## 1) Create Supabase project

1. Create a new project in Supabase.
2. Open `Project Settings` -> `API`.
3. Copy:
   - Project URL
   - `anon` public API key

## 2) Configure auth providers

1. Open `Authentication` -> `Providers`.
2. Enable `Email` provider.
3. Enable `Google` provider and set the Google OAuth Client ID/Secret.
4. Add the app/callback redirect URLs required by your auth flow.

## 3) Add local config to Android project

1. Copy `local.properties.example` to `local.properties` (if needed).
2. Add/update:

```properties
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_ANON_KEY=your-anon-key
```

3. Sync Gradle.

## 4) Verify config is wired

- `app/build.gradle.kts` exposes:
  - `BuildConfig.SUPABASE_URL`
  - `BuildConfig.SUPABASE_ANON_KEY`
- Values should come from your local `local.properties`.
