package com.example.productivityapp

import org.json.JSONObject
import java.util.Locale

/**
 * First name / display name from the JWT `user_metadata` (Supabase sign-up),
 * falling back to the part before @ in `email`.
 */
object AuthUserDisplayName {

    fun firstNameFromAccessToken(accessToken: String): String {
        val root = JwtPayloadParser.payloadJson(accessToken) ?: return "there"
        val meta = root.optJSONObject("user_metadata") ?: JSONObject()
        val full = meta.optString("full_name").trim().ifBlank { null }
            ?: meta.optString("name").trim().ifBlank { null }
        if (full != null) {
            val token = full.split(Regex("\\s+")).firstOrNull().orEmpty()
            if (token.isNotBlank()) return token
        }
        val first = meta.optString("first_name").trim().ifBlank { null }
            ?: meta.optString("given_name").trim().ifBlank { null }
        if (first != null) return first
        val email = root.optString("email").trim()
        if (email.isNotBlank()) {
            val local = email.substringBefore('@').substringBefore('+')
            if (local.isNotBlank()) {
                return local.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        }
        return "there"
    }

    /** Full name for profile: metadata names, else a title-cased email local-part. */
    fun displayNameForProfile(accessToken: String): String {
        val root = JwtPayloadParser.payloadJson(accessToken) ?: return ""
        val meta = root.optJSONObject("user_metadata") ?: JSONObject()
        meta.optString("full_name").trim().ifBlank { null }?.let { return it }
        val fn = meta.optString("first_name").trim()
        val ln = meta.optString("last_name").trim()
        if (fn.isNotEmpty() || ln.isNotEmpty()) return "$fn $ln".trim()
        meta.optString("name").trim().ifBlank { null }?.let { return it }
        val given = meta.optString("given_name").trim()
        val family = meta.optString("family_name").trim()
        if (given.isNotEmpty() || family.isNotEmpty()) return "$given $family".trim()
        val email = root.optString("email").trim()
        if (email.isNotBlank()) {
            return email.substringBefore('@').substringBefore('+')
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
        return ""
    }

    fun emailFromAccessToken(accessToken: String): String {
        return JwtPayloadParser.payloadJson(accessToken)?.optString("email")?.trim().orEmpty()
    }
}
