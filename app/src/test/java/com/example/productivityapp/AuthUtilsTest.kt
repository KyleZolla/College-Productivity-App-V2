package com.example.productivityapp

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
}
