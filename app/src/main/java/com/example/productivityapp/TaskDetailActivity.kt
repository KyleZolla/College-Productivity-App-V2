package com.example.productivityapp

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
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.concurrent.Executors

class TaskDetailActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var editDueButton: MaterialButton? = null
    private var saveDueButton: MaterialButton? = null
    private lateinit var dueValue: TextView
    private lateinit var statusLine: TextView
    private lateinit var taskId: String
    /** Last value confirmed from Supabase (or from the intent when opened). */
    private var savedStatus: TaskStatus = TaskStatus.NOT_STARTED
    private var savedDue: LocalDateTime? = null
    private var draftDue: LocalDateTime? = null
    private lateinit var roadmapProgress: ProgressBar
    private lateinit var roadmapProgressLabel: TextView
    private lateinit var roadmapList: RecyclerView
    private lateinit var roadmapAdapter: RoadmapStepsAdapter
    private var roadmapSteps: MutableList<RoadmapStep> = mutableListOf()
    private var roadmapPatchInFlight = false
    private var statusPatchInFlight = false

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

        editDueButton = findViewById(R.id.taskDetailEditDue)
        saveDueButton = findViewById(R.id.taskDetailSaveDue)

        roadmapProgress = findViewById(R.id.taskDetailRoadmapProgress)
        roadmapProgressLabel = findViewById(R.id.taskDetailRoadmapProgressLabel)
        roadmapList = findViewById(R.id.taskDetailRoadmapList)
        roadmapAdapter = RoadmapStepsAdapter { index, checked -> onRoadmapStepToggled(index, checked) }
        roadmapList.layoutManager = LinearLayoutManager(this)
        roadmapList.adapter = roadmapAdapter

        // Now that views are wired, we can render labels safely.
        refreshStatusLine(savedStatus)
        refreshDueLabel()
        editDueButton?.setOnClickListener { pickDueDateTime() }
        saveDueButton?.setOnClickListener { persistDueToDatabase() }

        updateSaveEnabled()

        fetchLatestTask()
    }

    private fun fetchLatestTask() {
        val token = SessionManager.getAccessToken(this) ?: return
        networkExecutor.execute {
            when (val result = SupabaseTasksApi.getTask(token, taskId)) {
                is SupabaseTasksApi.GetResult.Failure -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
                is SupabaseTasksApi.GetResult.Success -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    savedStatus = result.task.status
                    refreshStatusLine(savedStatus)
                    savedDue = result.task.dueDate
                    draftDue = savedDue
                    refreshDueLabel()

                    roadmapSteps = RoadmapStep.parseList(result.task.roadmap).toMutableList()
                    if (roadmapSteps.isNotEmpty()) {
                        roadmapAdapter.submitList(roadmapSteps)
                    } else {
                        roadmapAdapter.submitList(emptyList())
                    }
                    refreshRoadmapProgress()
                    updateSaveEnabled()

                    // Ensure status stays consistent with roadmap progress.
                    val derived = deriveStatusFromSteps(roadmapSteps)
                    if (derived != savedStatus) {
                        persistDerivedStatus(derived)
                    }
                }
            }
        }
    }

    private fun refreshRoadmapProgress() {
        val completed = roadmapSteps.count { it.completed }
        val total = roadmapSteps.size
        val percent = if (total == 0) 0 else ((completed.toDouble() / total.toDouble()) * 100.0).toInt()
        roadmapProgress.progress = percent
        roadmapProgressLabel.text = "$completed/$total steps completed"
    }

    private fun onRoadmapStepToggled(index: Int, checked: Boolean) {
        if (index < 0 || index >= roadmapSteps.size) return
        val cur = roadmapSteps[index]
        if (cur.completed == checked) return
        roadmapSteps[index] = cur.copy(completed = checked)
        roadmapAdapter.submitList(roadmapSteps.toList())
        refreshRoadmapProgress()
        persistRoadmapToDatabase()

        val derived = deriveStatusFromSteps(roadmapSteps)
        if (derived != savedStatus) {
            persistDerivedStatus(derived)
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
        updateSaveEnabled()
    }

    private fun updateSaveEnabled() {
        saveDueButton?.isEnabled = draftDue != savedDue
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

        saveDueButton?.isEnabled = false
        editDueButton?.isEnabled = false
        saveDueButton?.text = getString(R.string.task_detail_saving_due)

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
                        saveDueButton?.text = getString(R.string.task_detail_save_due)
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
                    saveDueButton?.text = getString(R.string.task_detail_save_due)
                    editDueButton?.isEnabled = true
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
