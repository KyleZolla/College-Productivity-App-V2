package com.example.productivityapp

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
import java.util.concurrent.Executors

class CompletedTasksActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loading: ProgressBar
    private lateinit var adapter: TaskListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_completed_tasks)

        val root = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.completedTasksRoot)
        val toolbar = findViewById<MaterialToolbar>(R.id.completedTasksToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.completedTasksRecyclerView)
        emptyView = findViewById(R.id.completedTasksEmptyView)
        loading = findViewById(R.id.completedTasksLoading)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = TaskListAdapter { task ->
            startActivity(TaskDetailActivity.createIntent(this, task))
        }
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadCompletedTasks()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }

    private fun loadCompletedTasks() {
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            adapter.submitList(emptyList())
            emptyView.text = getString(R.string.error_task_not_signed_in)
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            loading.visibility = View.GONE
            return
        }

        loading.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.INVISIBLE

        networkExecutor.execute {
            when (val result = SupabaseTasksApi.listTasks(token, TaskListFilter.COMPLETED)) {
                is SupabaseTasksApi.ListResult.Success -> runOnUiThread {
                    loading.visibility = View.GONE
                    adapter.submitList(result.tasks)
                    if (result.tasks.isEmpty()) {
                        emptyView.text = getString(R.string.tasks_completed_empty)
                        emptyView.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyView.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                }
                is SupabaseTasksApi.ListResult.Failure -> runOnUiThread {
                    loading.visibility = View.GONE
                    recyclerView.visibility = View.GONE
                    adapter.submitList(emptyList())
                    emptyView.text = getString(R.string.tasks_completed_load_failed) + "\n" + result.message
                    emptyView.visibility = View.VISIBLE
                    Toast.makeText(
                        this,
                        getString(R.string.tasks_completed_load_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
