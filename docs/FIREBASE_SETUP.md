# Firebase setup (local only)

`app/google-services.json` is **not** in git. Each developer (and CI, if you add it later) needs their own copy.

## Get the file

1. Open [Firebase Console](https://console.firebase.google.com/) → your project → **Project settings**.
2. Under **Your apps**, select the Android app (`com.example.productivityapp`).
3. Download **google-services.json**.
4. Save it as `app/google-services.json` (same folder as `google-services.json.example`).

## Build

The file must exist locally or the Google Services Gradle plugin will fail. Your copy is ignored by git and stays on your machine.

## If this repo was ever public with the real file committed

The old API key may still exist in **git history**. Do both:

1. **Rotate / restrict** the key in [Google Cloud Console](https://console.cloud.google.com/) → APIs & Services → **Credentials**:
   - Restrict the Android key to package `com.example.productivityapp` and your app **SHA-1** fingerprints (debug + release).
   - If unsure, create a new key in Firebase, download a fresh `google-services.json`, then delete or disable the old key.
2. **Stop tracking** the file (one-time, after pulling this change):
   ```bash
   git rm --cached app/google-services.json
   git commit -m "Stop tracking google-services.json; use local Firebase config"
   ```
3. **Optional — scrub history** so the old key is not in past commits: use [GitHub’s guide](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository) or `git filter-repo`. Rotating the key is usually enough if the new key is restricted.
