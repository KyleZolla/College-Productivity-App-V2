package com.example.productivityapp

object EstimateFeedback {
    const val TOO_SHORT = "too_short"
    const val ABOUT_RIGHT = "about_right"
    const val TOO_LONG = "too_long"

    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return null
        return when (s) {
            TOO_SHORT, "too short" -> TOO_SHORT
            ABOUT_RIGHT, "about right", "about_right" -> ABOUT_RIGHT
            TOO_LONG, "too long" -> TOO_LONG
            else -> null
        }
    }
}
