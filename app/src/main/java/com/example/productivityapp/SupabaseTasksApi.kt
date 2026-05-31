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
    /** Tasks that have been soft-deleted (deletedAt is not null). */
    DELETED,
    /**
     * Completed tasks that still have roadmap JSON (non-null).
     * Used with [ACTIVE] on home so “review completed” days still list fully-done tasks.
     */
    COMPLETED_WITH_ROADMAP,
}

/**
 * `tasks` table via PostgREST.
 * Columns: id, userId, title, dueDate, status, roadmap, roadmapConfidence, completedAt, courseId.
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
        val roadmapConfidence: RoadmapConfidence? = null,
        val completedAt: String? = null,
        val courseId: String? = null,
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
        data class Success(val status: TaskStatus, val completedAt: String?) : PatchResult()
        data class Failure(val message: String) : PatchResult()
    }

    sealed class PatchDueResult {
        data class Success(val dueDate: LocalDateTime?) : PatchDueResult()
        data class Failure(val message: String) : PatchDueResult()
    }

    sealed class PatchCourseResult {
        data class Success(val courseId: String?) : PatchCourseResult()
        data class Failure(val message: String) : PatchCourseResult()
    }

    sealed class PatchRoadmapResult {
        object Success : PatchRoadmapResult()
        data class Failure(val message: String) : PatchRoadmapResult()
    }

    sealed class DeleteResult {
        object Success : DeleteResult()
        data class Failure(val message: String) : DeleteResult()
    }

    sealed class ClearCourseIdResult {
        object Success : ClearCourseIdResult()
        data class Failure(val message: String) : ClearCourseIdResult()
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
            val filterParams = when (filter) {
                TaskListFilter.ACTIVE ->
                    "deletedAt=is.null&status=neq.${enc(TaskStatus.COMPLETE.apiValue)}"
                TaskListFilter.COMPLETED ->
                    "deletedAt=is.null&status=eq.${enc(TaskStatus.COMPLETE.apiValue)}"
                TaskListFilter.DELETED ->
                    "deletedAt=not.is.null"
                TaskListFilter.COMPLETED_WITH_ROADMAP ->
                    "deletedAt=is.null&status=eq.${enc(TaskStatus.COMPLETE.apiValue)}&roadmap=not.is.null"
            }
            val orderParam = when (filter) {
                TaskListFilter.DELETED -> "order=deletedAt.desc.nullslast"
                else -> "order=dueDate.asc.nullslast"
            }
            val query = "select=id,title,dueDate,status,roadmap,roadmapConfidence,completedAt,courseId&$filterParams&$orderParam"
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
                    parseTaskRow(arr.getJSONObject(i))?.let { out.add(it) }
                }
                ListResult.Success(out)
            } else {
                ListResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            ListResult.Failure(e.message ?: "Network error.")
        }
    }

    private fun parseCompletedAt(raw: Any?): String? {
        if (raw == null || raw == JSONObject.NULL) return null
        return raw.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun parseTaskRow(obj: JSONObject): TaskRow? {
        val id = obj.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title")
        val due = parseDueDate(obj.opt("dueDate"))
        val status = TaskStatus.fromApi(
            when {
                !obj.has("status") || obj.isNull("status") -> null
                else -> obj.getString("status")
            }
        )
        val roadmap = parseRoadmap(obj.opt("roadmap"))
        val completedAtRaw = when {
            obj.has("completedAt") && !obj.isNull("completedAt") -> obj.get("completedAt")
            obj.has("completed_at") && !obj.isNull("completed_at") -> obj.get("completed_at")
            else -> null
        }
        return TaskRow(
            id = id,
            title = title,
            dueDate = due,
            status = status,
            roadmap = roadmap,
            roadmapConfidence = parseRoadmapConfidence(obj),
            completedAt = parseCompletedAt(completedAtRaw),
            courseId = parseCourseId(obj),
        )
    }

    private fun parseRoadmapConfidence(obj: JSONObject): RoadmapConfidence? {
        val raw = when {
            obj.has("roadmapConfidence") && !obj.isNull("roadmapConfidence") -> obj.get("roadmapConfidence")
            obj.has("roadmap_confidence") && !obj.isNull("roadmap_confidence") -> obj.get("roadmap_confidence")
            else -> null
        }
        return RoadmapConfidence.fromApi(raw?.toString())
    }

    private fun parseCourseId(obj: JSONObject): String? {
        val raw = when {
            obj.has("courseId") && !obj.isNull("courseId") -> obj.get("courseId")
            obj.has("course_id") && !obj.isNull("course_id") -> obj.get("course_id")
            else -> null
        }
        return raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
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

    private fun parseStatusAndCompletedAtFromPatchResponse(body: String): Pair<TaskStatus, String?>? {
        if (body.isBlank()) return null
        return try {
            val trimmed = body.trim()
            val row = when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) return null
                    arr.getJSONObject(0)
                }
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> return null
            }
            val status = TaskStatus.fromApi(
                when {
                    !row.has("status") || row.isNull("status") -> null
                    else -> row.getString("status")
                }
            ) ?: return null
            val completedAtRaw = when {
                row.has("completedAt") && !row.isNull("completedAt") -> row.get("completedAt")
                row.has("completed_at") && !row.isNull("completed_at") -> row.get("completed_at")
                else -> null
            }
            status to parseCompletedAt(completedAtRaw)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStatusFromPatchResponse(body: String): TaskStatus? =
        parseStatusAndCompletedAtFromPatchResponse(body)?.first

    fun getTask(accessToken: String, taskId: String): GetResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return GetResult.Failure("Missing Supabase config.")
        }
        val idFilter = taskId.trim()
        if (idFilter.isEmpty()) return GetResult.Failure("Missing task id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val query = "select=id,title,dueDate,status,roadmap,roadmapConfidence,completedAt,courseId&id=eq.$idFilter"
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
            parseTaskRow(arr.getJSONObject(0))?.let { GetResult.Success(it) }
                ?: GetResult.Failure("Task missing id.")
        } catch (e: Exception) {
            GetResult.Failure(e.message ?: "Network error.")
        }
    }

    fun insertTask(
        accessToken: String,
        userId: String,
        title: String,
        dueDate: LocalDateTime,
        courseId: String? = null,
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
            if (courseId.isNullOrBlank()) {
                payload.put("courseId", JSONObject.NULL)
            } else {
                payload.put("courseId", courseId)
            }

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
            if (status == TaskStatus.COMPLETE) {
                payload.put("completedAt", Instant.now().toString())
            } else {
                payload.put("completedAt", JSONObject.NULL)
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
                    return PatchResult.Failure("No row was updated.")
                }
                val parsed = parseStatusAndCompletedAtFromPatchResponse(responseBody)
                val confirmedStatus = parsed?.first ?: status
                val confirmedCompletedAt = parsed?.second
                    ?: if (status == TaskStatus.COMPLETE) Instant.now().toString() else null
                PatchResult.Success(confirmedStatus, confirmedCompletedAt)
            } else {
                PatchResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            PatchResult.Failure(e.message ?: "Network error.")
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

    fun updateTaskCourseId(accessToken: String, taskId: String, courseId: String?): PatchCourseResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return PatchCourseResult.Failure("Missing Supabase config.")
        }
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val idFilter = taskId.trim()
            if (idFilter.isEmpty()) {
                return PatchCourseResult.Failure("Missing task id.")
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
                if (courseId.isNullOrBlank()) put("courseId", JSONObject.NULL) else put("courseId", courseId)
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
                    return PatchCourseResult.Failure("No row was updated.")
                }
                val confirmed = parseCourseIdFromPatchResponse(responseBody)
                PatchCourseResult.Success(confirmed)
            } else {
                PatchCourseResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            PatchCourseResult.Failure(e.message ?: "Network error.")
        }
    }

    private fun parseCourseIdFromPatchResponse(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val trimmed = body.trim()
            val row = when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) return null
                    arr.getJSONObject(0)
                }
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> return null
            }
            parseCourseId(row)
        } catch (_: Exception) {
            null
        }
    }

    fun updateTaskRoadmap(
        accessToken: String,
        taskId: String,
        roadmapSteps: JSONArray,
        roadmapConfidence: RoadmapConfidence? = null,
    ): PatchRoadmapResult {
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
            if (roadmapConfidence != null) {
                payload.put("roadmapConfidence", roadmapConfidence.apiValue)
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
                PatchRoadmapResult.Success
            } else {
                PatchRoadmapResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            PatchRoadmapResult.Failure(e.message ?: "Network error.")
        }
    }

    fun updateRoadmapConfidence(
        accessToken: String,
        taskId: String,
        roadmapConfidence: RoadmapConfidence,
    ): PatchRoadmapResult {
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

            val payload = JSONObject().put("roadmapConfidence", roadmapConfidence.apiValue)
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

    /** Sets `courseId` to null on all tasks referencing [courseId]. */
    fun clearCourseIdOnTasks(accessToken: String, courseId: String): ClearCourseIdResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return ClearCourseIdResult.Failure("Missing Supabase config.")
        }
        val idFilter = courseId.trim()
        if (idFilter.isEmpty()) return ClearCourseIdResult.Failure("Missing course id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = URLEncoder.encode(idFilter, StandardCharsets.UTF_8.name())
            val url = URL("$base/rest/v1/tasks?courseId=eq.$enc")
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

            val payload = JSONObject().put("courseId", JSONObject.NULL)
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode in 200..299) {
                ClearCourseIdResult.Success
            } else {
                ClearCourseIdResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            ClearCourseIdResult.Failure(e.message ?: "Network error.")
        }
    }

    /**
     * Soft delete: set `deletedAt` to an ISO-8601 timestamptz string (UTC, includes timezone).
     */
    fun deleteTask(accessToken: String, taskId: String): DeleteResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return DeleteResult.Failure("Missing Supabase config.")
        }
        val idFilter = taskId.trim()
        if (idFilter.isEmpty()) return DeleteResult.Failure("Missing task id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = URLEncoder.encode(idFilter, StandardCharsets.UTF_8.name())
            val url = URL("$base/rest/v1/tasks?id=eq.$enc")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            // So we can tell “no row matched / RLS hid the row” from a real update (PostgREST returns []).
            connection.setRequestProperty("Prefer", "return=representation")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true

            val payload = JSONObject().put("deletedAt", Instant.now().toString())
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                DeleteResult.Failure(parseError(responseBody, responseCode))
            } else {
                val trimmed = responseBody.trim()
                when {
                    trimmed.startsWith("[") -> {
                        val arr = JSONArray(trimmed)
                        when (arr.length()) {
                            0 -> DeleteResult.Failure(
                                "No row was updated. Check that your Supabase RLS policy allows UPDATE on tasks you own.",
                            )
                            else -> DeleteResult.Success
                        }
                    }
                    // 204 No Content or unexpected empty success — treat as success for compatibility.
                    trimmed.isEmpty() -> DeleteResult.Success
                    else -> DeleteResult.Success
                }
            }
        } catch (e: Exception) {
            DeleteResult.Failure(e.message ?: "Network error.")
        }
    }
}
