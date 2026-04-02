package com.example.productivityapp

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object AuthUtils {
    private val emailRegex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun isValidEmailAndPassword(email: String, password: String): Boolean {
        return emailRegex.matches(email) && password.length >= 6
    }

    fun parseFragmentParams(fragment: String?): Map<String, String> {
        if (fragment.isNullOrBlank()) return emptyMap()
        return fragment.split("&")
            .mapNotNull { part ->
                val idx = part.indexOf("=")
                if (idx <= 0) return@mapNotNull null
                val key = URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8)
                key to value
            }
            .toMap()
    }
}
