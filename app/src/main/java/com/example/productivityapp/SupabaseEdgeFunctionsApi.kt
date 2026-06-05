package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

object SupabaseEdgeFunctionsApi {

    // IMPORTANT: this must match the deployed Edge Function HTTP slug.
    // In your project, the deployed URL is `/functions/v1/super-handler` even if the local folder is `get_roadmap`.
    private const val PRIMARY_ROADMAP_FUNCTION_SLUG = "super-handler"
    private const val FALLBACK_ROADMAP_FUNCTION_SLUG = "get_roadmap"

    sealed class RoadmapResult {
        data class Success(val steps: JSONArray) : RoadmapResult()
        data class Failure(val message: String) : RoadmapResult()
    }

    sealed class CourseProfileResult {
        data class Success(val courseProfile: String) : CourseProfileResult()
        data class Failure(val message: String) : CourseProfileResult()
    }

    private const val GENERATE_COURSE_PROFILE_SLUG = "generate-course-profile"

    fun getRoadmap(
        accessToken: String,
        title: String,
        dueDateIso: String,
        assignmentType: String?,
        difficulty: String?,
        requirements: String?,
        documentContent: String?,
        photoText: String?,
        courseName: String? = null,
        courseLevel: String? = null,
        courseProfile: String? = null,
        school: String? = null,
        yearInSchool: String? = null,
        userEstimatedHours: Double? = null,
        existingWorkload: Map<String, Double> = emptyMap(),
    ): RoadmapResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return RoadmapResult.Failure("Missing Supabase config.")
        }
        val payload = JSONObject()
            .put("timeZone", ZoneId.systemDefault().id)
            .put("title", title)
            .put("dueDate", dueDateIso)
            .put("assignmentType", assignmentType ?: JSONObject.NULL)
            .put("difficulty", difficulty ?: JSONObject.NULL)
            .put("requirements", requirements ?: JSONObject.NULL)
            .put("documentContent", documentContent ?: JSONObject.NULL)
            .put("photoText", photoText ?: JSONObject.NULL)
            .put("courseName", courseName ?: JSONObject.NULL)
            .put("courseLevel", courseLevel ?: JSONObject.NULL)
            .put("courseProfile", courseProfile ?: JSONObject.NULL)
            .put("school", school?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("yearInSchool", yearInSchool?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        if (userEstimatedHours != null) {
            payload.put("userEstimatedHours", userEstimatedHours)
        } else {
            payload.put("userEstimatedHours", JSONObject.NULL)
        }

        val estimateFeedbackHistory = loadEstimateFeedbackHistory(accessToken)
        payload.put("estimateFeedbackHistory", estimateFeedbackHistory)

        val existingWorkloadJson = JSONObject()
        for ((date, hours) in existingWorkload) {
            existingWorkloadJson.put(date, hours)
        }
        payload.put("existingWorkload", existingWorkloadJson)

        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val primary = callRoadmapFunction(base, PRIMARY_ROADMAP_FUNCTION_SLUG, accessToken, payload)
        if (primary is RoadmapResult.Failure && primary.message.contains("HTTP 404", ignoreCase = true)) {
            val fallback = callRoadmapFunction(base, FALLBACK_ROADMAP_FUNCTION_SLUG, accessToken, payload)
            return if (fallback is RoadmapResult.Failure) {
                RoadmapResult.Failure(
                    primary.message + "\n\nTried fallback `${FALLBACK_ROADMAP_FUNCTION_SLUG}` too:\n" + fallback.message
                )
            } else {
                fallback
            }
        }
        return primary
    }

    fun generateCourseProfile(
        courseName: String,
        courseLevel: String,
        courseSyllabus: String,
    ): CourseProfileResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return CourseProfileResult.Failure("Missing Supabase config.")
        }
        val payload = JSONObject()
            .put("courseName", courseName.trim())
            .put("courseLevel", courseLevel.trim())
            .put("courseSyllabus", courseSyllabus.trim())
        return callGenerateCourseProfileFunction(
            BuildConfig.SUPABASE_URL.trimEnd('/'),
            GENERATE_COURSE_PROFILE_SLUG,
            payload,
        )
    }

    private fun callGenerateCourseProfileFunction(
        base: String,
        slug: String,
        payload: JSONObject,
    ): CourseProfileResult {
        return try {
            val url = URL("$base/functions/v1/$slug")
            Log.d("SupabaseEdgeFunctionsApi", "Calling Edge Function: $url")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 60000
            connection.readTimeout = 60000
            connection.doOutput = true

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                val reason = parseError(responseBody, responseCode)
                val msg = "HTTP $responseCode at $url\n$reason"
                Log.e("SupabaseEdgeFunctionsApi", msg)
                return CourseProfileResult.Failure(msg)
            }

            parseCourseProfile(responseBody) ?: run {
                val snippet = responseBody.trim().take(800)
                CourseProfileResult.Failure(
                    "200 OK at $url but response did not contain courseProfile.\n\nBody snippet:\n$snippet",
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Network error."
            Log.e("SupabaseEdgeFunctionsApi", msg, e)
            CourseProfileResult.Failure(msg)
        }
    }

    private fun parseCourseProfile(body: String): CourseProfileResult.Success? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    val profile = when {
                        obj.has("courseProfile") && !obj.isNull("courseProfile") ->
                            obj.optString("courseProfile")
                        obj.has("data") && !obj.isNull("data") -> {
                            val data = obj.opt("data")
                            when (data) {
                                is JSONObject ->
                                    if (data.has("courseProfile") && !data.isNull("courseProfile")) {
                                        data.optString("courseProfile")
                                    } else {
                                        null
                                    }
                                else -> null
                            }
                        }
                        else -> null
                    }
                    profile?.trim()?.takeIf { it.isNotEmpty() }?.let { CourseProfileResult.Success(it) }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadEstimateFeedbackHistory(accessToken: String): JSONArray {
        val userId = SupabaseUserId.resolveUserId(accessToken) ?: return JSONArray()
        return when (
            val result = SupabaseRoadmapStepEstimateFeedbackApi.listRecentHistory(accessToken, userId, limit = 50)
        ) {
            is SupabaseRoadmapStepEstimateFeedbackApi.HistoryResult.Success -> result.records
            is SupabaseRoadmapStepEstimateFeedbackApi.HistoryResult.Failure -> {
                Log.w("SupabaseEdgeFunctionsApi", "Could not load estimate feedback history: ${result.message}")
                JSONArray()
            }
        }
    }

    private fun callRoadmapFunction(
        base: String,
        slug: String,
        accessToken: String,
        payload: JSONObject,
    ): RoadmapResult {
        return try {
            val url = URL("$base/functions/v1/$slug")
            Log.d("SupabaseEdgeFunctionsApi", "Calling Edge Function: $url")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            // Use anon key as the bearer token to avoid passing non-Supabase JWTs (e.g. Google ES256)
            // into the Edge Functions gateway, which will reject them.
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.doOutput = true

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                val reason = parseError(responseBody, responseCode)
                val msg = "HTTP $responseCode at $url\n$reason"
                Log.e("SupabaseEdgeFunctionsApi", msg)
                return RoadmapResult.Failure(msg)
            }

            val steps = parseSteps(responseBody) ?: run {
                val snippet = responseBody.trim().take(800)
                return RoadmapResult.Failure(
                    "200 OK at $url but response did not contain steps.\n\nBody snippet:\n$snippet"
                )
            }
            RoadmapResult.Success(steps)
        } catch (e: Exception) {
            val msg = e.message ?: "Network error."
            Log.e("SupabaseEdgeFunctionsApi", msg, e)
            RoadmapResult.Failure(msg)
        }
    }

    private fun parseSteps(body: String): JSONArray? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    when {
                        obj.has("steps") && !obj.isNull("steps") -> obj.optJSONArray("steps")
                        obj.has("data") && !obj.isNull("data") -> {
                            val data = obj.opt("data")
                            when (data) {
                                is JSONArray -> data
                                is JSONObject -> data.optJSONArray("steps")
                                else -> null
                            }
                        }
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseError(body: String, code: Int): String {
        return try {
            val root = JSONObject(body)
            root.optString("message")
                .ifBlank { root.optString("error") }
                .ifBlank { root.optString("error_description") }
                .ifBlank { root.optString("hint") }
                .ifBlank { root.optString("details") }
                .ifBlank { "Request failed ($code)" }
        } catch (_: Exception) {
            if (body.isBlank()) "Request failed ($code)" else body
        }
    }
}

