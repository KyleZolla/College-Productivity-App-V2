package com.example.productivityapp

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar

class AddTaskActivity : AppCompatActivity() {
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

        var selectedDueDate: LocalDate? = null
        val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

        dueDateRow.setOnClickListener {
            val cal = Calendar.getInstance()
            val initial = selectedDueDate
            val initialYear = initial?.year ?: cal.get(Calendar.YEAR)
            val initialMonth = (initial?.monthValue?.minus(1)) ?: cal.get(Calendar.MONTH)
            val initialDay = initial?.dayOfMonth ?: cal.get(Calendar.DAY_OF_MONTH)

            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                selectedDueDate = LocalDate.of(year, month + 1, dayOfMonth)
                dueDateValue.text = selectedDueDate?.format(dateFormatter) ?: getString(R.string.due_date_not_set)
            }, initialYear, initialMonth, initialDay).show()
        }

        findViewById<Button>(R.id.createTaskButton).setOnClickListener {
            // TODO: create the task. For now just go to Tasks tab.
            startActivity(
                Intent(this, HomeActivity::class.java).apply {
                    putExtra(HomeActivity.EXTRA_SELECTED_TAB, HomeActivity.TAB_TASKS)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            finish()
        }

        findViewById<Button>(R.id.cancelAddTaskButton).setOnClickListener {
            finish()
        }
    }
}

