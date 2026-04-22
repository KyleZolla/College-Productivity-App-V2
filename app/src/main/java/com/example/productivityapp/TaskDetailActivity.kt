package com.example.productivityapp

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Calendar
import java.util.concurrent.Executors

class TaskDetailActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private lateinit var statusChipGroup: ChipGroup
    private lateinit var saveStatusButton: MaterialButton
    private var editDueButton: MaterialButton? = null
    private var saveDueButton: MaterialButton? = null
    private lateinit var dueValue: TextView
    private lateinit var taskId: String
    /** Last value confirmed from Supabase (or from the intent when opened). */
    private var savedStatus: TaskStatus = TaskStatus.NOT_STARTED
    private var savedDue: LocalDateTime? = null
    private var draftDue: LocalDateTime? = null
    private var applyingChipSelection = false

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
        refreshDueLabel()

        statusChipGroup = findViewById(R.id.taskDetailStatusChips)
        saveStatusButton = findViewById(R.id.taskDetailSaveStatus)
        editDueButton = findViewById(R.id.taskDetailEditDue)
        saveDueButton = findViewById(R.id.taskDetailSaveDue)

        findViewById<Chip>(R.id.chipTaskStatusNotStarted).text = TaskStatus.NOT_STARTED.apiValue
        findViewById<Chip>(R.id.chipTaskStatusInProgress).text = TaskStatus.IN_PROGRESS.apiValue
        findViewById<Chip>(R.id.chipTaskStatusComplete).text = TaskStatus.COMPLETE.apiValue

        applyChipSelection(savedStatus)

        statusChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (applyingChipSelection || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            updateSaveEnabled()
        }

        saveStatusButton.setOnClickListener { persistStatusToDatabase() }
        editDueButton?.setOnClickListener { pickDueDateTime() }
        saveDueButton?.setOnClickListener { persistDueToDatabase() }

        updateSaveEnabled()
    }

    private fun refreshDueLabel() {
        val display = draftDue?.let { DueDateTimeFormat.displayFull(it) }
            ?: getString(R.string.due_date_not_set)
        val effectiveStatus = if (::statusChipGroup.isInitialized) {
            statusFromCheckedChip() ?: savedStatus
        } else {
            savedStatus
        }
        val dueLine = if (DueDateHumanLabel.isOverdue(draftDue, effectiveStatus)) {
            getString(R.string.due_overdue_was_due, display)
        } else {
            display
        }
        dueValue.text = dueLine
        // onCreate() calls this before all views are wired up.
        if (::statusChipGroup.isInitialized) {
            updateSaveEnabled()
        }
    }

    private fun statusFromCheckedChip(): TaskStatus? {
        return when (statusChipGroup.checkedChipId) {
            R.id.chipTaskStatusNotStarted -> TaskStatus.NOT_STARTED
            R.id.chipTaskStatusInProgress -> TaskStatus.IN_PROGRESS
            R.id.chipTaskStatusComplete -> TaskStatus.COMPLETE
            View.NO_ID -> null
            else -> null
        }
    }

    private fun updateSaveEnabled() {
        val draft = statusFromCheckedChip()
        saveStatusButton.isEnabled = draft != null && draft != savedStatus
        saveDueButton?.isEnabled = draftDue != savedDue
    }

    private fun persistStatusToDatabase() {
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
            return
        }
        val target = statusFromCheckedChip() ?: return
        if (target == savedStatus) return

        saveStatusButton.isEnabled = false
        statusChipGroup.isEnabled = false
        saveStatusButton.text = getString(R.string.task_detail_saving_status)

        networkExecutor.execute {
            when (val result = SupabaseTasksApi.updateTaskStatus(token, taskId, target)) {
                is SupabaseTasksApi.PatchResult.Success -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    savedStatus = result.status
                    applyChipSelection(savedStatus)
                    saveStatusButton.text = getString(R.string.task_detail_save_status)
                    statusChipGroup.isEnabled = true
                    updateSaveEnabled()
                    Toast.makeText(this, R.string.task_detail_status_saved, Toast.LENGTH_SHORT).show()
                }
                is SupabaseTasksApi.PatchResult.Failure -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    saveStatusButton.text = getString(R.string.task_detail_save_status)
                    statusChipGroup.isEnabled = true
                    applyChipSelection(savedStatus)
                    updateSaveEnabled()
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

        saveDueButton?.isEnabled = false
        editDueButton?.isEnabled = false
        saveDueButton?.text = getString(R.string.task_detail_saving_due)

        networkExecutor.execute {
            when (val result = SupabaseTasksApi.updateTaskDueDate(token, taskId, target)) {
                is SupabaseTasksApi.PatchDueResult.Success -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    savedDue = result.dueDate
                    draftDue = savedDue
                    refreshDueLabel()
                    editDueButton?.isEnabled = true
                    saveDueButton?.text = getString(R.string.task_detail_save_due)
                    Toast.makeText(this, R.string.task_detail_due_saved, Toast.LENGTH_SHORT).show()
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

    private fun applyChipSelection(status: TaskStatus) {
        applyingChipSelection = true
        val chipId = when (status) {
            TaskStatus.NOT_STARTED -> R.id.chipTaskStatusNotStarted
            TaskStatus.IN_PROGRESS -> R.id.chipTaskStatusInProgress
            TaskStatus.COMPLETE -> R.id.chipTaskStatusComplete
        }
        statusChipGroup.check(chipId)
        applyingChipSelection = false
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
