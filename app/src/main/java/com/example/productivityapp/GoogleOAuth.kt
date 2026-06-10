package com.example.productivityapp

import android.content.Context
import android.net.Uri

/**
 * Builds the Supabase Google OAuth authorize URL using the PKCE code flow.
 *
 * GoTrue switches to PKCE when `code_challenge` is present: the redirect carries a
 * one-time `?code=` instead of tokens, and [AuthCallbackActivity] exchanges it
 * together with the locally stored verifier.
 */
object GoogleOAuth {

    /**
     * Generates and stores a fresh PKCE verifier, then returns the authorize URL.
     * Returns null when Supabase config is missing.
     */
    fun buildAuthorizeUrl(context: Context): String? {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return null
        }
        val codeVerifier = PkceUtil.generateCodeVerifier()
        PendingOAuth.save(context, codeVerifier)
        val codeChallenge = PkceUtil.codeChallengeS256(codeVerifier)
        val redirectTo = Uri.encode(context.getString(R.string.oauth_redirect_url))
        return BuildConfig.SUPABASE_URL.trimEnd('/') +
            "/auth/v1/authorize" +
            "?provider=google" +
            "&redirect_to=$redirectTo" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=s256"
    }
}
