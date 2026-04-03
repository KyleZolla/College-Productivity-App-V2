# Task ID: 11

**Title:** Password recovery (forgot password)

**Status:** pending

**Dependencies:** 2

**Priority:** medium

**Description:** Let users request a reset email and set a new password from a return link into the app.

**Details:**

Use Supabase Auth POST /auth/v1/recover with redirect_to pointing at an app deep link (e.g. productivityapp://auth/reset). Handle the callback (fragment tokens, type=recovery), then call the user update/password API with the recovery session. Optionally host a minimal static HTTPS page that redirects custom-scheme + hash for email clients that block app links. Document Supabase redirect allowlist and email template.

**Test Strategy:**

Request reset, open link (or simulated intent), confirm new password works for login; verify invalid/expired tokens show a clear error.

## Subtasks

### 11.1. Configure Supabase recovery redirect

**Status:** pending  
**Dependencies:** None  

Allowlist redirect URL(s) and confirm email recovery template points to the chosen URL.

**Details:**

In Supabase Auth settings, add the app deep link and any optional HTTPS bridge URL to Redirect URLs. Note Site URL implications for development vs production.

### 11.2. Forgot-password entry on login

**Status:** pending  
**Dependencies:** 11.1  

UI to collect email and call recover endpoint.

**Details:**

Add Forgot password? on LoginActivity (or dialog). POST recover with apikey/Authorization headers matching existing auth REST usage; show success/error messaging without revealing whether the email exists if desired.

### 11.3. Deep link handler for reset session

**Status:** pending  
**Dependencies:** 11.1  

Activity/intent-filter to receive reset link and parse tokens.

**Details:**

Reuse or extend AuthUtils/AuthCallbackActivity pattern: parse URL fragment for access_token, refresh_token, type; distinguish recovery from OAuth; save session or pass tokens to reset screen.

### 11.4. In-app set new password screen

**Status:** pending  
**Dependencies:** 11.3  

New password + confirm, submit with recovery session.

**Details:**

Call Supabase user update (password) with Bearer access token from recovery flow; then clear recovery UX and route to login or home per product choice.

### 11.5. Optional static redirect page

**Status:** pending  
**Dependencies:** 11.1  

Host a one-file HTML redirect for stubborn email clients.

**Details:**

If needed, deploy minimal page on GitHub Pages or similar that forwards location.hash to the app custom scheme; add that HTTPS URL to Supabase allowlist.
