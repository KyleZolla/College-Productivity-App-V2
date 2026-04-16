package com.example.productivityapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class HomeActivity : AppCompatActivity() {

    private enum class Tab { Home, Tasks, Profile }

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var currentTab: Tab = Tab.Home

    private lateinit var panelHome: View
    private lateinit var panelTasks: View
    private lateinit var panelProfile: View
    private lateinit var tasksRecyclerView: RecyclerView
    private lateinit var tasksEmptyView: TextView
    private lateinit var tasksLoading: ProgressBar
    private lateinit var tasksAdapter: TaskListAdapter
    private lateinit var navHome: LinearLayout
    private lateinit var navTasks: LinearLayout
    private lateinit var navProfile: LinearLayout
    private lateinit var navHomeIcon: ImageView
    private lateinit var navTasksIcon: ImageView
    private lateinit var navProfileIcon: ImageView
    private lateinit var navHomeLabel: TextView
    private lateinit var navTasksLabel: TextView
    private lateinit var navProfileLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        val root = findViewById<View>(R.id.homeRoot)
        val bottomBar = findViewById<LinearLayout>(R.id.bottomNavBar)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }

        panelHome = findViewById(R.id.panelHome)
        panelTasks = findViewById(R.id.panelTasks)
        panelProfile = findViewById(R.id.panelProfile)

        tasksRecyclerView = findViewById(R.id.tasksRecyclerView)
        tasksEmptyView = findViewById(R.id.tasksEmptyView)
        tasksLoading = findViewById(R.id.tasksLoading)
        tasksRecyclerView.layoutManager = LinearLayoutManager(this)
        val dueDisplayFormat = DateTimeFormatter.ofPattern("MMM d, yyyy")
        tasksAdapter = TaskListAdapter { task ->
            val dueDisplay = task.dueDate?.format(dueDisplayFormat)
                ?: getString(R.string.due_date_not_set)
            startActivity(
                Intent(this, TaskDetailActivity::class.java).apply {
                    putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.id)
                    putExtra(TaskDetailActivity.EXTRA_TASK_TITLE, task.title)
                    putExtra(TaskDetailActivity.EXTRA_TASK_DUE_DISPLAY, dueDisplay)
                }
            )
        }
        tasksRecyclerView.adapter = tasksAdapter

        findViewById<Button>(R.id.logOutButton).setOnClickListener {
            SessionManager.clearSession(this)
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    putExtra("oauth_result_message", getString(R.string.status_logged_out))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            finish()
        }

        navHome = findViewById(R.id.navHome)
        navTasks = findViewById(R.id.navTasks)
        navProfile = findViewById(R.id.navProfile)
        navHomeIcon = findViewById(R.id.navHomeIcon)
        navTasksIcon = findViewById(R.id.navTasksIcon)
        navProfileIcon = findViewById(R.id.navProfileIcon)
        navHomeLabel = findViewById(R.id.navHomeLabel)
        navTasksLabel = findViewById(R.id.navTasksLabel)
        navProfileLabel = findViewById(R.id.navProfileLabel)

        navHome.setOnClickListener { showTab(Tab.Home) }
        navTasks.setOnClickListener { showTab(Tab.Tasks) }
        navProfile.setOnClickListener { showTab(Tab.Profile) }

        findViewById<FloatingActionButton>(R.id.fabAddTask).setOnClickListener {
            startActivity(Intent(this, AddTaskActivity::class.java))
        }

        applyRequestedTab(intent)
    }

    override fun onResume() {
        super.onResume()
        if (currentTab == Tab.Tasks) {
            loadTasks()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyRequestedTab(intent)
    }

    private fun applyRequestedTab(intent: Intent?) {
        val requested = intent?.getStringExtra(EXTRA_SELECTED_TAB)
        val tab = when (requested) {
            TAB_TASKS -> Tab.Tasks
            TAB_PROFILE -> Tab.Profile
            else -> Tab.Home
        }
        showTab(tab)
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        panelHome.visibility = if (tab == Tab.Home) View.VISIBLE else View.GONE
        panelTasks.visibility = if (tab == Tab.Tasks) View.VISIBLE else View.GONE
        panelProfile.visibility = if (tab == Tab.Profile) View.VISIBLE else View.GONE

        if (tab == Tab.Tasks) {
            loadTasks()
        }

        val primaryColor = MaterialColors.getColor(navHome, com.google.android.material.R.attr.colorPrimary)
        val muted = MaterialColors.getColor(navHome, com.google.android.material.R.attr.colorOnSurfaceVariant)

        fun style(icon: ImageView, label: TextView, selected: Boolean) {
            val c = if (selected) primaryColor else muted
            icon.imageTintList = android.content.res.ColorStateList.valueOf(c)
            label.setTextColor(c)
        }

        style(navHomeIcon, navHomeLabel, tab == Tab.Home)
        style(navTasksIcon, navTasksLabel, tab == Tab.Tasks)
        style(navProfileIcon, navProfileLabel, tab == Tab.Profile)
    }

    private fun loadTasks() {
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            tasksAdapter.submitList(emptyList())
            tasksEmptyView.text = getString(R.string.error_task_not_signed_in)
            tasksEmptyView.visibility = View.VISIBLE
            tasksRecyclerView.visibility = View.GONE
            tasksLoading.visibility = View.GONE
            return
        }

        tasksLoading.visibility = View.VISIBLE
        tasksEmptyView.visibility = View.GONE
        tasksRecyclerView.visibility = View.INVISIBLE

        networkExecutor.execute {
            when (val result = SupabaseTasksApi.listTasks(token)) {
                is SupabaseTasksApi.ListResult.Success -> runOnUiThread {
                    tasksLoading.visibility = View.GONE
                    tasksAdapter.submitList(result.tasks)
                    if (result.tasks.isEmpty()) {
                        tasksEmptyView.text = getString(R.string.tasks_empty)
                        tasksEmptyView.visibility = View.VISIBLE
                        tasksRecyclerView.visibility = View.GONE
                    } else {
                        tasksEmptyView.visibility = View.GONE
                        tasksRecyclerView.visibility = View.VISIBLE
                    }
                }
                is SupabaseTasksApi.ListResult.Failure -> runOnUiThread {
                    tasksLoading.visibility = View.GONE
                    tasksRecyclerView.visibility = View.GONE
                    tasksAdapter.submitList(emptyList())
                    tasksEmptyView.text =
                        getString(R.string.error_tasks_load_failed) + "\n" + result.message
                    tasksEmptyView.visibility = View.VISIBLE
                    Toast.makeText(
                        this,
                        getString(R.string.error_tasks_load_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_SELECTED_TAB = "selected_tab"
        const val TAB_HOME = "home"
        const val TAB_TASKS = "tasks"
        const val TAB_PROFILE = "profile"
    }
}
