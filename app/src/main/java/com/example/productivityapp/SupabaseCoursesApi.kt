package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * `courses` table via PostgREST.
 * Columns: id, userId, name, level, syllabus, created_at.
 */
object SupabaseCoursesApi {

    data class CourseRow(
        val id: String,
        val name: String,
        val level: String,
        val syllabus: String? = null,
    )

    sealed class ListResult {
        data class Success(val courses: List<CourseRow>) : ListResult()
        data class Failure(val message: String) : ListResult()
    }

    sealed class InsertResult {
        data class Success(val course: CourseRow) : InsertResult()
        data class Failure(val message: String) : InsertResult()
    }

    sealed class DeleteResult {
        object Success : DeleteResult()
        data class Failure(val message: String) : DeleteResult()
    }

    sealed class GetResult {
        data class Success(val course: CourseRow) : GetResult()
        data class Failure(val message: String) : GetResult()
    }

    fun getCourse(accessToken: String, courseId: String): GetResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return GetResult.Failure("Missing Supabase config.")
        }
        val id = courseId.trim()
        if (id.isEmpty()) return GetResult.Failure("Missing course id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
            val query = "select=id,name,level,syllabus&id=eq.$enc"
            val url = URL("$base/rest/v1/courses?$query")
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
            if (arr.length() == 0) return GetResult.Failure("Course not found.")
            parseCourseRow(arr.getJSONObject(0))?.let { GetResult.Success(it) }
                ?: GetResult.Failure("Course missing id.")
        } catch (e: Exception) {
            GetResult.Failure(e.message ?: "Network error.")
        }
    }

    fun listCourses(accessToken: String, userId: String): ListResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return ListResult.Failure("Missing Supabase config.")
        }
        val uid = userId.trim()
        if (uid.isEmpty()) return ListResult.Failure("Missing user id.")
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = URLEncoder.encode(uid, StandardCharsets.UTF_8.name())
            val query = "select=id,name,level,syllabus&userId=eq.$enc&order=name.asc"
            val url = URL("$base/rest/v1/courses?$query")
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
            val out = ArrayList<CourseRow>(arr.length())
            for (i in 0 until arr.length()) {
                parseCourseRow(arr.getJSONObject(i))?.let { out.add(it) }
            }
            ListResult.Success(out)
        } catch (e: Exception) {
            ListResult.Failure(e.message ?: "Network error.")
        }
    }

    fun insertCourse(
        accessToken: String,
        userId: String,
        name: String,
        level: String,
        syllabus: String?,
    ): InsertResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return InsertResult.Failure("Missing Supabase config.")
        }
        val uid = userId.trim()
        if (uid.isEmpty()) return InsertResult.Failure("Missing user id.")
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return InsertResult.Failure("Course name is required.")
        val trimmedLevel = level.trim()
        if (trimmedLevel.isEmpty()) return InsertResult.Failure("Course level is required.")
        return try {
            val url = URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/courses")
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
                .put("userId", uid)
                .put("name", trimmedName)
                .put("level", trimmedLevel)
                .put("created_at", Instant.now().toString())
            if (syllabus.isNullOrBlank()) {
                payload.put("syllabus", JSONObject.NULL)
            } else {
                payload.put("syllabus", syllabus)
            }

            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                return InsertResult.Failure(parseError(responseBody, responseCode))
            }
            val arr = JSONArray(responseBody)
            if (arr.length() == 0) {
                return InsertResult.Failure("No row returned from database.")
            }
            parseCourseRow(arr.getJSONObject(0))?.let { InsertResult.Success(it) }
                ?: InsertResult.Failure("Missing course id in response.")
        } catch (e: Exception) {
            InsertResult.Failure(e.message ?: "Network error.")
        }
    }

    /**
     * Clears [courseId] on related tasks, then removes the course row.
     */
    fun deleteCourse(accessToken: String, courseId: String): DeleteResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return DeleteResult.Failure("Missing Supabase config.")
        }
        val id = courseId.trim()
        if (id.isEmpty()) return DeleteResult.Failure("Missing course id.")

        when (val clear = SupabaseTasksApi.clearCourseIdOnTasks(accessToken, id)) {
            is SupabaseTasksApi.ClearCourseIdResult.Failure ->
                return DeleteResult.Failure(clear.message)
            is SupabaseTasksApi.ClearCourseIdResult.Success -> Unit
        }

        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val enc = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
            val url = URL("$base/rest/v1/courses?id=eq.$enc")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                return DeleteResult.Failure(parseError(responseBody, responseCode))
            }
            val trimmed = responseBody.trim()
            when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) {
                        DeleteResult.Failure(
                            "No row was deleted. Check that your Supabase RLS policy allows DELETE on courses you own.",
                        )
                    } else {
                        DeleteResult.Success
                    }
                }
                trimmed.isEmpty() -> DeleteResult.Success
                else -> DeleteResult.Success
            }
        } catch (e: Exception) {
            DeleteResult.Failure(e.message ?: "Network error.")
        }
    }

    private fun parseCourseRow(obj: JSONObject): CourseRow? {
        val id = obj.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val name = obj.optString("name")
        val level = obj.optString("level")
        val syllabusRaw = when {
            obj.has("syllabus") && !obj.isNull("syllabus") -> obj.optString("syllabus")
            else -> null
        }
        val syllabus = syllabusRaw?.trim()?.takeIf { it.isNotEmpty() }
        return CourseRow(id = id, name = name, level = level, syllabus = syllabus)
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
