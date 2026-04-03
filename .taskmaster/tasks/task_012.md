# Task ID: 12

**Title:** Security hardening

**Status:** pending

**Dependencies:** 1, 2

**Priority:** high

**Description:** Reduce risk around secrets, sessions, transport, and release builds.

**Details:**

Audit how Supabase anon key and URLs are packaged; prefer BuildConfig/local properties and never commit secrets. Review EncryptedSharedPreferences or Android Keystore for tokens if elevating beyond basic SharedPreferences. Enable R8/ProGuard rules for release, network security config (cleartext off), dependency updates. Align Supabase Row Level Security with app roles. Document threat model briefly.

**Test Strategy:**

Release build smoke test; verify no secrets in VCS; optional static analysis (lint, dependency check); validate RLS with anon key in SQL editor.

## Subtasks

### 12.1. Secrets and config audit

**Status:** pending  
**Dependencies:** None  

Ensure keys and env files stay out of git and are documented for teammates.

**Details:**

Review .gitignore, local.properties.example, BuildConfig usage; confirm no anon key in strings committed to repo.

### 12.2. Session and token storage review

**Status:** pending  
**Dependencies:** None  

Decide if session persistence meets your risk bar.

**Details:**

Document current SessionManager approach; plan upgrade path to EncryptedSharedPreferences or DataStore encrypted if needed.

### 12.3. Release build and R8

**Status:** pending  
**Dependencies:** None  

Turn on minification/shrinking for release with keep rules for reflection.

**Details:**

Enable isMinifyEnabled (and shrinkResources if appropriate), add ProGuard keep rules for JSON/network libraries used; verify release APK runs.

### 12.4. Transport and network policy

**Status:** pending  
**Dependencies:** None  

HTTPS only; network security config.

**Details:**

Add network_security_config.xml disallowing cleartext; confirm all auth/API calls use https.

### 12.5. Supabase RLS and API surface

**Status:** pending  
**Dependencies:** None  

Database policies match what the mobile anon key may invoke.

**Details:**

When task data exists in Supabase, define RLS policies per user; test with anon JWT claims. Keep service role key server-side only (future backend).
