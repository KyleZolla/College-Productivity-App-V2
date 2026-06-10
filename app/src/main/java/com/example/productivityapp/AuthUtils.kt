package com.example.productivityapp

object AuthUtils {
    private val emailRegex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun isValidEmailAndPassword(email: String, password: String): Boolean {
        return emailRegex.matches(email) && password.length >= 6
    }
}
