package com.example.productivityapp

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs course and task creation after the user leaves the create screen.
 * Uses an app-scoped executor so work is not cancelled when the activity finishes.
 */
object BackgroundCreateJobs {

    private const val LOG_TAG = "BackgroundCreateJobs"

    interface Listener {
        fun onCourseCreateSucceeded(profileSyncFailed: Boolean) {}
        fun onCourseCreateFailed(message: String) {}
        fun onCourseCreateNotice(message: String) {}
        fun onTaskCreateSucceeded() {}
        fun onTaskCreateFailed(message: String) {}
        fun onTaskCreateNotice(title: String, message: String) {}
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun enqueueCourseCreate(
        context: Context,
        accessToken: String,
        userId: String,
        name: String,
        level: String,
        docUri: Uri?,
        photoUri: Uri?,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val extractedSyllabus = extractSyllabusText(appContext, docUri, photoUri)
            when (
                val insert = SupabaseCoursesApi.insertCourse(
                    accessToken = accessToken,
                    userId = userId,
                    name = name,
                    level = level,
                    syllabus = extractedSyllabus,
                )
            ) {
                is SupabaseCoursesApi.InsertResult.Failure ->
                    notifyCourseCreateFailed(insert.message)
                is SupabaseCoursesApi.InsertResult.Success -> {
                    val savedCourse = insert.course
                    val profileSync = CourseProfileCoordinator.syncAfterSave(
                        accessToken = accessToken,
                        courseId = savedCourse.id,
                        courseName = savedCourse.name,
                        courseLevel = savedCourse.level,
                        savedSyllabus = savedCourse.syllabus,
                        previousSyllabus = null,
                        isEdit = false,
                    )
                    val profileSyncFailed = profileSync is CourseProfileCoordinator.SyncResult.Failure
                    val profileLimit =
                        profileSync as? CourseProfileCoordinator.SyncResult.LimitReached
                    AppEventsApi.logAppEvent(
                        accessToken = accessToken,
                        eventName = "course_created",
                        courseId = savedCourse.id,
                        metadata = JSONObject()
                            .put("has_syllabus", !savedCourse.syllabus.isNullOrBlank())
                            .put("has_document", docUri != null)
                            .put("has_photo", photoUri != null)
                            .put("level", savedCourse.level)
                            .put("course_profile_sync_failed", profileSyncFailed)
                            .put("syllabus_profile_limit_reached", profileLimit != null),
                    )
                    notifyCourseCreateSucceeded(profileSyncFailed)
                    if (profileLimit != null) {
                        notifyCourseCreateNotice(
                            profileLimit.message?.takeIf { it.isNotBlank() }
                                ?: appContext.getString(R.string.ai_syllabus_limit_reached),
                        )
                    }
                }
            }
        }
    }

    fun enqueueTaskCreate(
        context: Context,
        accessToken: String,
        userId: String,
        title: String,
        due: LocalDateTime,
        selectedCourseId: String?,
        creatingComplex: Boolean,
        assignmentType: String?,
        difficulty: String?,
        requirements: String?,
        userEstimatedHours: Double?,
        selectedDocUri: Uri?,
        selectedPhotoUri: Uri?,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            when (
                val insertResult = SupabaseTasksApi.insertTask(
                    accessToken,
                    userId,
                    title,
                    due,
                    selectedCourseId,
                )
            ) {
                is SupabaseTasksApi.InsertResult.Failure ->
                    notifyTaskCreateFailed(insertResult.message)
                is SupabaseTasksApi.InsertResult.Success -> {
                    if (!creatingComplex) {
                        val simpleTaskId = insertResult.id
                        AppEventsApi.logAppEvent(
                            accessToken = accessToken,
                            eventName = "task_created",
                            taskId = simpleTaskId,
                            courseId = selectedCourseId,
                            metadata = JSONObject()
                                .put("task_kind", "simple")
                                .put("has_course", !selectedCourseId.isNullOrBlank())
                                .put("has_due_date", true)
                                .put("has_roadmap", false),
                        )
                        AppEventsApi.logAppEvent(
                            accessToken = accessToken,
                            eventName = "simple_task_created",
                            taskId = simpleTaskId,
                            courseId = selectedCourseId,
                        )
                        notifyTaskCreateSucceeded()
                        return@execute
                    }

                    val taskId = insertResult.id
                    val dueIso = TaskDueParsing.toIsoParam(due)

                    // Free MVP check: skip the roadmap Edge Function once the rolling-window
                    // limit is reached. The task is still created; the user can use it as a
                    // simple task. (The Edge Function also enforces this server-side.)
                    if (SupabaseAiUsageApi.isComplexRoadmapLimitReached(accessToken, userId)) {
                        AppEventsApi.logAppEvent(
                            accessToken = accessToken,
                            eventName = "task_created",
                            taskId = taskId,
                            courseId = selectedCourseId,
                            metadata = JSONObject()
                                .put("task_kind", "complex")
                                .put("has_course", !selectedCourseId.isNullOrBlank())
                                .put("has_due_date", true)
                                .put("has_roadmap", false)
                                .put("ai_roadmap_limit_reached", true),
                        )
                        AppEventsApi.logAppEvent(
                            accessToken = accessToken,
                            eventName = "complex_task_created",
                            taskId = taskId,
                            courseId = selectedCourseId,
                        )
                        notifyTaskCreateNotice(
                            title = "",
                            message = appContext.getString(R.string.ai_roadmap_limit_reached),
                        )
                        notifyTaskCreateSucceeded()
                        return@execute
                    }

                    val documentContent = selectedDocUri?.let { uri ->
                        when (val res = DocumentContentExtractor.extractText(appContext, uri)) {
                            is DocumentContentExtractor.Result.Success -> res.content
                            is DocumentContentExtractor.Result.Failure -> {
                                Log.w(LOG_TAG, "Could not read document.\n${res.message}")
                                null
                            }
                        }
                    }

                    val photoText = selectedPhotoUri?.let { uri ->
                        when (val res = PhotoTextExtractor.extractText(appContext, uri)) {
                            is PhotoTextExtractor.Result.Success -> res.text
                            is PhotoTextExtractor.Result.Failure -> {
                                Log.w(LOG_TAG, "Could not read text from photo.\n${res.message}")
                                null
                            }
                        }
                    }

                    val courseContext = resolveCourseContextForRoadmap(accessToken, selectedCourseId)
                    val profileContext = resolveProfileContextForRoadmap(accessToken)

                    val roadmapConfidence = RoadmapConfidenceCalculator.calculate(
                        RoadmapConfidenceInput(
                            documentContent = documentContent,
                            photoText = photoText,
                            requirements = requirements,
                            userEstimatedHours = userEstimatedHours,
                            courseSelected = !selectedCourseId.isNullOrBlank(),
                        ),
                    )

                    val today = LocalDate.now(ZoneId.systemDefault())
                    val dueDate = due.toLocalDate()
                    val existingWorkload = ExistingWorkload.loadForRange(accessToken, today, dueDate)

                    var roadmapFailureMessage: String? = null
                    var roadmapFailureKind = SupabaseEdgeFunctionsApi.RoadmapResult.FailureKind.GENERATION
                    var roadmapTotalEstimatedHours: Double? = null
                    val roadmapSteps = when (
                        val roadmapResult = SupabaseEdgeFunctionsApi.getRoadmap(
                            accessToken = accessToken,
                            title = title,
                            dueDateIso = dueIso,
                            assignmentType = assignmentType,
                            difficulty = difficulty,
                            requirements = requirements,
                            documentContent = documentContent,
                            photoText = photoText,
                            courseName = courseContext.first,
                            courseLevel = courseContext.second,
                            courseProfile = courseContext.third,
                            school = profileContext.first,
                            yearInSchool = profileContext.second,
                            userEstimatedHours = userEstimatedHours,
                            existingWorkload = existingWorkload,
                        )
                    ) {
                        is SupabaseEdgeFunctionsApi.RoadmapResult.Success -> {
                            roadmapTotalEstimatedHours = roadmapResult.totalEstimatedHours
                            roadmapResult.steps
                        }
                        is SupabaseEdgeFunctionsApi.RoadmapResult.Failure -> {
                            Log.e(LOG_TAG, "Roadmap generation failed.\n${roadmapResult.message}")
                            roadmapFailureMessage = roadmapResult.message
                            roadmapFailureKind = roadmapResult.kind
                            JSONArray()
                        }
                        is SupabaseEdgeFunctionsApi.RoadmapResult.LimitReached -> {
                            // Server reported the free AI limit. The task already exists; we keep it
                            // without a roadmap and show the server message instead of the generic
                            // roadmap-failure message. We do not save a broken/empty roadmap.
                            Log.w(LOG_TAG, "Roadmap skipped: AI limit reached.")
                            val limitMessage = roadmapResult.message.ifBlank {
                                appContext.getString(R.string.ai_roadmap_limit_reached)
                            }
                            AppEventsApi.logAppEvent(
                                accessToken = accessToken,
                                eventName = "task_created",
                                taskId = taskId,
                                courseId = selectedCourseId,
                                metadata = JSONObject()
                                    .put("task_kind", "complex")
                                    .put("has_course", !selectedCourseId.isNullOrBlank())
                                    .put("has_due_date", true)
                                    .put("has_roadmap", false)
                                    .put("ai_roadmap_limit_reached", true),
                            )
                            AppEventsApi.logAppEvent(
                                accessToken = accessToken,
                                eventName = "complex_task_created",
                                taskId = taskId,
                                courseId = selectedCourseId,
                            )
                            notifyTaskCreateNotice(title = "", message = limitMessage)
                            notifyTaskCreateSucceeded()
                            return@execute
                        }
                    }

                    val hasDocument = !documentContent.isNullOrBlank()
                    val hasPhoto = !photoText.isNullOrBlank()
                    val hasRequirements = !requirements.isNullOrBlank()
                    val hasCourseProfile = !courseContext.third.isNullOrBlank()
                    var roadmapSaved = false

                    if (roadmapSteps.length() == 0) {
                        when (
                            val confidenceResult = SupabaseTasksApi.updateRoadmapConfidence(
                                accessToken = accessToken,
                                taskId = taskId,
                                roadmapConfidence = roadmapConfidence,
                            )
                        ) {
                            is SupabaseTasksApi.PatchRoadmapResult.Failure -> Log.w(
                                LOG_TAG,
                                "Could not save roadmap confidence.\n${confidenceResult.message}",
                            )
                            SupabaseTasksApi.PatchRoadmapResult.Success -> Unit
                        }
                        val errorType = when (roadmapFailureKind) {
                            SupabaseEdgeFunctionsApi.RoadmapResult.FailureKind.INCOMPLETE_RESPONSE ->
                                "incomplete_response"
                            SupabaseEdgeFunctionsApi.RoadmapResult.FailureKind.GENERATION ->
                                if (roadmapFailureMessage != null) "generation_failed" else "empty_roadmap"
                        }
                        AppEventsApi.logAppEvent(
                            accessToken = accessToken,
                            eventName = "roadmap_generation_failed",
                            taskId = taskId,
                            courseId = selectedCourseId,
                            metadata = JSONObject()
                                .put("error_type", errorType)
                                .put("error_message", AppEventsApi.shortenError(roadmapFailureMessage)),
                        )
                        val noticeMessage = when (roadmapFailureKind) {
                            SupabaseEdgeFunctionsApi.RoadmapResult.FailureKind.INCOMPLETE_RESPONSE ->
                                appContext.getString(R.string.roadmap_response_incomplete)
                            SupabaseEdgeFunctionsApi.RoadmapResult.FailureKind.GENERATION ->
                                appContext.getString(R.string.roadmap_generation_failed)
                        }
                        notifyTaskCreateNotice(
                            title = "",
                            message = noticeMessage,
                        )
                    } else {
                        when (
                            val patchResult = SupabaseTasksApi.updateTaskRoadmap(
                                accessToken = accessToken,
                                taskId = taskId,
                                roadmapSteps = roadmapSteps,
                                roadmapConfidence = roadmapConfidence,
                            )
                        ) {
                            is SupabaseTasksApi.PatchRoadmapResult.Failure -> {
                                Log.e(LOG_TAG, "Could not save roadmap.\n${patchResult.message}")
                                notifyTaskCreateNotice(
                                    title = "",
                                    message = appContext.getString(R.string.roadmap_generation_failed),
                                )
                                AppEventsApi.logAppEvent(
                                    accessToken = accessToken,
                                    eventName = "roadmap_generation_failed",
                                    taskId = taskId,
                                    courseId = selectedCourseId,
                                    metadata = JSONObject()
                                        .put("error_type", "save_failed")
                                        .put("error_message", AppEventsApi.shortenError(patchResult.message)),
                                )
                            }
                            SupabaseTasksApi.PatchRoadmapResult.Success -> {
                                roadmapSaved = true
                                Log.d(LOG_TAG, "Roadmap saved for taskId=$taskId steps=${roadmapSteps.length()}")
                                val totalEstimatedHours = roadmapTotalEstimatedHours
                                    ?: RoadmapStep.parseList(roadmapSteps).sumOf { it.estimatedHours ?: 0.0 }
                                AppEventsApi.logAppEvent(
                                    accessToken = accessToken,
                                    eventName = "roadmap_generated",
                                    taskId = taskId,
                                    courseId = selectedCourseId,
                                    metadata = JSONObject()
                                        .put("step_count", roadmapSteps.length())
                                        .put("total_estimated_hours", totalEstimatedHours)
                                        .put("roadmap_confidence", roadmapConfidence.apiValue)
                                        .put("has_document", hasDocument)
                                        .put("has_photo", hasPhoto)
                                        .put("has_requirements", hasRequirements)
                                        .put("has_course_profile", hasCourseProfile),
                                )
                            }
                        }
                    }

                    AppEventsApi.logAppEvent(
                        accessToken = accessToken,
                        eventName = "task_created",
                        taskId = taskId,
                        courseId = selectedCourseId,
                        metadata = JSONObject()
                            .put("task_kind", "complex")
                            .put("has_course", !selectedCourseId.isNullOrBlank())
                            .put("has_due_date", true)
                            .put("has_roadmap", roadmapSaved),
                    )
                    AppEventsApi.logAppEvent(
                        accessToken = accessToken,
                        eventName = "complex_task_created",
                        taskId = taskId,
                        courseId = selectedCourseId,
                    )

                    notifyTaskCreateSucceeded()
                }
            }
        }
    }

    private fun extractSyllabusText(context: Context, docUri: Uri?, photoUri: Uri?): String? {
        if (docUri == null && photoUri == null) return null
        val parts = ArrayList<String>(2)
        docUri?.let { uri ->
            when (val res = DocumentContentExtractor.extractText(context, uri)) {
                is DocumentContentExtractor.Result.Success -> parts.add(res.content)
                is DocumentContentExtractor.Result.Failure ->
                    Log.w(LOG_TAG, "Syllabus document skipped.\n${res.message}")
            }
        }
        photoUri?.let { uri ->
            when (val res = PhotoTextExtractor.extractText(context, uri)) {
                is PhotoTextExtractor.Result.Success -> parts.add(res.text)
                is PhotoTextExtractor.Result.Failure ->
                    Log.w(LOG_TAG, "Syllabus photo skipped.\n${res.message}")
            }
        }
        return parts.joinToString("\n\n").trim().takeIf { it.isNotEmpty() }
    }

    private fun resolveProfileContextForRoadmap(
        accessToken: String,
    ): Pair<String?, String?> {
        val userId = SupabaseUserId.resolveUserId(accessToken) ?: return Pair(null, null)
        return when (val result = SupabaseProfilesApi.get(accessToken, userId)) {
            is SupabaseProfilesApi.GetResult.Success -> Pair(result.row.school, result.row.yearInSchool)
            is SupabaseProfilesApi.GetResult.NotFound,
            is SupabaseProfilesApi.GetResult.Failure,
            -> {
                if (result is SupabaseProfilesApi.GetResult.Failure) {
                    Log.w(LOG_TAG, "Could not load profile for roadmap.\n${result.message}")
                }
                Pair(null, null)
            }
        }
    }

    private fun resolveCourseContextForRoadmap(
        accessToken: String,
        courseId: String?,
    ): Triple<String?, String?, String?> {
        if (courseId.isNullOrBlank()) return Triple(null, null, null)
        return when (val result = SupabaseCoursesApi.getCourse(accessToken, courseId)) {
            is SupabaseCoursesApi.GetResult.Success -> Triple(
                result.course.name,
                result.course.level,
                result.course.courseProfile,
            )
            is SupabaseCoursesApi.GetResult.Failure -> {
                Log.w(LOG_TAG, "Could not load course for roadmap.\n${result.message}")
                Triple(null, null, null)
            }
        }
    }

    private fun notifyCourseCreateSucceeded(profileSyncFailed: Boolean) {
        mainHandler.post {
            listeners.forEach { it.onCourseCreateSucceeded(profileSyncFailed) }
        }
    }

    private fun notifyCourseCreateFailed(message: String) {
        mainHandler.post {
            listeners.forEach { it.onCourseCreateFailed(message) }
        }
    }

    private fun notifyCourseCreateNotice(message: String) {
        mainHandler.post {
            listeners.forEach { it.onCourseCreateNotice(message) }
        }
    }

    private fun notifyTaskCreateSucceeded() {
        mainHandler.post {
            listeners.forEach { it.onTaskCreateSucceeded() }
        }
    }

    private fun notifyTaskCreateFailed(message: String) {
        mainHandler.post {
            listeners.forEach { it.onTaskCreateFailed(message) }
        }
    }

    private fun notifyTaskCreateNotice(title: String, message: String) {
        mainHandler.post {
            listeners.forEach { it.onTaskCreateNotice(title, message) }
        }
    }
}
