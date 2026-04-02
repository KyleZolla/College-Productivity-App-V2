package com.example.productivityapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUtilsTest {
    @Test
    fun validEmailAndPassword_returnsTrue() {
        assertTrue(AuthUtils.isValidEmailAndPassword("student@example.com", "secret12"))
    }

    @Test
    fun invalidEmail_returnsFalse() {
        assertFalse(AuthUtils.isValidEmailAndPassword("invalid-email", "secret12"))
    }

    @Test
    fun shortPassword_returnsFalse() {
        assertFalse(AuthUtils.isValidEmailAndPassword("student@example.com", "12345"))
    }

    @Test
    fun parseFragmentParams_parsesTokenFragment() {
        val fragment = "access_token=abc123&refresh_token=ref456&expires_in=3600"

        val result = AuthUtils.parseFragmentParams(fragment)

        assertEquals("abc123", result["access_token"])
        assertEquals("ref456", result["refresh_token"])
        assertEquals("3600", result["expires_in"])
    }

    @Test
    fun parseFragmentParams_decodesUriEncoding() {
        val fragment = "token_type=Bearer&scope=email%20profile"

        val result = AuthUtils.parseFragmentParams(fragment)

        assertEquals("Bearer", result["token_type"])
        assertEquals("email profile", result["scope"])
    }
}
