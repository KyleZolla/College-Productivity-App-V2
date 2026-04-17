package com.example.productivityapp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves the logged-in Supabase user id from the access token (`sub` claim),
 * with a REST fallback to [GET /auth/v1/user](https://supabase.com/docs/reference/api/get-user).
 */
object SupabaseUserId {

    fun resolveUserId(accessToken: String): String? {
        userIdFromJwt(accessToken)?.let { return it }
        return fetchUserIdFromApi(accessToken)
    }

    private fun userIdFromJwt(accessToken: String): String? {
        return JwtPayloadParser.payloadJson(accessToken)?.optString("sub")?.takeIf { it.isNotBlank() }
    }

    private fun fetchUserIdFromApi(accessToken: String): String? {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) return null
        return try {
            val url = URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/user")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val responseCode = connection.responseCode
            val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (responseCode !in 200..299) return null
            JSONObject(body).optString("id").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
