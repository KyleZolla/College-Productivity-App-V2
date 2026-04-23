package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime

enum class TaskListFilter {
    /** All tasks except those with status Complete (home + main tasks tab). */
    ACTIVE,
    /** Only status Complete. */
    COMPLETED,
}

/**
 * `tasks` table via PostgREST.
 * Columns: id, userId, title, dueDate, status, created_at.
 * `dueDate` is expected as `timestamptz` in Postgres (ISO-8601 with offset on write).
 *
 * `status` must match your DB (e.g. "Not Started", "In Progress", "Complete").
 */
object SupabaseTasksApi {

    data class TaskRow(
        val id: String,
        val title: String,
        val dueDate: LocalDateTime?,
        val status: TaskStatus,
        val roadmap: JSONArray?,
    )

    sealed class ListResult {
        data class Success(val tasks: List<TaskRow>) : ListResult()
        data class Failure(val message: String) : ListResult()
    }

    sealed class GetResult {
        data class Success(val task: TaskRow) : GetResult()
        data class Failure(val message: String) : GetResult()
    }

    sealed class InsertResult {
        data class Success(val id: String, val status: TaskStatus) : InsertResult()
        data class Failure(val message: String) : InsertResult()
    }

    sealed class PatchResult {
        data class Success(val status: TaskStatus) : PatchResult()
        data class Failure(val message: String) : PatchResult()
    }

    sealed class PatchDueResult {
        data class Success(val dueDate: LocalDateTime?) : PatchDueResult()
        data class Failure(val message: String) : PatchDueResult()
    }

    sealed class PatchRoadmapResult {
        object Success : PatchRoadmapResult()
        data class Failure(val message: String) : PatchRoadmapResult()
    }

    fun listTasks(accessToken: String, filter: TaskListFilter = TaskListFilter.ACTIVE): ListResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return ListResult.Failure("Missing Supabase config.")
        }
        return listTasksImpl(accessToken, filter)
    }

    private fun listTasksImpl(accessToken: String, filter: TaskListFilter): ListResult {
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = { s: String -> URLEncoder.encode(s, StandardCharsets.UTF_8.name()) }
            val statusParam = when (filter) {
                TaskListFilter.ACTIVE -> "status=neq.${enc(TaskStatus.COMPLETE.apiValue)}"
                TaskListFilter.COMPLETED -> "status=eq.${enc(TaskStatus.COMPLETE.apiValue)}"
            }
            val query = "select=id,title,dueDate,status,roadmap&$statusParam&order=dueDate.asc.nullslast"
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
                    val status = TaskStatus.fromApi(
                        when {
                            !obj.has("status") || obj.isNull("status") -> null
                            else -> obj.getString("status")
                        }
                    )
                    val roadmap = parseRoadmap(obj.opt("roadmap"))
                    out.add(TaskRow(id = id, title = title, dueDate = due, status = status, roadmap = roadmap))
                }
                ListResult.Success(out)
            } else {
                ListResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            ListResult.Failure(e.message ?: "Network error.")
        }
    }

    private fun parseDueDate(raw: Any?): LocalDateTime? {
        if (raw == null || raw == JSONObject.NULL) return null
        return TaskDueParsing.parseFlexible(raw.toString().trim())
    }

    private fun parseRoadmap(raw: Any?): JSONArray? {
        if (raw == null || raw == JSONObject.NULL) return null
        return try {
            when (raw) {
                is JSONArray -> raw
                is JSONObject -> raw.optJSONArray("steps")
                else -> {
                    val s = raw.toString().trim()
                    when {
                        s.isEmpty() || s == "null" -> null
                        s.startsWith("[") -> JSONArray(s)
                        s.startsWith("{") -> JSONObject(s).optJSONArray("steps")
                        else -> null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getTask(accessToken: String, taskId: String): GetResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return GetResult.Failure("Missing Supabase config.")
        }
        val idFilter = taskId.trim()
        if (idFilter.isEmpty()) return GetResult.Failure("Missing task id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val query = "select=id,title,dueDate,status,roadmap&id=eq.$idFilter"
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

            if (responseCode !in 200..299) {
                return GetResult.Failure(parseError(responseBody, responseCode))
            }
            val arr = JSONArray(responseBody)
            if (arr.length() == 0) return GetResult.Failure("Task not found.")
            val obj = arr.getJSONObject(0)
            val id = obj.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return GetResult.Failure("Task missing id.")
            val title = obj.optString("title")
            val due = parseDueDate(obj.opt("dueDate"))
            val status = TaskStatus.fromApi(
                when {
                    !obj.has("status") || obj.isNull("status") -> null
                    else -> obj.getString("status")
                }
            )
            val roadmap = parseRoadmap(obj.opt("roadmap"))
            GetResult.Success(TaskRow(id = id, title = title, dueDate = due, status = status, roadmap = roadmap))
        } catch (e: Exception) {
            GetResult.Failure(e.message ?: "Network error.")
        }
    }

    fun insertTask(
        accessToken: String,
        userId: String,
        title: String,
        dueDate: LocalDateTime,
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
                .put("dueDate", TaskDueParsing.toIsoParam(dueDate))
                .put("status", TaskStatus.NOT_STARTED.apiValue)
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
                val row = arr.getJSONObject(0)
                val id = row.opt("id")?.toString()?.takeIf { it.isNotBlank() }
                    ?: return InsertResult.Failure("Missing task id in response.")
                val status = TaskStatus.fromApi(
                    when {
                        !row.has("status") || row.isNull("status") -> null
                        else -> row.getString("status")
                    }
                )
                InsertResult.Success(id = id, status = status)
            } else {
                InsertResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            InsertResult.Failure(e.message ?: "Network error.")
        }
    }

    fun updateTaskStatus(accessToken: String, taskId: String, status: TaskStatus): PatchResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return PatchResult.Failure("Missing Supabase config.")
        }
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val idFilter = taskId.trim()
            if (idFilter.isEmpty()) {
                return PatchResult.Failure("Missing task id.")
            }
            val url = URL("$base/rest/v1/tasks?id=eq.$idFilter")
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

            val payload = JSONObject().put("status", status.apiValue)
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode in 200..299) {
                val trimmed = responseBody.trim()
                if (trimmed == "[]") {
                    return PatchResult.Failure("No row was updated.")
                }
                val confirmed = parseStatusFromPatchResponse(responseBody) ?: status
                PatchResult.Success(confirmed)
            } else {
                PatchResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            PatchResult.Failure(e.message ?: "Network error.")
        }
    }

    private fun parseStatusFromPatchResponse(body: String): TaskStatus? {
        if (body.isBlank()) return null
        return try {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) return null
                    val row = arr.getJSONObject(0)
                    TaskStatus.fromApi(
                        when {
                            !row.has("status") || row.isNull("status") -> null
                            else -> row.getString("status")
                        }
                    )
                }
                trimmed.startsWith("{") -> {
                    val row = JSONObject(trimmed)
                    TaskStatus.fromApi(
                        when {
                            !row.has("status") || row.isNull("status") -> null
                            else -> row.getString("status")
                        }
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun updateTaskDueDate(accessToken: String, taskId: String, dueDate: LocalDateTime?): PatchDueResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return PatchDueResult.Failure("Missing Supabase config.")
        }
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val idFilter = taskId.trim()
            if (idFilter.isEmpty()) {
                return PatchDueResult.Failure("Missing task id.")
            }
            val url = URL("$base/rest/v1/tasks?id=eq.$idFilter")
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

            val payload = JSONObject().apply {
                if (dueDate == null) put("dueDate", JSONObject.NULL) else put("dueDate", TaskDueParsing.toIsoParam(dueDate))
            }
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode in 200..299) {
                val trimmed = responseBody.trim()
                if (trimmed == "[]") {
                    return PatchDueResult.Failure("No row was updated.")
                }
                val confirmed = parseDueFromPatchResponse(responseBody)
                PatchDueResult.Success(confirmed ?: dueDate)
            } else {
                PatchDueResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            PatchDueResult.Failure(e.message ?: "Network error.")
        }
    }

    fun updateTaskRoadmap(accessToken: String, taskId: String, roadmapSteps: JSONArray): PatchRoadmapResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return PatchRoadmapResult.Failure("Missing Supabase config.")
        }
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val idFilter = taskId.trim()
            if (idFilter.isEmpty()) {
                return PatchRoadmapResult.Failure("Missing task id.")
            }
            val url = URL("$base/rest/v1/tasks?id=eq.$idFilter")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Prefer", "return=minimal")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true

            val payload = JSONObject().put("roadmap", roadmapSteps)
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode in 200..299) {
                PatchRoadmapResult.Success
            } else {
                PatchRoadmapResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            PatchRoadmapResult.Failure(e.message ?: "Network error.")
        }
    }

    private fun parseDueFromPatchResponse(body: String): LocalDateTime? {
        if (body.isBlank()) return null
        return try {
            val trimmed = body.trim()
            val rawDue: Any? = when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) return null
                    val row = arr.getJSONObject(0)
                    if (!row.has("dueDate") || row.isNull("dueDate")) null else row.get("dueDate")
                }
                trimmed.startsWith("{") -> {
                    val row = JSONObject(trimmed)
                    if (!row.has("dueDate") || row.isNull("dueDate")) null else row.get("dueDate")
                }
                else -> null
            }
            parseDueDate(rawDue)
        } catch (_: Exception) {
            null
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
