package com.example.productivityapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceUtilTest {

    @Test
    fun codeChallengeS256_matchesRfc7636TestVector() {
        // Appendix B of RFC 7636.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        assertEquals(expectedChallenge, PkceUtil.codeChallengeS256(verifier))
    }

    @Test
    fun generateCodeVerifier_isUrlSafeAndWithinRfcLength() {
        val verifier = PkceUtil.generateCodeVerifier()

        // RFC 7636 requires 43..128 characters of [A-Za-z0-9-._~].
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun generateCodeVerifier_isRandom() {
        assertNotEquals(PkceUtil.generateCodeVerifier(), PkceUtil.generateCodeVerifier())
    }
}
