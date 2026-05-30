package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * `profiles` table via PostgREST.
 * Columns (camelCase): `id`, `school`, `yearInSchool`.
 */
object SupabaseProfilesApi {

    data class ProfileRow(
        val id: String,
        val school: String?,
        val yearInSchool: String?,
    )

    sealed class GetResult {
        data class Success(val row: ProfileRow) : GetResult()
        data class NotFound(val userId: String) : GetResult()
        data class Failure(val message: String) : GetResult()
    }

    sealed class PatchResult {
        object Success : PatchResult()
        data class Failure(val message: String) : PatchResult()
    }

    fun get(accessToken: String, userId: String): GetResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return GetResult.Failure("Missing Supabase config.")
        }
        val id = userId.trim()
        if (id.isEmpty()) return GetResult.Failure("Missing user id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
            val query = "select=id,school,yearInSchool&id=eq.$enc"
            val url = URL("$base/rest/v1/profiles?$query")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                return GetResult.Failure(parseError(responseBody, responseCode))
            }
            val arr = JSONArray(responseBody)
            if (arr.length() == 0) return GetResult.NotFound(id)
            GetResult.Success(parseRow(arr.getJSONObject(0), id))
        } catch (e: Exception) {
            GetResult.Failure(e.message ?: "Network error.")
        }
    }

    /**
     * Saves optional account fields on sign-up or from profile settings.
     * PATCH when a row exists; POST a minimal row otherwise.
     */
    fun upsertAccountFields(
        accessToken: String,
        userId: String,
        school: String?,
        yearInSchool: String?,
    ): PatchResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return PatchResult.Failure("Missing Supabase config.")
        }
        val id = userId.trim()
        if (id.isEmpty()) return PatchResult.Failure("Missing user id.")
        val enc = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
        val fields = JSONObject()
            .put("school", school?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("yearInSchool", yearInSchool?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)

        val patch = patchProfilesById(accessToken, enc, fields)
        return when (patch) {
            is PatchAttemptResult.Updated -> PatchResult.Success
            is PatchAttemptResult.NoRowUpdated -> postNewProfileAccountFields(accessToken, id, school, yearInSchool)
            is PatchAttemptResult.Error -> PatchResult.Failure(patch.message)
        }
    }

    private sealed class PatchAttemptResult {
        object Updated : PatchAttemptResult()
        object NoRowUpdated : PatchAttemptResult()
        data class Error(val message: String) : PatchAttemptResult()
    }

    private fun patchProfilesById(
        accessToken: String,
        idEncoded: String,
        fields: JSONObject,
    ): PatchAttemptResult {
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val url = URL("$base/rest/v1/profiles?id=eq.$idEncoded")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true
            val bodyBytes = fields.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                return PatchAttemptResult.Error(parseError(responseBody, responseCode))
            }
            val trimmed = responseBody.trim()
            if (trimmed == "[]" || trimmed.isEmpty()) {
                PatchAttemptResult.NoRowUpdated
            } else {
                PatchAttemptResult.Updated
            }
        } catch (e: Exception) {
            PatchAttemptResult.Error(e.message ?: "Network error.")
        }
    }

    private fun postNewProfileAccountFields(
        accessToken: String,
        userId: String,
        school: String?,
        yearInSchool: String?,
    ): PatchResult {
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val url = URL("$base/rest/v1/profiles")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Prefer", "return=minimal")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true

            val payload = JSONObject()
                .put("id", userId)
                .put("school", school?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("yearInSchool", yearInSchool?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode in 200..299) {
                PatchResult.Success
            } else {
                PatchResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            PatchResult.Failure(e.message ?: "Network error.")
        }
    }

    private fun parseRow(obj: JSONObject, fallbackId: String): ProfileRow {
        val id = obj.optString("id").ifBlank { fallbackId }
        val school = when {
            obj.has("school") && !obj.isNull("school") -> obj.optString("school").trim().ifBlank { null }
            else -> null
        }
        val yearInSchool = when {
            obj.has("yearInSchool") && !obj.isNull("yearInSchool") ->
                obj.optString("yearInSchool").trim().ifBlank { null }
            obj.has("year_in_school") && !obj.isNull("year_in_school") ->
                obj.optString("year_in_school").trim().ifBlank { null }
            else -> null
        }

        return ProfileRow(
            id = id,
            school = school,
            yearInSchool = yearInSchool,
        )
    }

    private fun parseError(body: String, code: Int): String {
        return try {
            val root = JSONObject(body)
            root.optString("message")
                .ifBlank { root.optString("error_description") }
                .ifBlank { root.optString("hint") }
                .ifBlank { root.optString("details") }
                .ifBlank { "Request failed ($code)" }
        } catch (_: Exception) {
            if (body.isBlank()) "Request failed ($code)" else body
        }
    }
}
