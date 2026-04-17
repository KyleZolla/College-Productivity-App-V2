package com.example.productivityapp

import android.util.Base64
import org.json.JSONObject

internal object JwtPayloadParser {

    fun payloadJson(accessToken: String): JSONObject? {
        val parts = accessToken.split('.')
        if (parts.size < 2) return null
        val segment = parts[1]
        val padded = buildString {
            append(segment)
            repeat((4 - segment.length % 4) % 4) { append('=') }
        }
        return try {
            JSONObject(String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
