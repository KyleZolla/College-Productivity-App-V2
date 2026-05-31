package com.example.productivityapp

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.round

class TaskDetailActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var editDueButton: MaterialButton? = null
    private lateinit var dueValue: TextView
    private lateinit var statusLine: TextView
    private lateinit var taskId: String
    /** Last value confirmed from Supabase (or from the intent when opened). */
    private var savedStatus: TaskStatus = TaskStatus.NOT_STARTED
    private var savedDue: LocalDateTime? = null
    private var draftDue: LocalDateTime? = null
    private lateinit var roadmapProgress: ProgressBar
    private lateinit var roadmapProgressLabel: TextView
    private lateinit var roadmapHoursSummary: TextView
    private lateinit var roadmapList: RecyclerView
    private lateinit var roadmapAdapter: RoadmapStepsAdapter
    private lateinit var roadmapCard: com.google.android.material.card.MaterialCardView
    private lateinit var simpleCard: com.google.android.material.card.MaterialCardView
    private lateinit var simpleCompleteCheck: com.google.android.material.checkbox.MaterialCheckBox
    private var roadmapSteps: MutableList<RoadmapStep> = mutableListOf()
    private val estimateFeedbackAnsweredStepIndices = mutableSetOf<Int>()
    private var currentTaskRow: SupabaseTasksApi.TaskRow? = null
    private var roadmapPatchInFlight = false
    private var statusPatchInFlight = false
    private var deleteInFlight = false
    private var coursePatchInFlight = false
    private var suppressCourseSelectionCallback = false

    private var savedCourseId: String? = null
    private var courseOptions: List<CourseSelectorHelper.CourseOption> = emptyList()
    private lateinit var courseDropdown: MaterialAutoCompleteTextView

    private var achievementsUserId: String? = null
    private var activeTasksSnapshot: List<SupabaseTasksApi.TaskRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_task_detail)

        val root = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.taskDetailRoot)
        val toolbar = findViewById<MaterialToolbar>(R.id.taskDetailToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        toolbar.setNavigationOnClickListener { finish() }

        val title = intent.getStringExtra(EXTRA_TASK_TITLE).orEmpty()
        val due = intent.getStringExtra(EXTRA_TASK_DUE_DISPLAY).orEmpty()
        val dueIso = intent.getStringExtra(EXTRA_TASK_DUE_ISO)
        val dueParsed = TaskDueParsing.parseFlexible(dueIso)
        val id = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        if (title.isBlank() || id.isBlank()) {
            finish()
            return
        }
        taskId = id
        savedStatus = TaskStatus.fromApi(intent.getStringExtra(EXTRA_TASK_STATUS))
        savedDue = dueParsed
        draftDue = savedDue

        findViewById<TextView>(R.id.taskDetailName).text = title
        dueValue = findViewById(R.id.taskDetailDueDate)
        statusLine = findViewById(R.id.taskDetailStatusLine)
        courseDropdown = findViewById(R.id.taskDetailCourseDropdown)

        editDueButton = findViewById(R.id.taskDetailEditDue)
        findViewById<MaterialButton>(R.id.taskDetailDeleteTask).setOnClickListener { confirmDeleteTask() }

        roadmapCard = findViewById(R.id.taskDetailRoadmapCard)
        simpleCard = findViewById(R.id.taskDetailSimpleCard)
        simpleCompleteCheck = findViewById(R.id.taskDetailSimpleCompleteCheck)
        simpleCompleteCheck.setOnCheckedChangeListener { _, checked ->
            onSimpleTaskCompleteToggled(checked)
        }

        roadmapProgress = findViewById(R.id.taskDetailRoadmapProgress)
        roadmapProgressLabel = findViewById(R.id.taskDetailRoadmapProgressLabel)
        roadmapHoursSummary = findViewById(R.id.taskDetailRoadmapHoursSummary)
        roadmapList = findViewById(R.id.taskDetailRoadmapList)
        roadmapAdapter = RoadmapStepsAdapter(
            onToggle = { index, checked -> onRoadmapStepToggled(index, checked) },
            shouldShowEstimateFeedback = { index, step ->
                step.completed && index !in estimateFeedbackAnsweredStepIndices
            },
            onEstimateFeedbackSelected = { index, feedback ->
                onRoadmapEstimateFeedbackSelected(index, feedback)
            },
        )
        roadmapList.layoutManager = LinearLayoutManager(this)
        roadmapList.adapter = roadmapAdapter

        // Now that views are wired, we can render labels safely.
        refreshStatusLine(savedStatus)
        refreshDueLabel()
        editDueButton?.setOnClickListener { pickDueDateTime() }
        setupCourseSelectionListener()

        fetchLatestTask()
    }

    private fun setupCourseSelectionListener() {
        courseDropdown.setOnItemClickListener { _, _, position, _ ->
            if (suppressCourseSelectionCallback || coursePatchInFlight) return@setOnItemClickListener
            val newId = courseOptions.getOrNull(position)?.id
            if (newId == savedCourseId) return@setOnItemClickListener
            persistCourseToDatabase(newId)
        }
    }

    private fun loadCoursesForSelector(selectedCourseId: String?) {
        val token = SessionManager.getAccessToken(this) ?: return
        val userId = SupabaseUserId.resolveUserId(token) ?: return
        networkExecutor.execute {
            when (val result = SupabaseCoursesApi.listCourses(token, userId)) {
                is SupabaseCoursesApi.ListResult.Success -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    courseOptions = CourseSelectorHelper.buildOptions(
                        result.courses,
                        getString(R.string.task_course_none),
                    )
                    suppressCourseSelectionCallback = true
                    CourseSelectorHelper.bind(this, courseDropdown, courseOptions, selectedCourseId)
                    suppressCourseSelectionCallback = false
                    courseDropdown.isEnabled = !coursePatchInFlight && !deleteInFlight
                }
                is SupabaseCoursesApi.ListResult.Failure -> Unit
            }
        }
    }

    private fun persistCourseToDatabase(courseId: String?) {
        if (coursePatchInFlight) return
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
            return
        }
        coursePatchInFlight = true
        courseDropdown.isEnabled = false
        networkExecutor.execute {
            when (val result = SupabaseTasksApi.updateTaskCourseId(token, taskId, courseId)) {
                is SupabaseTasksApi.PatchCourseResult.Success -> runOnUiThread {
                    coursePatchInFlight = false
                    if (isFinishing) return@runOnUiThread
                    savedCourseId = result.courseId
                    suppressCourseSelectionCallback = true
                    CourseSelectorHelper.bind(this, courseDropdown, courseOptions, savedCourseId)
                    suppressCourseSelectionCallback = false
                    courseDropdown.isEnabled = !deleteInFlight
                    Toast.makeText(this, R.string.task_course_saved, Toast.LENGTH_SHORT).show()
                }
                is SupabaseTasksApi.PatchCourseResult.Failure -> runOnUiThread {
                    coursePatchInFlight = false
                    if (isFinishing) return@runOnUiThread
                    suppressCourseSelectionCallback = true
                    CourseSelectorHelper.bind(this, courseDropdown, courseOptions, savedCourseId)
                    suppressCourseSelectionCallback = false
                    courseDropdown.isEnabled = !deleteInFlight
                    Toast.makeText(
                        this,
                        getString(R.string.error_task_course_update_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun confirmDeleteTask() {
        if (deleteInFlight) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.task_detail_delete_confirm_title)
            .setMessage(R.string.task_detail_delete_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.task_detail_delete_confirm_button) { _, _ -> deleteTaskFromServer() }
            .show()
    }

    private fun deleteTaskFromServer() {
        if (deleteInFlight) return
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
            return
        }
        deleteInFlight = true
        val deleteBtn = findViewById<MaterialButton>(R.id.taskDetailDeleteTask)
        deleteBtn.isEnabled = false
        editDueButton?.isEnabled = false
        courseDropdown.isEnabled = false
        deleteBtn.text = getString(R.string.task_detail_deleting)

        networkExecutor.execute {
            when (val result = SupabaseTasksApi.deleteTask(token, taskId)) {
                is SupabaseTasksApi.DeleteResult.Success -> runOnUiThread {
                    deleteInFlight = false
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(this, R.string.task_detail_task_deleted, Toast.LENGTH_SHORT).show()
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(EXTRA_RESULT_TASK_DELETED, true),
                    )
                    finish()
                }
                is SupabaseTasksApi.DeleteResult.Failure -> runOnUiThread {
                    deleteInFlight = false
                    if (isFinishing) return@runOnUiThread
                    deleteBtn.isEnabled = true
                    editDueButton?.isEnabled = true
                    courseDropdown.isEnabled = true
                    deleteBtn.text = getString(R.string.task_detail_delete_task)
                    Toast.makeText(
                        this,
                        getString(R.string.error_task_delete_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun fetchLatestTask() {
        val token = SessionManager.getAccessToken(this) ?: return
        achievementsUserId = SupabaseUserId.resolveUserId(token)?.also { AchievementManager.ensureLoaded(token, it) }
        networkExecutor.execute {
            val activeTasksResult = SupabaseTasksApi.listTasks(token, TaskListFilter.ACTIVE)
            when (val result = SupabaseTasksApi.getTask(token, taskId)) {
                is SupabaseTasksApi.GetResult.Failure -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
                is SupabaseTasksApi.GetResult.Success -> {
                    val userId = achievementsUserId
                    val answeredForTask = if (userId != null) {
                        when (val keys = SupabaseRoadmapStepEstimateFeedbackApi.listAnsweredKeys(token, userId)) {
                            is SupabaseRoadmapStepEstimateFeedbackApi.ListResult.Success ->
                                keys.answeredKeys
                                    .mapNotNull { entry ->
                                        val parts = entry.split(':', limit = 2)
                                        if (parts.size == 2 && parts[0] == taskId) {
                                            parts[1].toIntOrNull()
                                        } else {
                                            null
                                        }
                                    }
                                    .toSet()
                            is SupabaseRoadmapStepEstimateFeedbackApi.ListResult.Failure -> emptySet()
                        }
                    } else {
                        emptySet()
                    }
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (activeTasksResult is SupabaseTasksApi.ListResult.Success) {
                            activeTasksSnapshot = activeTasksResult.tasks
                        }
                        currentTaskRow = result.task
                        savedStatus = result.task.status
                        refreshStatusLine(savedStatus)
                        savedDue = result.task.dueDate
                        draftDue = savedDue
                        refreshDueLabel()
                        savedCourseId = result.task.courseId
                        loadCoursesForSelector(savedCourseId)

                        roadmapSteps = RoadmapStep.parseList(result.task.roadmap).toMutableList()
                        estimateFeedbackAnsweredStepIndices.clear()
                        estimateFeedbackAnsweredStepIndices.addAll(
                            answeredForTask.filter { idx ->
                                idx in roadmapSteps.indices && roadmapSteps[idx].completed
                            },
                        )
                        refreshSimpleOrRoadmapUi(result.task)

                        if (!TaskKind.isSimpleTask(result.task)) {
                            val derived = deriveStatusFromSteps(roadmapSteps)
                            if (derived != savedStatus) {
                                persistDerivedStatus(derived)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun refreshSimpleOrRoadmapUi(task: SupabaseTasksApi.TaskRow) {
        if (TaskKind.isSimpleTask(task)) {
            simpleCard.visibility = View.VISIBLE
            roadmapCard.visibility = View.GONE
            simpleCompleteCheck.setOnCheckedChangeListener(null)
            simpleCompleteCheck.isChecked = task.status == TaskStatus.COMPLETE
            simpleCompleteCheck.isEnabled = !statusPatchInFlight
            simpleCompleteCheck.setOnCheckedChangeListener { _, checked ->
                if (checked != (task.status == TaskStatus.COMPLETE)) {
                    onSimpleTaskCompleteToggled(checked)
                }
            }
            roadmapAdapter.submitList(emptyList())
            roadmapProgressLabel.visibility = View.GONE
            roadmapHoursSummary.visibility = View.GONE
            roadmapProgress.visibility = View.GONE
        } else {
            simpleCard.visibility = View.GONE
            roadmapCard.visibility = View.VISIBLE
            if (roadmapSteps.isNotEmpty()) {
                roadmapAdapter.submitList(roadmapSteps)
            } else {
                roadmapAdapter.submitList(emptyList())
            }
            refreshRoadmapProgress()
        }
    }

    private fun onSimpleTaskCompleteToggled(checked: Boolean) {
        val targetStatus = if (checked) TaskStatus.COMPLETE else TaskStatus.NOT_STARTED
        if (targetStatus == savedStatus) return
        simpleCompleteCheck.isEnabled = false
        persistSimpleTaskStatus(targetStatus)
    }

    private fun persistSimpleTaskStatus(status: TaskStatus) {
        if (statusPatchInFlight) return
        val token = SessionManager.getAccessToken(this) ?: return
        statusPatchInFlight = true
        networkExecutor.execute {
            when (val result = SupabaseTasksApi.updateTaskStatus(token, taskId, status)) {
                is SupabaseTasksApi.PatchResult.Success -> runOnUiThread {
                    statusPatchInFlight = false
                    if (isFinishing) return@runOnUiThread
                    savedStatus = result.status
                    refreshStatusLine(savedStatus)
                    refreshDueLabel()
                    simpleCompleteCheck.setOnCheckedChangeListener(null)
                    simpleCompleteCheck.isChecked = savedStatus == TaskStatus.COMPLETE
                    simpleCompleteCheck.isEnabled = true
                    simpleCompleteCheck.setOnCheckedChangeListener { _, checked ->
                        if (checked != (savedStatus == TaskStatus.COMPLETE)) {
                            onSimpleTaskCompleteToggled(checked)
                        }
                    }
                }
                is SupabaseTasksApi.PatchResult.Failure -> runOnUiThread {
                    statusPatchInFlight = false
                    if (isFinishing) return@runOnUiThread
                    simpleCompleteCheck.setOnCheckedChangeListener(null)
                    simpleCompleteCheck.isChecked = savedStatus == TaskStatus.COMPLETE
                    simpleCompleteCheck.isEnabled = true
                    simpleCompleteCheck.setOnCheckedChangeListener { _, checked ->
                        if (checked != (savedStatus == TaskStatus.COMPLETE)) {
                            onSimpleTaskCompleteToggled(checked)
                        }
                    }
                    Toast.makeText(
                        this,
                        getString(R.string.error_task_status_update_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun refreshRoadmapProgress() {
        val totalSteps = roadmapSteps.size
        val doneSteps = roadmapSteps.count { it.completed }
        if (totalSteps <= 0) {
            roadmapProgressLabel.visibility = View.GONE
            roadmapHoursSummary.visibility = View.GONE
            roadmapProgress.visibility = View.GONE
            return
        }

        roadmapProgressLabel.visibility = View.VISIBLE
        roadmapHoursSummary.visibility = View.VISIBLE
        roadmapProgress.visibility = View.VISIBLE

        roadmapProgressLabel.text = getString(R.string.home_plan_steps_summary, doneSteps, totalSteps)

        var doneHours = 0.0
        var totalHours = 0.0
        for (step in roadmapSteps) {
            val h = step.estimatedHours ?: 0.0
            totalHours += h
            if (step.completed) doneHours += h
        }
        roadmapHoursSummary.text = getString(
            R.string.home_today_plan_hours_summary,
            formatRoadmapSummaryHours(doneHours),
            formatRoadmapSummaryHours(totalHours),
        )

        val hourPercent = if (totalHours > 0.0) {
            ((doneHours / totalHours) * 1000.0).toInt().coerceIn(0, 1000)
        } else {
            ((doneSteps.toDouble() / totalSteps.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
        }
        roadmapProgress.progress = hourPercent
    }

    private fun formatRoadmapSummaryHours(hours: Double): String {
        if (hours <= 0.0) return "0"
        val rounded = round(hours * 10.0) / 10.0
        return if (abs(rounded - rounded.toInt()) < 0.05) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", rounded)
        }
    }

    private fun onRoadmapStepToggled(index: Int, checked: Boolean) {
        if (index < 0 || index >= roadmapSteps.size) return
        val cur = roadmapSteps[index]
        if (cur.completed == checked) return
        val today = LocalDate.now()
        val tasksBefore = activeTasksSnapshot
        val hadOverdueBefore = TodayPlanWork.hasIncompleteOverdueSteps(tasksBefore, today)
        estimateFeedbackAnsweredStepIndices.remove(index)
        roadmapSteps[index] = cur.copy(
            completed = checked,
            completedAt = if (checked) Instant.now().toString() else null,
        )
        roadmapAdapter.submitList(roadmapSteps.toList())
        refreshRoadmapProgress()
        persistRoadmapToDatabase()

        val derived = deriveStatusFromSteps(roadmapSteps)
        val newRoadmap = RoadmapStep.toJsonArray(roadmapSteps)
        activeTasksSnapshot = activeTasksSnapshot.map { row ->
            if (row.id == taskId) row.copy(roadmap = newRoadmap, status = derived) else row
        }

        val token = SessionManager.getAccessToken(this)
        val userId = token?.let { achievementsUserId ?: SupabaseUserId.resolveUserId(it) }
        if (token != null && userId != null && checked) {
            AchievementManager.ensureLoaded(token, userId)
            val rec = RoadmapStep.recommendedLocalDate(cur)
            if (rec != null && !rec.isAfter(today)) {
                AchievementManager.maybeShowFirstTaskCompleted(this, token, userId)
            }
            if (rec != null && rec.isAfter(today)) {
                AchievementManager.maybeShowGettingAhead(this, token, userId)
            }
        }

        if (checked && hadOverdueBefore) {
            val hasOverdueAfter = TodayPlanWork.hasIncompleteOverdueSteps(activeTasksSnapshot, today)
            if (!hasOverdueAfter) {
                AchievementManager.showAllCaughtUp(this)
            }
        }

        if (derived != savedStatus) {
            persistDerivedStatus(derived)
        }
    }

    private fun onRoadmapEstimateFeedbackSelected(index: Int, feedback: String) {
        if (roadmapPatchInFlight) return
        val normalized = EstimateFeedback.normalize(feedback) ?: return
        if (index !in roadmapSteps.indices) return
        if (index in estimateFeedbackAnsweredStepIndices) return
        val step = roadmapSteps[index]
        if (!step.completed) return

        val token = SessionManager.getAccessToken(this) ?: return
        val userId = achievementsUserId ?: SupabaseUserId.resolveUserId(token) ?: return
        val task = currentTaskRow ?: return

        estimateFeedbackAnsweredStepIndices.add(index)
        roadmapAdapter.submitList(roadmapSteps.toList())

        networkExecutor.execute {
            when (
                val result = SupabaseRoadmapStepEstimateFeedbackApi.upsert(
                    accessToken = token,
                    userId = userId,
                    task = task,
                    stepIndex = index,
                    step = step,
                    feedback = normalized,
                )
            ) {
                is SupabaseRoadmapStepEstimateFeedbackApi.UpsertResult.Failure -> runOnUiThread {
                    estimateFeedbackAnsweredStepIndices.remove(index)
                    if (isFinishing) return@runOnUiThread
                    roadmapAdapter.submitList(roadmapSteps.toList())
                    Toast.makeText(
                        this,
                        getString(R.string.roadmap_estimate_feedback_save_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is SupabaseRoadmapStepEstimateFeedbackApi.UpsertResult.Success -> Unit
            }
        }
    }

    private fun persistRoadmapToDatabase() {
        if (roadmapPatchInFlight) return
        val token = SessionManager.getAccessToken(this) ?: return
        roadmapPatchInFlight = true
        val payload: JSONArray = RoadmapStep.toJsonArray(roadmapSteps)
        networkExecutor.execute {
            val result = SupabaseTasksApi.updateTaskRoadmap(token, taskId, payload)
            runOnUiThread {
                roadmapPatchInFlight = false
                if (isFinishing) return@runOnUiThread
                if (result is SupabaseTasksApi.PatchRoadmapResult.Failure) {
                    Toast.makeText(
                        this,
                        "Could not save progress.\n${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun refreshDueLabel() {
        val display = draftDue?.let { DueDateTimeFormat.displayFull(it) }
            ?: getString(R.string.due_date_not_set)
        val effectiveStatus = savedStatus
        val dueLine = if (DueDateHumanLabel.isOverdue(draftDue, effectiveStatus)) {
            getString(R.string.due_overdue_was_due, display)
        } else {
            display
        }
        dueValue.text = dueLine
    }

    private fun refreshStatusLine(status: TaskStatus) {
        statusLine.text = "Status: ${status.apiValue}"
    }

    private fun deriveStatusFromSteps(steps: List<RoadmapStep>): TaskStatus {
        if (steps.isEmpty()) return TaskStatus.NOT_STARTED
        val completed = steps.count { it.completed }
        return when {
            completed <= 0 -> TaskStatus.NOT_STARTED
            completed >= steps.size -> TaskStatus.COMPLETE
            else -> TaskStatus.IN_PROGRESS
        }
    }

    private fun persistDerivedStatus(status: TaskStatus) {
        if (statusPatchInFlight) return
        val token = SessionManager.getAccessToken(this) ?: return
        statusPatchInFlight = true
        networkExecutor.execute {
            when (val result = SupabaseTasksApi.updateTaskStatus(token, taskId, status)) {
                is SupabaseTasksApi.PatchResult.Success -> runOnUiThread {
                    statusPatchInFlight = false
                    if (isFinishing) return@runOnUiThread
                    savedStatus = result.status
                    refreshStatusLine(savedStatus)
                    refreshDueLabel()

                    // Achievements are step-based; do not trigger off task status changes here.
                }
                is SupabaseTasksApi.PatchResult.Failure -> runOnUiThread {
                    statusPatchInFlight = false
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(
                        this,
                        getString(R.string.error_task_status_update_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun pickDueDateTime() {
        val initial = draftDue ?: LocalDateTime.now()
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val date = LocalDate.of(year, month + 1, dayOfMonth)
                val initialTime = draftDue?.toLocalTime() ?: LocalTime.of(9, 0)
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(initialTime.hour)
                    .setMinute(initialTime.minute)
                    .setTitleText(R.string.due_time_picker_title)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    draftDue = LocalDateTime.of(date, LocalTime.of(picker.hour, picker.minute))
                    refreshDueLabel()
                    persistDueToDatabase()
                }
                picker.show(supportFragmentManager, "task_detail_due_time")
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        ).apply {
            datePicker.minDate = cal.timeInMillis - 1000L * 60L * 60L * 24L * 365L * 10L
        }.show()
    }

    private fun persistDueToDatabase() {
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
            return
        }
        val target = draftDue
        if (target == savedDue) return
        val previousDue = savedDue

        editDueButton?.isEnabled = false
        editDueButton?.text = getString(R.string.task_detail_saving_due)

        networkExecutor.execute {
            when (val result = SupabaseTasksApi.updateTaskDueDate(token, taskId, target)) {
                is SupabaseTasksApi.PatchDueResult.Success -> {
                    val newDue = result.dueDate
                    val deltaDays = if (previousDue != null && newDue != null) {
                        ChronoUnit.DAYS.between(previousDue.toLocalDate(), newDue.toLocalDate())
                    } else {
                        0L
                    }

                    var shiftedRoadmap: JSONArray? = null
                    if (deltaDays != 0L && roadmapSteps.isNotEmpty()) {
                        val shifted = RoadmapStep.shiftRecommendedDates(roadmapSteps, deltaDays)
                        val candidate = RoadmapStep.toJsonArray(shifted)
                        when (val roadmapPatch = SupabaseTasksApi.updateTaskRoadmap(token, taskId, candidate)) {
                            is SupabaseTasksApi.PatchRoadmapResult.Success -> {
                                shiftedRoadmap = candidate
                            }
                            is SupabaseTasksApi.PatchRoadmapResult.Failure -> runOnUiThread {
                                if (isFinishing) return@runOnUiThread
                                Toast.makeText(
                                    this,
                                    "Due date saved, but could not shift roadmap dates.\n${roadmapPatch.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        savedDue = newDue
                        draftDue = savedDue
                        refreshDueLabel()
                        editDueButton?.isEnabled = true
                        editDueButton?.text = getString(R.string.task_detail_edit_due)
                        Toast.makeText(this, R.string.task_detail_due_saved, Toast.LENGTH_SHORT).show()

                        if (shiftedRoadmap != null) {
                            roadmapSteps = RoadmapStep.parseList(shiftedRoadmap).toMutableList()
                            roadmapAdapter.submitList(roadmapSteps)
                            refreshRoadmapProgress()
                        }
                    }
                }
                is SupabaseTasksApi.PatchDueResult.Failure -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    editDueButton?.isEnabled = true
                    editDueButton?.text = getString(R.string.task_detail_edit_due)
                    draftDue = savedDue
                    refreshDueLabel()
                    Toast.makeText(
                        this,
                        getString(R.string.error_task_due_update_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        networkExecutor.shutdownNow()
        super.onDestroy()
    }


    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_TASK_DUE_DISPLAY = "extra_task_due_display"
        const val EXTRA_TASK_DUE_ISO = "extra_task_due_iso"
        const val EXTRA_TASK_STATUS = "extra_task_status"
        /** Set on the result Intent when the task row was removed in Supabase. */
        const val EXTRA_RESULT_TASK_DELETED = "extra_result_task_deleted"

        fun createIntent(context: Context, task: SupabaseTasksApi.TaskRow): Intent {
            val dueDisplay = task.dueDate?.let { DueDateTimeFormat.displayFull(it) }
                ?: context.getString(R.string.due_date_not_set)
            val dueIso = task.dueDate?.let { TaskDueParsing.toIsoParam(it) }
            return Intent(context, TaskDetailActivity::class.java).apply {
                putExtra(EXTRA_TASK_ID, task.id)
                putExtra(EXTRA_TASK_TITLE, task.title)
                putExtra(EXTRA_TASK_DUE_DISPLAY, dueDisplay)
                putExtra(EXTRA_TASK_DUE_ISO, dueIso)
                putExtra(EXTRA_TASK_STATUS, task.status.apiValue)
            }
        }
    }
}
