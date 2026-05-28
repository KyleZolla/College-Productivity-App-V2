package com.example.productivityapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    private lateinit var selectedSyllabusLabel: TextView

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

        findViewById<MaterialToolbar>(R.id.addCourseToolbar).setNavigationOnClickListener { finish() }

        val nameInput = findViewById<TextInputEditText>(R.id.courseNameInput)
        val levelChips = findViewById<ChipGroup>(R.id.courseLevelChips)
        val saveButton = findViewById<MaterialButton>(R.id.saveCourseButton)
        selectedSyllabusLabel = findViewById(R.id.selectedSyllabusLabel)

        findViewById<MaterialButton>(R.id.chooseSyllabusDocButton).setOnClickListener {
            pickDocumentLauncher.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.chooseSyllabusPhotoButton).setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }

        saveButton.setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(this, R.string.error_course_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val level = readSelectedLevel(levelChips)
            if (level.isNullOrBlank()) {
                Toast.makeText(this, R.string.error_course_level_required, Toast.LENGTH_SHORT).show()
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

            val docUri = selectedDocUri
            val photoUri = selectedPhotoUri
            saveButton.isEnabled = false
            val originalText = saveButton.text
            saveButton.text = getString(R.string.status_saving_course)

            networkExecutor.execute {
                val syllabusText = extractSyllabusText(docUri, photoUri)

                when (
                    val result = SupabaseCoursesApi.insertCourse(
                        accessToken = accessToken,
                        userId = userId,
                        name = name,
                        level = level,
                        syllabus = syllabusText,
                    )
                ) {
                    is SupabaseCoursesApi.InsertResult.Success -> runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                    is SupabaseCoursesApi.InsertResult.Failure -> runOnUiThread {
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
    }

    private fun refreshSyllabusSelectionLabel() {
        val doc = selectedDocUri
        val photo = selectedPhotoUri
        selectedSyllabusLabel.text = when {
            doc != null && photo != null -> getString(
                R.string.course_syllabus_doc_and_photo_selected,
                doc.lastPathSegment ?: doc.toString(),
                photo.lastPathSegment ?: photo.toString(),
            )
            doc != null -> getString(
                R.string.add_task_ai_doc_selected,
                doc.lastPathSegment ?: doc.toString(),
            )
            photo != null -> getString(
                R.string.add_task_ai_photo_selected,
                photo.lastPathSegment ?: photo.toString(),
            )
            else -> getString(R.string.course_syllabus_none)
        }
    }

    private fun readSelectedLevel(group: ChipGroup): String? {
        val id = group.checkedChipId
        if (id == View.NO_ID) return null
        return group.findViewById<Chip>(id)?.text?.toString()
    }

    private fun extractSyllabusText(docUri: Uri?, photoUri: Uri?): String? {
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
}
