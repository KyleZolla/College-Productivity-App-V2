package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId

/**
 * `roadmap_step_estimate_feedback` via PostgREST.
 * Unique on (`user_id`, `task_id`, `step_index`).
 */
object SupabaseRoadmapStepEstimateFeedbackApi {

    sealed class UpsertResult {
        object Success : UpsertResult()
        data class Failure(val message: String) : UpsertResult()
    }

    sealed class ListResult {
        data class Success(val answeredKeys: Set<String>) : ListResult()
        data class Failure(val message: String) : ListResult()
    }

    sealed class HistoryResult {
        data class Success(val records: JSONArray) : HistoryResult()
        data class Failure(val message: String) : HistoryResult()
    }

    /** Most recent feedback rows for [userId], ordered by `created_at` descending. */
    fun listRecentHistory(
        accessToken: String,
        userId: String,
        limit: Int = 50,
    ): HistoryResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return HistoryResult.Failure("Missing Supabase config.")
        }
        val id = userId.trim()
        if (id.isEmpty()) return HistoryResult.Failure("Missing user id.")
        val cappedLimit = limit.coerceIn(1, 50)
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
            val query = "user_id=eq.$enc&order=created_at.desc&limit=$cappedLimit"
            val url = URL("$base/rest/v1/roadmap_step_estimate_feedback?$query")
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
                return HistoryResult.Failure(parseError(responseBody, responseCode))
            }
            HistoryResult.Success(JSONArray(responseBody))
        } catch (e: Exception) {
            HistoryResult.Failure(e.message ?: "Network error.")
        }
    }

    /** Keys are `taskId:stepIndex`. */
    fun listAnsweredKeys(accessToken: String, userId: String): ListResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return ListResult.Failure("Missing Supabase config.")
        }
        val id = userId.trim()
        if (id.isEmpty()) return ListResult.Failure("Missing user id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
            val query = "select=task_id,step_index&user_id=eq.$enc"
            val url = URL("$base/rest/v1/roadmap_step_estimate_feedback?$query")
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
                return ListResult.Failure(parseError(responseBody, responseCode))
            }

            val arr = JSONArray(responseBody)
            val keys = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val taskId = when {
                    obj.has("task_id") && !obj.isNull("task_id") -> obj.optString("task_id")
                    obj.has("taskId") && !obj.isNull("taskId") -> obj.optString("taskId")
                    else -> ""
                }.trim()
                val stepIndex = when {
                    obj.has("step_index") && !obj.isNull("step_index") -> obj.optInt("step_index", -1)
                    obj.has("stepIndex") && !obj.isNull("stepIndex") -> obj.optInt("stepIndex", -1)
                    else -> -1
                }
                if (taskId.isNotEmpty() && stepIndex >= 0) {
                    keys.add("$taskId:$stepIndex")
                }
            }
            ListResult.Success(keys)
        } catch (e: Exception) {
            ListResult.Failure(e.message ?: "Network error.")
        }
    }

    fun upsert(
        accessToken: String,
        userId: String,
        task: SupabaseTasksApi.TaskRow,
        stepIndex: Int,
        step: RoadmapStep,
        feedback: String,
    ): UpsertResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return UpsertResult.Failure("Missing Supabase config.")
        }
        val normalizedFeedback = EstimateFeedback.normalize(feedback)
            ?: return UpsertResult.Failure("Invalid feedback.")
        val uid = userId.trim()
        val tid = task.id.trim()
        if (uid.isEmpty() || tid.isEmpty()) {
            return UpsertResult.Failure("Missing user or task id.")
        }
        if (stepIndex < 0) return UpsertResult.Failure("Invalid step index.")

        val taskContext = fetchTaskTypeAndDifficulty(accessToken, tid)
        val courseContext = fetchCourseNameAndLevel(accessToken, task.courseId)

        val completedAt = Instant.now()
        val zone = ZoneId.systemDefault()
        val completedDate = completedAt.atZone(zone).toLocalDate()
        val scheduledDate = RoadmapStep.recommendedLocalDate(step)
        val wasCompletedLate = scheduledDate != null && completedDate.isAfter(scheduledDate)

        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val url = URL(
                "$base/rest/v1/roadmap_step_estimate_feedback?on_conflict=user_id,task_id,step_index",
            )
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Prefer", "return=minimal,resolution=merge-duplicates")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true

            val payload = JSONObject()
                .put("user_id", uid)
                .put("task_id", tid)
                .put(
                    "course_id",
                    task.courseId?.takeIf { it.isNotBlank() } ?: JSONObject.NULL,
                )
                .put(
                    "course_name",
                    courseContext.name?.takeIf { it.isNotBlank() } ?: JSONObject.NULL,
                )
                .put(
                    "course_level",
                    courseContext.level?.takeIf { it.isNotBlank() } ?: JSONObject.NULL,
                )
                .put("step_index", stepIndex)
                .put("step_title", step.title)
                .put(
                    "task_title",
                    task.title.trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL,
                )
                .put(
                    "task_type",
                    taskContext.taskType?.takeIf { it.isNotBlank() } ?: JSONObject.NULL,
                )
                .put(
                    "difficulty",
                    taskContext.difficulty?.takeIf { it.isNotBlank() } ?: JSONObject.NULL,
                )
                .put(
                    "estimated_hours",
                    step.estimatedHours?.takeIf { !it.isNaN() } ?: JSONObject.NULL,
                )
                .put("feedback", normalizedFeedback)
                .put(
                    "scheduled_date",
                    scheduledDate?.toString() ?: JSONObject.NULL,
                )
                .put("completed_at", completedAt.toString())
                .put("was_completed_late", wasCompletedLate)

            val body = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode in 200..299) {
                UpsertResult.Success
            } else {
                UpsertResult.Failure(parseError(responseBody, responseCode))
            }
        } catch (e: Exception) {
            UpsertResult.Failure(e.message ?: "Network error.")
        }
    }

    private data class TaskFeedbackContext(val taskType: String?, val difficulty: String?)

    private data class CourseFeedbackContext(val name: String?, val level: String?)

    private fun fetchCourseNameAndLevel(accessToken: String, courseId: String?): CourseFeedbackContext {
        val id = courseId?.trim().orEmpty()
        if (id.isEmpty()) return CourseFeedbackContext(null, null)
        return when (val result = SupabaseCoursesApi.getCourse(accessToken, id)) {
            is SupabaseCoursesApi.GetResult.Success -> CourseFeedbackContext(
                name = result.course.name,
                level = result.course.level,
            )
            is SupabaseCoursesApi.GetResult.Failure -> CourseFeedbackContext(null, null)
        }
    }

    /** Best-effort; returns nulls when optional task columns are absent or unreadable. */
    private fun fetchTaskTypeAndDifficulty(accessToken: String, taskId: String): TaskFeedbackContext {
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = URLEncoder.encode(taskId.trim(), StandardCharsets.UTF_8.name())
            val query = "select=assignmentType,taskType,difficulty&id=eq.$enc"
            val url = URL("$base/rest/v1/tasks?$query")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return TaskFeedbackContext(null, null)

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(responseBody)
            if (arr.length() == 0) return TaskFeedbackContext(null, null)
            val obj = arr.getJSONObject(0)
            val type = sequenceOf("assignmentType", "assignment_type", "taskType", "task_type", "type")
                .mapNotNull { key ->
                    if (!obj.has(key) || obj.isNull(key)) null
                    else obj.optString(key).trim().takeIf { it.isNotEmpty() }
                }
                .firstOrNull()
            val diff = if (!obj.has("difficulty") || obj.isNull("difficulty")) {
                null
            } else {
                obj.optString("difficulty").trim().takeIf { it.isNotEmpty() }
            }
            TaskFeedbackContext(type, diff)
        } catch (_: Exception) {
            TaskFeedbackContext(null, null)
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
