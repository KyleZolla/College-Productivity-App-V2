package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * `profiles` table via PostgREST.
 * Columns (camelCase): `id`, `currentStreak`, `lastCompletedDate`.
 * Future-day plan done early: only `lastCompletedDate` is set to that plan day; `currentStreak` is unchanged.
 */
object SupabaseProfilesApi {

    data class ProfileRow(
        val id: String,
        val currentStreak: Int,
        val lastCompletedDate: LocalDate?,
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
            val query = "select=id,currentStreak,lastCompletedDate&id=eq.$enc"
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
     * Persists streak fields. Prefer **PATCH** (works with typical RLS: update own row).
     * POST upsert often fails when only UPDATE is allowed or merge-duplicates isn't configured.
     */
    fun upsertStreak(
        accessToken: String,
        userId: String,
        currentStreak: Int,
        lastCompletedDate: LocalDate,
    ): PatchResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return PatchResult.Failure("Missing Supabase config.")
        }
        val id = userId.trim()
        if (id.isEmpty()) return PatchResult.Failure("Missing user id.")
        val enc = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
        val dateStr = lastCompletedDate.toString()
        val fields = JSONObject()
            .put("currentStreak", currentStreak)
            .put("lastCompletedDate", dateStr)

        val patch = patchProfilesById(accessToken, enc, fields)
        when (patch) {
            is PatchAttemptResult.Updated -> return PatchResult.Success
            is PatchAttemptResult.NoRowUpdated -> {
                // No profile row yet — insert (needs INSERT RLS or service role).
                return postNewProfileStreak(accessToken, id, currentStreak, lastCompletedDate)
            }
            is PatchAttemptResult.Error -> return PatchResult.Failure(patch.message)
        }
    }

    /**
     * Finishing a **future** day’s plan early: set `lastCompletedDate` to that plan’s calendar day only.
     * Does **not** change `currentStreak`.
     */
    fun patchLastCompletedDateForFuturePlanDayOnly(
        accessToken: String,
        userId: String,
        planDay: LocalDate,
    ): PatchResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return PatchResult.Failure("Missing Supabase config.")
        }
        val id = userId.trim()
        if (id.isEmpty()) return PatchResult.Failure("Missing user id.")
        val enc = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
        val fields = JSONObject().put("lastCompletedDate", planDay.toString())
        val patch = patchProfilesById(accessToken, enc, fields)
        return when (patch) {
            is PatchAttemptResult.Updated -> PatchResult.Success
            is PatchAttemptResult.NoRowUpdated -> postNewProfileLastCompletedDateOnly(accessToken, id, planDay)
            is PatchAttemptResult.Error -> PatchResult.Failure(patch.message)
        }
    }

    private fun postNewProfileLastCompletedDateOnly(
        accessToken: String,
        userId: String,
        planDay: LocalDate,
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
                .put("lastCompletedDate", planDay.toString())
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

    private fun postNewProfileStreak(
        accessToken: String,
        userId: String,
        currentStreak: Int,
        lastCompletedDate: LocalDate,
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
                .put("currentStreak", currentStreak)
                .put("lastCompletedDate", lastCompletedDate.toString())
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
        val streak = when {
            obj.has("currentStreak") && !obj.isNull("currentStreak") -> obj.optInt("currentStreak", 0)
            obj.has("current_streak") && !obj.isNull("current_streak") -> obj.optInt("current_streak", 0)
            else -> 0
        }
        val lastRaw = when {
            obj.has("lastCompletedDate") && !obj.isNull("lastCompletedDate") ->
                obj.get("lastCompletedDate")
            obj.has("last_completed_date") && !obj.isNull("last_completed_date") ->
                obj.get("last_completed_date")
            else -> null
        }
        val lastDate = parseDateField(lastRaw)
        return ProfileRow(id = id, currentStreak = streak, lastCompletedDate = lastDate)
    }

    private fun parseDateField(raw: Any?): LocalDate? {
        if (raw == null || raw == JSONObject.NULL) return null
        val s = raw.toString().trim()
        if (s.isEmpty()) return null
        val datePart = s.take(10)
        return runCatching { LocalDate.parse(datePart) }.getOrNull()
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
