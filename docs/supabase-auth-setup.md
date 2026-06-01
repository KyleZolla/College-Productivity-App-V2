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

### Email sign-up and confirmation (important)

If **Confirm email** is enabled, Supabase must be able to send mail to any address users type (not only your org team). With the built-in mailer, sign-up often fails with HTTP 400 and a misleading message like `Email address "user@school.edu" is invalid` even when the address is fine.

Pick one:

- **Production / real users:** `Authentication` -> `Emails` -> configure **custom SMTP** (Resend, SendGrid, etc.). See [Auth SMTP](https://supabase.com/docs/guides/auth/auth-smtp).
- **Development / testing:** `Authentication` -> `Providers` -> `Email` -> turn off **Confirm email**, then save.

Your hosted project settings override `supabase/config.toml` in this repo.

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
