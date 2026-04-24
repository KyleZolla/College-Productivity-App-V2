package com.example.productivityapp

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Calendar
import java.util.concurrent.Executors
import org.json.JSONArray

class AddTaskActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val logTag = "AddTaskActivity"
    private var activeDialog: AlertDialog? = null

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

            val assignmentType = selectedAssignmentType
            val difficulty = selectedDifficulty
            val requirements = additionalDetailsInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            val selectedDoc = selectedDocUri
            val selectedPhoto = selectedPhotoUri

            createButton.isEnabled = false
            cancelButton.isEnabled = false
            val originalCreateText = createButton.text
            createButton.text = getString(R.string.status_creating_task)

            networkExecutor.execute {
                val insertResult = SupabaseTasksApi.insertTask(accessToken, userId, title, due)
                runOnUiThread {
                    createButton.isEnabled = true
                    cancelButton.isEnabled = true
                    createButton.text = originalCreateText
                }

                when (insertResult) {
                    is SupabaseTasksApi.InsertResult.Failure -> runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.error_task_create_failed) + "\n" + insertResult.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    is SupabaseTasksApi.InsertResult.Success -> {
                        val taskId = insertResult.id
                        val dueIso = TaskDueParsing.toIsoParam(due)

                        val documentContent = selectedDoc?.let { uri ->
                            when (val res = DocumentContentExtractor.extractText(this, uri)) {
                                is DocumentContentExtractor.Result.Success -> res.content
                                is DocumentContentExtractor.Result.Failure -> {
                                    runOnUiThread {
                                        val msg = "Could not read document.\n${res.message}"
                                        Log.w(logTag, msg)
                                        if (!isFinishing && !isDestroyed) {
                                            activeDialog?.dismiss()
                                            activeDialog = MaterialAlertDialogBuilder(this)
                                                .setTitle("Document upload skipped")
                                                .setMessage(res.message)
                                                .setPositiveButton(android.R.string.ok, null)
                                                .show()
                                        }
                                    }
                                    null
                                }
                            }
                        }

                        val photoText = selectedPhoto?.let { uri ->
                            when (val res = PhotoTextExtractor.extractText(this, uri)) {
                                is PhotoTextExtractor.Result.Success -> res.text
                                is PhotoTextExtractor.Result.Failure -> {
                                    runOnUiThread {
                                        val msg = "Could not read text from photo.\n${res.message}"
                                        Log.w(logTag, msg)
                                        if (!isFinishing && !isDestroyed) {
                                            activeDialog?.dismiss()
                                            activeDialog = MaterialAlertDialogBuilder(this)
                                                .setTitle("Photo text skipped")
                                                .setMessage(res.message)
                                                .setPositiveButton(android.R.string.ok, null)
                                                .show()
                                        }
                                    }
                                    null
                                }
                            }
                        }

                        val roadmapSteps = when (val roadmapResult = SupabaseEdgeFunctionsApi.getRoadmap(
                            accessToken = accessToken,
                            title = title,
                            dueDateIso = dueIso,
                            assignmentType = assignmentType,
                            difficulty = difficulty,
                            requirements = requirements,
                            documentContent = documentContent,
                            photoText = photoText,
                        )) {
                            is SupabaseEdgeFunctionsApi.RoadmapResult.Success -> roadmapResult.steps
                            is SupabaseEdgeFunctionsApi.RoadmapResult.Failure -> {
                                runOnUiThread {
                                    val msg = "Roadmap generation failed.\n${roadmapResult.message}"
                                    Log.e(logTag, msg)
                                    if (!isFinishing && !isDestroyed) {
                                        activeDialog?.dismiss()
                                        activeDialog = MaterialAlertDialogBuilder(this)
                                            .setTitle("Roadmap generation failed")
                                            .setMessage(roadmapResult.message)
                                            .setPositiveButton(android.R.string.ok, null)
                                            .show()
                                    }
                                }
                                JSONArray()
                            }
                        }

                        if (roadmapSteps.length() == 0) {
                            runOnUiThread {
                                val msg = "Edge Function returned 0 steps. Will save empty roadmap."
                                Log.w(logTag, msg)
                                if (!isFinishing && !isDestroyed) {
                                    activeDialog?.dismiss()
                                    activeDialog = MaterialAlertDialogBuilder(this)
                                        .setTitle("No roadmap steps returned")
                                        .setMessage(
                                            "The Edge Function returned 0 steps. The task will be created, but its roadmap will be empty.\n\nCheck your Edge Function output format."
                                        )
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                            }
                        }

                        when (val patchResult = SupabaseTasksApi.updateTaskRoadmap(
                            accessToken = accessToken,
                            taskId = taskId,
                            roadmapSteps = roadmapSteps
                        )) {
                            is SupabaseTasksApi.PatchRoadmapResult.Failure -> runOnUiThread {
                                val msg = "Could not save roadmap.\n${patchResult.message}"
                                Log.e(logTag, msg)
                                if (!isFinishing && !isDestroyed) {
                                    activeDialog?.dismiss()
                                    activeDialog = MaterialAlertDialogBuilder(this)
                                        .setTitle("Could not save roadmap")
                                        .setMessage(patchResult.message)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                            }
                            SupabaseTasksApi.PatchRoadmapResult.Success -> runOnUiThread {
                                Log.d(logTag, "Roadmap saved for taskId=$taskId steps=${roadmapSteps.length()}")
                            }
                        }

                        runOnUiThread {
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
                                    putExtra(TaskDetailActivity.EXTRA_TASK_ID, taskId)
                                    putExtra(TaskDetailActivity.EXTRA_TASK_TITLE, title)
                                    putExtra(TaskDetailActivity.EXTRA_TASK_DUE_DISPLAY, dueDisplay)
                                    putExtra(TaskDetailActivity.EXTRA_TASK_DUE_ISO, dueIso)
                                    putExtra(TaskDetailActivity.EXTRA_TASK_STATUS, insertResult.status.apiValue)
                                }
                            )
                            finish()
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
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroy()
        networkExecutor.shutdownNow()
    }
}
