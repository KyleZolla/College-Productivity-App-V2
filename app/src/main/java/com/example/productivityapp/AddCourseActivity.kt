package com.example.productivityapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executors

class AddCourseActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var activeDialog: AlertDialog? = null
    private var selectedDocUri: Uri? = null
    private var selectedPhotoUri: Uri? = null
    private var editingCourseId: String? = null
    private var existingSyllabus: String? = null

    private lateinit var selectedSyllabusLabel: TextView
    private lateinit var saveButton: MaterialButton
    private lateinit var nameInput: TextInputEditText
    private lateinit var levelChips: ChipGroup

    private val pickDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers don't allow persistable permissions; ignore.
        }
        selectedDocUri = uri
        refreshSyllabusSelectionLabel()
    }

    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        selectedPhotoUri = uri
        refreshSyllabusSelectionLabel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_add_course)

        val root = findViewById<View>(R.id.addCourseRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.addCourseToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        nameInput = findViewById(R.id.courseNameInput)
        levelChips = findViewById(R.id.courseLevelChips)
        saveButton = findViewById(R.id.saveCourseButton)
        selectedSyllabusLabel = findViewById(R.id.selectedSyllabusLabel)

        findViewById<MaterialButton>(R.id.chooseSyllabusDocButton).setOnClickListener {
            pickDocumentLauncher.launch(SYLLABUS_DOCUMENT_MIME_TYPES)
        }
        findViewById<MaterialButton>(R.id.chooseSyllabusPhotoButton).setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }

        editingCourseId = intent.getStringExtra(EXTRA_COURSE_ID)?.trim()?.takeIf { it.isNotEmpty() }
        if (editingCourseId != null) {
            toolbar.title = getString(R.string.edit_course_title)
            saveButton.isEnabled = false
            loadCourseForEdit(editingCourseId!!)
        } else {
            refreshSyllabusSelectionLabel()
        }

        saveButton.setOnClickListener { saveCourse() }
    }

    private fun loadCourseForEdit(courseId: String) {
        val accessToken = SessionManager.getAccessToken(this)
        if (accessToken.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        networkExecutor.execute {
            when (val result = SupabaseCoursesApi.getCourse(accessToken, courseId)) {
                is SupabaseCoursesApi.GetResult.Success -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    bindCourseForEdit(result.course)
                }
                is SupabaseCoursesApi.GetResult.Failure -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(
                        this,
                        getString(R.string.error_course_load_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun bindCourseForEdit(course: SupabaseCoursesApi.CourseRow) {
        nameInput.setText(course.name)
        selectLevelChip(course.level)
        existingSyllabus = course.syllabus
        refreshSyllabusSelectionLabel()
        saveButton.isEnabled = true
    }

    private fun selectLevelChip(level: String) {
        for (i in 0 until levelChips.childCount) {
            val chip = levelChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.text.toString() == level
        }
    }

    private fun saveCourse() {
        val name = nameInput.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            Toast.makeText(this, R.string.error_course_name_required, Toast.LENGTH_SHORT).show()
            return
        }

        val level = readSelectedLevel(levelChips)
        if (level.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_course_level_required, Toast.LENGTH_SHORT).show()
            return
        }

        val accessToken = SessionManager.getAccessToken(this)
        if (accessToken.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
            return
        }

        val userId = SupabaseUserId.resolveUserId(accessToken)
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_user_unknown, Toast.LENGTH_SHORT).show()
            return
        }

        val docUri = selectedDocUri
        val photoUri = selectedPhotoUri
        val courseId = editingCourseId

        if (courseId == null) {
            BackgroundCreateJobs.enqueueCourseCreate(
                context = this,
                accessToken = accessToken,
                userId = userId,
                name = name,
                level = level,
                docUri = docUri,
                photoUri = photoUri,
            )
            Toast.makeText(this, R.string.status_creating_course_background, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(HomeActivity.EXTRA_SELECTED_TAB, HomeActivity.TAB_PROFILE)
                },
            )
            finish()
            return
        }

        saveButton.isEnabled = false
        val originalText = saveButton.text
        saveButton.text = getString(R.string.status_saving_course)

        networkExecutor.execute {
            val previousSyllabus = existingSyllabus
            val extractedSyllabus = extractSyllabusText(docUri, photoUri)
            val syllabusToSave = when {
                extractedSyllabus != null -> extractedSyllabus
                courseId != null -> existingSyllabus
                else -> null
            }

            val result = if (courseId != null) {
                SupabaseCoursesApi.updateCourse(
                    accessToken = accessToken,
                    courseId = courseId,
                    name = name,
                    level = level,
                    syllabus = syllabusToSave,
                )
            } else {
                SupabaseCoursesApi.insertCourse(
                    accessToken = accessToken,
                    userId = userId,
                    name = name,
                    level = level,
                    syllabus = syllabusToSave,
                ).let { insert ->
                    when (insert) {
                        is SupabaseCoursesApi.InsertResult.Success ->
                            SupabaseCoursesApi.UpdateResult.Success(insert.course)
                        is SupabaseCoursesApi.InsertResult.Failure ->
                            SupabaseCoursesApi.UpdateResult.Failure(insert.message)
                    }
                }
            }

            when (result) {
                is SupabaseCoursesApi.UpdateResult.Success -> {
                    val savedCourse = result.course
                    runOnUiThread {
                        if (!isFinishing) {
                            saveButton.text = getString(R.string.status_generating_course_profile)
                        }
                    }
                    val profileSync = CourseProfileCoordinator.syncAfterSave(
                        accessToken = accessToken,
                        courseId = savedCourse.id,
                        courseName = savedCourse.name,
                        courseLevel = savedCourse.level,
                        savedSyllabus = savedCourse.syllabus,
                        previousSyllabus = previousSyllabus,
                        isEdit = courseId != null,
                    )
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_WAS_EDIT, courseId != null),
                        )
                        when (profileSync) {
                            is CourseProfileCoordinator.SyncResult.Failure ->
                                Toast.makeText(
                                    this,
                                    R.string.course_profile_generation_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            is CourseProfileCoordinator.SyncResult.LimitReached ->
                                Toast.makeText(
                                    this,
                                    profileSync.message?.takeIf { it.isNotBlank() }
                                        ?: getString(R.string.ai_syllabus_limit_reached),
                                    Toast.LENGTH_LONG,
                                ).show()
                            CourseProfileCoordinator.SyncResult.Skipped,
                            CourseProfileCoordinator.SyncResult.Success,
                            -> Unit
                        }
                        finish()
                    }
                }
                is SupabaseCoursesApi.UpdateResult.Failure -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    saveButton.isEnabled = true
                    saveButton.text = originalText
                    Toast.makeText(
                        this,
                        getString(R.string.error_course_save_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun refreshSyllabusSelectionLabel() {
        val doc = selectedDocUri
        val photo = selectedPhotoUri
        selectedSyllabusLabel.text = when {
            doc != null && photo != null -> getString(
                R.string.course_syllabus_doc_and_photo_selected,
                uriDisplayName(doc),
                uriDisplayName(photo),
            )
            doc != null -> getString(R.string.course_syllabus_doc_selected, uriDisplayName(doc))
            photo != null -> getString(R.string.course_syllabus_photo_selected, uriDisplayName(photo))
            !existingSyllabus.isNullOrBlank() -> getString(R.string.course_syllabus_on_file)
            else -> getString(R.string.course_syllabus_none)
        }
    }

    private fun uriDisplayName(uri: Uri): String {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    cursor.getString(idx)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        } catch (_: Exception) {
            // Fall back to URI path below.
        } finally {
            cursor?.close()
        }
        return uri.lastPathSegment?.trim()?.takeIf { it.isNotEmpty() } ?: uri.toString()
    }

    private fun readSelectedLevel(group: ChipGroup): String? {
        val id = group.checkedChipId
        if (id == View.NO_ID) return null
        return group.findViewById<Chip>(id)?.text?.toString()
    }

    private fun extractSyllabusText(docUri: Uri?, photoUri: Uri?): String? {
        if (docUri == null && photoUri == null) return null
        val parts = ArrayList<String>(2)
        docUri?.let { uri ->
            when (val res = DocumentContentExtractor.extractText(this, uri)) {
                is DocumentContentExtractor.Result.Success -> parts.add(res.content)
                is DocumentContentExtractor.Result.Failure -> showExtractSkippedDialog(res.message)
            }
        }
        photoUri?.let { uri ->
            when (val res = PhotoTextExtractor.extractText(this, uri)) {
                is PhotoTextExtractor.Result.Success -> parts.add(res.text)
                is PhotoTextExtractor.Result.Failure -> showExtractSkippedDialog(res.message)
            }
        }
        return parts.joinToString("\n\n").trim().takeIf { it.isNotEmpty() }
    }

    private fun showExtractSkippedDialog(message: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                activeDialog?.dismiss()
                activeDialog = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.course_syllabus_extract_skipped_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroy()
        networkExecutor.shutdownNow()
    }

    companion object {
        private const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_WAS_EDIT = "was_edit"

        /** PDF, Word, and text files — not images (use the photo picker for those). */
        private val SYLLABUS_DOCUMENT_MIME_TYPES = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/*",
            "application/*",
        )

        fun createIntent(context: Context): Intent =
            Intent(context, AddCourseActivity::class.java)

        fun editIntent(context: Context, courseId: String): Intent =
            Intent(context, AddCourseActivity::class.java)
                .putExtra(EXTRA_COURSE_ID, courseId)
    }
}
