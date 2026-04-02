# Auth Test Plan (Task 2.5)

This checklist validates the implemented auth flows end to end.

## Automated tests

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Covered by unit tests:
- Email/password input validation logic
- OAuth callback fragment parsing (including URI-decoded values)

## Manual test checklist

### Email sign-up
- Open app on sign-up screen.
- Submit a new email/password.
- Confirm success message appears.
- Confirm user appears in Supabase `auth.users`.
- If email confirmation is enabled, complete confirmation link.

### Email login
- Open log-in screen.
- Log in with valid credentials.
- Verify navigation to Home screen.
- Try invalid password and verify clear error message.

### Google login
- Tap `Continue with Google`.
- Complete provider flow in browser.
- Confirm app returns from deep link and lands on Home screen.
- Verify Supabase auth logs show successful OAuth sign-in.

### Session persistence
- After successful login, close app completely and relaunch.
- Verify app skips auth screens and opens Home screen.

### Logout
- On Home, tap `Log out`.
- Verify navigation back to Login.
- Relaunch app and verify auth screen shows again (session cleared).

## Notes
- Supabase redirect configuration must include:
  - `productivityapp://auth/callback`
