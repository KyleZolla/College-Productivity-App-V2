package com.example.productivityapp

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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

        // AI roadmap helpers (UI only for now).
        val uploadDocCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.addTaskAiUploadDoc)
        val uploadDocPanel = findViewById<LinearLayout>(R.id.addTaskAiUploadDocPanel)
        val chooseDocButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.addTaskAiChooseDocButton)
        val selectedDocLabel = findViewById<TextView>(R.id.addTaskAiUploadDocSelectedLabel)
        var selectedDocUri: Uri? = null

        val uploadPhotoCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.addTaskAiUploadPhoto)
        val uploadPhotoPanel = findViewById<LinearLayout>(R.id.addTaskAiUploadPhotoPanel)
        val choosePhotoButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.addTaskAiChoosePhotoButton)
        val selectedPhotoLabel = findViewById<TextView>(R.id.addTaskAiUploadPhotoSelectedLabel)
        var selectedPhotoUri: Uri? = null

        val answerQuestionsCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.addTaskAiAnswerQuestions)
        val answerQuestionsPanel = findViewById<LinearLayout>(R.id.addTaskAiAnswerQuestionsPanel)
        val assignmentTypeChips = findViewById<com.google.android.material.chip.ChipGroup>(R.id.addTaskAiAssignmentTypeChips)
        val difficultyChips = findViewById<com.google.android.material.chip.ChipGroup>(R.id.addTaskAiDifficultyChips)
        val additionalDetailsInput = findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.addTaskAiAdditionalDetailsInput
        )

        val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            // Persist read permission so the URI stays valid if user returns later.
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't allow persistable permissions; ignore.
            }
            selectedDocUri = uri
            selectedDocLabel.text = getString(
                R.string.add_task_ai_doc_selected,
                (uri.lastPathSegment ?: uri.toString())
            )
        }

        val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            selectedPhotoUri = uri
            selectedPhotoLabel.text = getString(
                R.string.add_task_ai_photo_selected,
                (uri.lastPathSegment ?: uri.toString())
            )
        }

        uploadDocCard.setOnClickListener {
            uploadDocPanel.visibility = if (uploadDocPanel.visibility == android.view.View.VISIBLE) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
        }
        chooseDocButton.setOnClickListener {
            pickDocumentLauncher.launch(arrayOf("*/*"))
        }

        uploadPhotoCard.setOnClickListener {
            uploadPhotoPanel.visibility = if (uploadPhotoPanel.visibility == android.view.View.VISIBLE) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
        }
        choosePhotoButton.setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }

        answerQuestionsCard.setOnClickListener {
            answerQuestionsPanel.visibility = if (answerQuestionsPanel.visibility == android.view.View.VISIBLE) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
        }

        // Values are currently UI-only; these vars just keep them handy for later wiring.
        fun readSelectedChipText(group: com.google.android.material.chip.ChipGroup): String? {
            val id = group.checkedChipId
            if (id == android.view.View.NO_ID) return null
            return group.findViewById<com.google.android.material.chip.Chip>(id)?.text?.toString()
        }
        var selectedAssignmentType: String? = null
        var selectedDifficulty: String? = null
        assignmentTypeChips.setOnCheckedStateChangeListener { _, _ ->
            selectedAssignmentType = readSelectedChipText(assignmentTypeChips)
        }
        difficultyChips.setOnCheckedStateChangeListener { _, _ ->
            selectedDifficulty = readSelectedChipText(difficultyChips)
        }
        // Prevent "unused" warnings in case you compile with stricter settings later.
        additionalDetailsInput.setOnFocusChangeListener { _, _ ->
            // no-op
        }

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
