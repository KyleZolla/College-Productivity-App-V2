package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate

/**
 * `tasks` table via PostgREST.
 * Columns: id, userId, title, dueDate, created_at.
 */
object SupabaseTasksApi {

    data class TaskRow(
        val id: String,
        val title: String,
        val dueDate: LocalDate?,
    )

    sealed class ListResult {
        data class Success(val tasks: List<TaskRow>) : ListResult()
        data class Failure(val message: String) : ListResult()
    }

    sealed class InsertResult {
        data class Success(val id: String) : InsertResult()
        data class Failure(val message: String) : InsertResult()
    }

    fun listTasks(accessToken: String): ListResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return ListResult.Failure("Missing Supabase config.")
        }
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val query = "select=id,title,dueDate&order=dueDate.asc.nullslast"
            val url = URL("$base/rest/v1/tasks?$query")
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

            if (responseCode in 200..299) {
                val arr = JSONArray(responseBody)
                val out = ArrayList<TaskRow>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    val title = obj.optString("title")
                    val due = parseDueDate(obj.opt("dueDate"))
                    out.add(TaskRow(id = id, title = title, dueDate = due))
                }
                ListResult.Success(out)
            } else {
                ListResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            ListResult.Failure(e.message ?: "Network error.")
        }
    }

    private fun parseDueDate(raw: Any?): LocalDate? {
        if (raw == null || raw == JSONObject.NULL) return null
        val s = raw.toString().trim()
        if (s.isBlank()) return null
        val datePart = s.take(10)
        return try {
            LocalDate.parse(datePart)
        } catch (_: Exception) {
            null
        }
    }

    fun insertTask(
        accessToken: String,
        userId: String,
        title: String,
        dueDate: LocalDate,
    ): InsertResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return InsertResult.Failure("Missing Supabase config.")
        }
        return try {
            val url = URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/tasks")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true

            val payload = JSONObject()
                .put("userId", userId)
                .put("title", title)
                .put("dueDate", dueDate.toString())
                .put("created_at", Instant.now().toString())

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode in 200..299) {
                val arr = JSONArray(responseBody)
                if (arr.length() == 0) {
                    return InsertResult.Failure("No row returned from database.")
                }
                val id = arr.getJSONObject(0).opt("id")?.toString()?.takeIf { it.isNotBlank() }
                    ?: return InsertResult.Failure("Missing task id in response.")
                InsertResult.Success(id)
            } else {
                InsertResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            InsertResult.Failure(e.message ?: "Network error.")
        }
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
