package com.example.productivityapp

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Calendar
import java.util.concurrent.Executors

class AddTaskActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_add_task)

        val root = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.addTaskRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val titleInput = findViewById<EditText>(R.id.taskTitleInput)
        val dueDateRow = findViewById<LinearLayout>(R.id.dueDateRow)
        val dueDateValue = findViewById<TextView>(R.id.dueDateValue)
        val createButton = findViewById<Button>(R.id.createTaskButton)
        val cancelButton = findViewById<Button>(R.id.cancelAddTaskButton)

        var selectedDue: LocalDateTime? = null

        fun refreshDueLabel() {
            dueDateValue.text = selectedDue?.let { DueDateTimeFormat.displayFull(it) }
                ?: getString(R.string.due_date_not_set)
        }

        dueDateRow.setOnClickListener {
            val cal = Calendar.getInstance()
            val initial = selectedDue ?: LocalDateTime.now()
            val initialYear = initial.year
            val initialMonth = initial.monthValue - 1
            val initialDay = initial.dayOfMonth

            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    val date = LocalDate.of(year, month + 1, dayOfMonth)
                    val initialTime = selectedDue?.toLocalTime() ?: LocalTime.of(9, 0)
                    val picker = MaterialTimePicker.Builder()
                        .setTimeFormat(TimeFormat.CLOCK_12H)
                        .setHour(initialTime.hour)
                        .setMinute(initialTime.minute)
                        .setTitleText(R.string.due_time_picker_title)
                        .build()
                    picker.addOnPositiveButtonClickListener {
                        selectedDue = LocalDateTime.of(
                            date,
                            LocalTime.of(picker.hour, picker.minute),
                        )
                        refreshDueLabel()
                    }
                    picker.show(supportFragmentManager, "task_due_time")
                },
                initialYear,
                initialMonth,
                initialDay,
            ).show()
        }

        createButton.setOnClickListener {
            val title = titleInput.text.toString().trim()
            val due = selectedDue
            if (title.isBlank()) {
                Toast.makeText(this, R.string.error_task_title_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (due == null) {
                Toast.makeText(this, R.string.error_task_due_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val accessToken = SessionManager.getAccessToken(this)
            if (accessToken.isNullOrBlank()) {
                Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val userId = SupabaseUserId.resolveUserId(accessToken)
            if (userId.isNullOrBlank()) {
                Toast.makeText(this, R.string.error_task_user_unknown, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            createButton.isEnabled = false
            cancelButton.isEnabled = false

            networkExecutor.execute {
                val result = SupabaseTasksApi.insertTask(accessToken, userId, title, due)
                runOnUiThread {
                    createButton.isEnabled = true
                    cancelButton.isEnabled = true
                    when (result) {
                        is SupabaseTasksApi.InsertResult.Success -> {
                            val dueDisplay = DueDateTimeFormat.displayFull(due)
                            startActivity(
                                Intent(this, HomeActivity::class.java).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                    putExtra(HomeActivity.EXTRA_SELECTED_TAB, HomeActivity.TAB_TASKS)
                                }
                            )
                            startActivity(
                                Intent(this, TaskDetailActivity::class.java).apply {
                                    putExtra(TaskDetailActivity.EXTRA_TASK_ID, result.id)
                                    putExtra(TaskDetailActivity.EXTRA_TASK_TITLE, title)
                                    putExtra(TaskDetailActivity.EXTRA_TASK_DUE_DISPLAY, dueDisplay)
                                    putExtra(TaskDetailActivity.EXTRA_TASK_DUE_ISO, TaskDueParsing.toIsoParam(due))
                                    putExtra(TaskDetailActivity.EXTRA_TASK_STATUS, result.status.apiValue)
                                }
                            )
                            finish()
                        }
                        is SupabaseTasksApi.InsertResult.Failure -> {
                            Toast.makeText(
                                this,
                                getString(R.string.error_task_create_failed) + "\n" + result.message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        cancelButton.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }
}
