package com.example.productivityapp

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) helpers for the OAuth code flow.
 *
 * The verifier never leaves the device; only its S256 challenge is sent with the
 * authorize request, so an intercepted auth code is useless to other apps.
 */
object PkceUtil {

    private const val VERIFIER_BYTES = 64

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        SecureRandom().nextBytes(bytes)
        return base64UrlNoPadding(bytes)
    }

    fun codeChallengeS256(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPadding(digest)
    }

    private fun base64UrlNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
