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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Calendar
import java.util.concurrent.Executors

class AddTaskActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var courseOptions: List<CourseSelectorHelper.CourseOption> = emptyList()
    private lateinit var courseDropdown: MaterialAutoCompleteTextView

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
        val taskTypeToggle = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.taskTypeToggle)
        val aiSectionCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.addTaskAiSectionCard)
        val dueDateRow = findViewById<LinearLayout>(R.id.dueDateRow)
        val dueDateValue = findViewById<TextView>(R.id.dueDateValue)
        val createButton = findViewById<Button>(R.id.createTaskButton)
        val cancelButton = findViewById<Button>(R.id.cancelAddTaskButton)
        courseDropdown = findViewById(R.id.taskCourseDropdown)

        var selectedDue: LocalDateTime? = null

        // AI roadmap helpers (optional context for roadmap generation).
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
        val estimatedHoursInput = findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.addTaskEstimatedHoursInput
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

        var isComplexMode = false

        fun refreshTaskTypeUi() {
            aiSectionCard.visibility = if (isComplexMode) android.view.View.VISIBLE else android.view.View.GONE
        }

        taskTypeToggle.check(R.id.taskTypeSimple)
        refreshTaskTypeUi()

        taskTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            isComplexMode = checkedId == R.id.taskTypeComplex
            refreshTaskTypeUi()
        }

        fun navigateToTasksTab() {
            startActivity(
                Intent(this, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(HomeActivity.EXTRA_SELECTED_TAB, HomeActivity.TAB_TASKS)
                },
            )
            finish()
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

        refreshDueLabel()
        loadCoursesForSelector()

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

            val assignmentType = selectedAssignmentType
            val difficulty = selectedDifficulty
            val requirements = additionalDetailsInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            val userEstimatedHours = readUserEstimatedHours(estimatedHoursInput)
            val selectedDoc = selectedDocUri
            val selectedPhoto = selectedPhotoUri
            val creatingComplex = isComplexMode
            val selectedCourseId = CourseSelectorHelper.selectedCourseId(courseDropdown, courseOptions)

            BackgroundCreateJobs.enqueueTaskCreate(
                context = this,
                accessToken = accessToken,
                userId = userId,
                title = title,
                due = due,
                selectedCourseId = selectedCourseId,
                creatingComplex = creatingComplex,
                assignmentType = assignmentType,
                difficulty = difficulty,
                requirements = requirements,
                userEstimatedHours = userEstimatedHours,
                selectedDocUri = selectedDoc,
                selectedPhotoUri = selectedPhoto,
            )
            Toast.makeText(this, R.string.status_creating_task_background, Toast.LENGTH_SHORT).show()
            navigateToTasksTab()
        }

        cancelButton.setOnClickListener {
            finish()
        }
    }

    private fun loadCoursesForSelector() {
        val accessToken = SessionManager.getAccessToken(this) ?: return
        val userId = SupabaseUserId.resolveUserId(accessToken) ?: return
        networkExecutor.execute {
            when (val result = SupabaseCoursesApi.listCourses(accessToken, userId)) {
                is SupabaseCoursesApi.ListResult.Success -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    courseOptions = CourseSelectorHelper.buildOptions(
                        result.courses,
                        getString(R.string.task_course_none),
                    )
                    CourseSelectorHelper.bind(this, courseDropdown, courseOptions, selectedCourseId = null)
                }
                is SupabaseCoursesApi.ListResult.Failure -> Unit
            }
        }
    }

    private fun readUserEstimatedHours(
        input: com.google.android.material.textfield.TextInputEditText,
    ): Double? {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text.toDoubleOrNull()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }
}
