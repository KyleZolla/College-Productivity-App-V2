package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SupabaseAchievementsApi {

    data class AchievementsRow(
        val userId: String,
        val firstTaskCompleted: Boolean,
        val gettingAhead: Boolean,
        val halfwayThroughCurrentTasks: Boolean,
    )

    sealed class GetResult {
        data class Success(val row: AchievementsRow) : GetResult()
        data class NotFound(val userId: String) : GetResult()
        data class Failure(val message: String) : GetResult()
    }

    sealed class UpsertResult {
        data class Success(val row: AchievementsRow) : UpsertResult()
        data class Failure(val message: String) : UpsertResult()
    }

    fun get(accessToken: String, userId: String): GetResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return GetResult.Failure("Missing Supabase config.")
        }
        val id = userId.trim()
        if (id.isEmpty()) return GetResult.Failure("Missing user id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val query =
                "select=userId,first_task_completed,getting_ahead,halfway_through_current_tasks&userId=eq.$id"
            val url = URL("$base/rest/v1/user_achievements?$query")
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
            val obj = arr.getJSONObject(0)
            GetResult.Success(parseRow(obj, fallbackUserId = id))
        } catch (e: Exception) {
            GetResult.Failure(e.message ?: "Network error.")
        }
    }

    fun upsert(
        accessToken: String,
        userId: String,
        firstTaskCompleted: Boolean? = null,
        gettingAhead: Boolean? = null,
        halfwayThroughCurrentTasks: Boolean? = null,
    ): UpsertResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return UpsertResult.Failure("Missing Supabase config.")
        }
        val id = userId.trim()
        if (id.isEmpty()) return UpsertResult.Failure("Missing user id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val url = URL("$base/rest/v1/user_achievements")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Prefer", "return=representation,resolution=merge-duplicates")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true

            val payload = JSONObject().put("userId", id).apply {
                if (firstTaskCompleted != null) put("first_task_completed", firstTaskCompleted)
                if (gettingAhead != null) put("getting_ahead", gettingAhead)
                if (halfwayThroughCurrentTasks != null) put(
                    "halfway_through_current_tasks",
                    halfwayThroughCurrentTasks
                )
            }
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                return UpsertResult.Failure(parseError(responseBody, responseCode))
            }
            val arr = JSONArray(responseBody)
            val obj = if (arr.length() > 0) arr.getJSONObject(0) else JSONObject().put("userId", id)
            UpsertResult.Success(parseRow(obj, fallbackUserId = id))
        } catch (e: Exception) {
            UpsertResult.Failure(e.message ?: "Network error.")
        }
    }

    private fun parseRow(obj: JSONObject, fallbackUserId: String): AchievementsRow {
        val userId = obj.optString("userId").ifBlank { fallbackUserId }
        return AchievementsRow(
            userId = userId,
            firstTaskCompleted = obj.optBoolean("first_task_completed", false),
            gettingAhead = obj.optBoolean("getting_ahead", false),
            halfwayThroughCurrentTasks = obj.optBoolean("halfway_through_current_tasks", false),
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

