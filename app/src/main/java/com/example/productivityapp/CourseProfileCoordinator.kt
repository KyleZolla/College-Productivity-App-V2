package com.example.productivityapp

/**
 * Generates or clears [courses.course_profile] after a successful course save.
 */
object CourseProfileCoordinator {

    sealed class SyncResult {
        /** No profile work was needed (empty/unchanged syllabus). */
        object Skipped : SyncResult()

        /** Profile was generated or cleared successfully. */
        object Success : SyncResult()

        /**
         * Course row is saved, but the free syllabus-profile limit was reached so no AI
         * profile was generated. Not an error — the course exists without AI analysis.
         * [message] is the server-provided user-facing text, or null to use the app default.
         */
        data class LimitReached(val message: String?) : SyncResult()

        /** Course row is saved; profile sync failed. */
        data class Failure(val message: String) : SyncResult()
    }

    fun syncAfterSave(
        accessToken: String,
        courseId: String,
        courseName: String,
        courseLevel: String,
        savedSyllabus: String?,
        previousSyllabus: String?,
        isEdit: Boolean,
    ): SyncResult {
        val saved = normalizeSyllabus(savedSyllabus)
        val previous = normalizeSyllabus(previousSyllabus)

        if (isEdit) {
            if (saved == previous) return SyncResult.Skipped
            if (saved == null) {
                return when (
                    val clear = SupabaseCoursesApi.clearCourseProfile(accessToken, courseId)
                ) {
                    is SupabaseCoursesApi.UpdateProfileResult.Success -> SyncResult.Success
                    is SupabaseCoursesApi.UpdateProfileResult.Failure ->
                        SyncResult.Failure(clear.message)
                }
            }
        } else if (saved == null) {
            return SyncResult.Skipped
        }

        val syllabus = saved ?: return SyncResult.Skipped

        // Free MVP check: skip the course-profile Edge Function once the rolling-window
        // limit is reached. The course is already saved; we just leave it without an
        // AI-generated profile. (The Edge Function also enforces this server-side.)
        val userId = SupabaseUserId.resolveUserId(accessToken)
        if (userId != null && SupabaseAiUsageApi.isSyllabusProfileLimitReached(accessToken, userId)) {
            return SyncResult.LimitReached(message = null)
        }

        return when (
            val generated = SupabaseEdgeFunctionsApi.generateCourseProfile(
                accessToken = accessToken,
                courseName = courseName,
                courseLevel = courseLevel,
                courseSyllabus = syllabus,
            )
        ) {
            is SupabaseEdgeFunctionsApi.CourseProfileResult.Success -> when (
                val update = SupabaseCoursesApi.setCourseProfile(
                    accessToken = accessToken,
                    courseId = courseId,
                    courseProfile = generated.courseProfile,
                )
            ) {
                is SupabaseCoursesApi.UpdateProfileResult.Success -> SyncResult.Success
                is SupabaseCoursesApi.UpdateProfileResult.Failure ->
                    SyncResult.Failure(update.message)
            }
            is SupabaseEdgeFunctionsApi.CourseProfileResult.LimitReached ->
                SyncResult.LimitReached(message = generated.message)
            is SupabaseEdgeFunctionsApi.CourseProfileResult.Failure ->
                SyncResult.Failure(generated.message)
        }
    }

    private fun normalizeSyllabus(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}
