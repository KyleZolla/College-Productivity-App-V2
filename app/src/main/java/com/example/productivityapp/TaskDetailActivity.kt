package com.example.productivityapp

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

class TaskDetailActivity : AppCompatActivity() {

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
        val id = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        if (title.isBlank() || id.isBlank()) {
            finish()
            return
        }

        findViewById<TextView>(R.id.taskDetailName).text = title
        findViewById<TextView>(R.id.taskDetailDueDate).text =
            due.ifBlank { getString(R.string.due_date_not_set) }
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_TASK_DUE_DISPLAY = "extra_task_due_display"
    }
}
