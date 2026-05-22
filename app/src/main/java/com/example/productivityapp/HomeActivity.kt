package com.example.productivityapp

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.comparisons.compareBy
import kotlin.comparisons.nullsLast
import kotlin.math.roundToInt

class HomeActivity : AppCompatActivity() {

    private enum class Tab { Home, Tasks, Profile }

    private val openTaskDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        if (result.data?.getBooleanExtra(TaskDetailActivity.EXTRA_RESULT_TASK_DELETED, false) != true) {
            return@registerForActivityResult
        }
        if (SessionManager.getAccessToken(this).isNullOrBlank()) return@registerForActivityResult
        refreshHomeHeader()
        loadHomeUpcoming(showLoading = false)
        loadTasks(showLoading = false)
    }

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

    private lateinit var homeDateLine: TextView
    private lateinit var homeGreetingLine: TextView
    private lateinit var homeTodayPlanSection: LinearLayout
    private lateinit var homeTodayPlanProgressBlock: LinearLayout
    private lateinit var homeTodayPlanStepsSummary: TextView
    private lateinit var homeTodayPlanHoursSummary: TextView
    private lateinit var homeTodayPlanHoursProgress: ProgressBar
    private lateinit var homeTodayPlanEmpty: TextView
    private lateinit var homeTodayPlanCompletedTodayHost: LinearLayout
    private lateinit var homeTodayPlanFutureReviewBeforeFocusHost: LinearLayout
    private lateinit var homeTodayPlanCompletedByDayHost: LinearLayout
    private lateinit var homeTodayPlanGroups: LinearLayout
    private lateinit var homeTodayPlanToggle: MaterialButton
    private lateinit var homeGetAheadSection: LinearLayout
    private lateinit var homeGetAheadDateLine: TextView
    private lateinit var homeGetAheadProgressBlock: LinearLayout
    private lateinit var homeGetAheadStepsSummary: TextView
    private lateinit var homeGetAheadHoursSummary: TextView
    private lateinit var homeGetAheadHoursProgress: ProgressBar
    private lateinit var homeGetAheadGroups: LinearLayout
    private lateinit var homeUpcomingLoading: ProgressBar

    private var homeTasksSnapshot: List<SupabaseTasksApi.TaskRow> = emptyList()
    /** Task ids currently shown in Coming up; stable until a task fully completes or data reloads. */
    private var homeUpcomingDisplayedIds: List<String> = emptyList()
    private var homeRoadmapPatchInFlight = false
    private var homeTodayPlanExpanded = false
    private val homeCompletedReviewDaysExpanded = mutableSetOf<String>()
    private val homeTodayPlanPinnedCheckedKeys = mutableSetOf<String>()
    /** Simple tasks marked complete via Today's Plan today (session); keeps them in the plan after reload. */
    private val homeSimpleCompletedTodayIds = mutableSetOf<String>()
    private var homeGetAheadFocusDate: LocalDate? = null
    private val homeGetAheadPinnedCheckedKeys = mutableSetOf<String>()
    private lateinit var homeUpcomingEmpty: TextView
    private lateinit var homeUpcomingCards: LinearLayout
    private lateinit var homeViewAllTasks: TextView

    private var achievementsUserId: String? = null

    private lateinit var profileDisplayName: TextView
    private lateinit var profileEmail: TextView

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
        tasksAdapter = TaskListAdapter(
            onSimpleTaskToggled = { task, checked -> onSimpleTaskToggled(task, checked) },
        ) { task -> openTaskDetail(task) }
        tasksRecyclerView.adapter = tasksAdapter

        findViewById<TextView>(R.id.tasksViewCompletedButton).setOnClickListener {
            startActivity(Intent(this, CompletedTasksActivity::class.java))
        }
        findViewById<TextView>(R.id.tasksViewDeletedButton).setOnClickListener {
            startActivity(Intent(this, DeletedTasksActivity::class.java))
        }

        homeDateLine = findViewById(R.id.homeDateLine)
        homeGreetingLine = findViewById(R.id.homeGreetingLine)
        homeTodayPlanSection = findViewById(R.id.homeTodayPlanSection)
        homeTodayPlanProgressBlock = findViewById(R.id.homeTodayPlanProgressBlock)
        homeTodayPlanStepsSummary = findViewById(R.id.homeTodayPlanStepsSummary)
        homeTodayPlanHoursSummary = findViewById(R.id.homeTodayPlanHoursSummary)
        homeTodayPlanHoursProgress = findViewById(R.id.homeTodayPlanHoursProgress)
        homeTodayPlanEmpty = findViewById(R.id.homeTodayPlanEmpty)
        homeTodayPlanCompletedTodayHost = findViewById(R.id.homeTodayPlanCompletedTodayHost)
        homeTodayPlanFutureReviewBeforeFocusHost =
            findViewById(R.id.homeTodayPlanFutureReviewBeforeFocusHost)
        homeTodayPlanCompletedByDayHost = findViewById(R.id.homeTodayPlanCompletedByDayHost)
        homeTodayPlanGroups = findViewById(R.id.homeTodayPlanGroups)
        homeTodayPlanToggle = findViewById(R.id.homeTodayPlanToggle)
        homeTodayPlanToggle.setOnClickListener {
            homeTodayPlanExpanded = !homeTodayPlanExpanded
            bindHomeTodayPlan(homeTasksSnapshot)
        }
        homeGetAheadSection = findViewById(R.id.homeGetAheadSection)
        homeGetAheadDateLine = findViewById(R.id.homeGetAheadDateLine)
        homeGetAheadProgressBlock = findViewById(R.id.homeGetAheadProgressBlock)
        homeGetAheadStepsSummary = findViewById(R.id.homeGetAheadStepsSummary)
        homeGetAheadHoursSummary = findViewById(R.id.homeGetAheadHoursSummary)
        homeGetAheadHoursProgress = findViewById(R.id.homeGetAheadHoursProgress)
        homeGetAheadGroups = findViewById(R.id.homeGetAheadGroups)
        homeUpcomingLoading = findViewById(R.id.homeUpcomingLoading)
        homeUpcomingEmpty = findViewById(R.id.homeUpcomingEmpty)
        homeUpcomingCards = findViewById(R.id.homeUpcomingCards)
        homeViewAllTasks = findViewById(R.id.homeViewAllTasks)
        homeViewAllTasks.setOnClickListener { showTab(Tab.Tasks) }

        profileDisplayName = findViewById(R.id.profileDisplayName)
        profileEmail = findViewById(R.id.profileEmail)

        findViewById<MaterialButton>(R.id.logOutButton).setOnClickListener {
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
        FcmTokenRegistrar.syncIfLoggedIn(this)
    }

    override fun onResume() {
        super.onResume()
        when (currentTab) {
            Tab.Tasks -> loadTasks(showLoading = true)
            Tab.Home -> {
                refreshHomeHeader()
                loadHomeUpcoming(showLoading = true)
            }
            Tab.Profile -> refreshProfileCard()
        }
        val token = SessionManager.getAccessToken(this)
        if (!token.isNullOrBlank()) {
            // Preload earned achievements (best-effort).
            networkExecutor.execute {
                achievementsUserId = SupabaseUserId.resolveUserId(token)
                achievementsUserId?.let { AchievementManager.ensureLoaded(token, it) }
            }
            maybeShowStreakOnAppLaunch(token)
            // Keep home cards and task list aligned with Supabase (e.g. after status edit on detail).
            when (currentTab) {
                Tab.Home -> loadTasks(showLoading = false)
                Tab.Tasks -> loadHomeUpcoming(showLoading = false)
                Tab.Profile -> {
                    loadHomeUpcoming(showLoading = false)
                    loadTasks(showLoading = false)
                }
            }
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

        when (tab) {
            Tab.Tasks -> loadTasks(showLoading = true)
            Tab.Home -> {
                refreshHomeHeader()
                loadHomeUpcoming(showLoading = true)
            }
            Tab.Profile -> refreshProfileCard()
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

    private fun refreshProfileCard() {
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            profileDisplayName.text = getString(R.string.profile_name_fallback)
            profileEmail.text = ""
            return
        }
        val name = AuthUserDisplayName.displayNameForProfile(token)
        profileDisplayName.text = name.ifBlank { getString(R.string.profile_name_fallback) }
        profileEmail.text = AuthUserDisplayName.emailFromAccessToken(token)
    }

    private fun refreshHomeHeader() {
        val today = LocalDate.now()
        homeDateLine.text = today.format(
            DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
        )
        val token = SessionManager.getAccessToken(this)
        val name = token?.let { AuthUserDisplayName.firstNameFromAccessToken(it) } ?: "there"
        val hour = LocalTime.now().hour
        val res = when (hour) {
            in 5..11 -> R.string.home_greet_morning
            in 12..16 -> R.string.home_greet_afternoon
            in 17..22 -> R.string.home_greet_evening
            else -> R.string.home_greet_late
        }
        homeGreetingLine.text = getString(res, name)
    }

    private fun loadHomeUpcoming(showLoading: Boolean = true) {
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            homeUpcomingLoading.visibility = View.GONE
            homeUpcomingCards.removeAllViews()
            homeUpcomingCards.visibility = View.GONE
            homeTodayPlanSection.visibility = View.GONE
            homeUpcomingEmpty.text = getString(R.string.error_task_not_signed_in)
            homeUpcomingEmpty.visibility = View.VISIBLE
            return
        }

        if (showLoading) {
            homeUpcomingLoading.visibility = View.VISIBLE
            homeUpcomingEmpty.visibility = View.GONE
            homeUpcomingCards.visibility = View.GONE
            homeTodayPlanSection.visibility = View.GONE
        }

        networkExecutor.execute {
            when (
                val active = SupabaseTasksApi.listTasks(token, TaskListFilter.ACTIVE)
            ) {
                is SupabaseTasksApi.ListResult.Success -> {
                    val completedRm = SupabaseTasksApi.listTasks(token, TaskListFilter.COMPLETED_WITH_ROADMAP)
                    val completedAll = SupabaseTasksApi.listTasks(token, TaskListFilter.COMPLETED)
                    val extra = when (completedRm) {
                        is SupabaseTasksApi.ListResult.Success -> completedRm.tasks
                        is SupabaseTasksApi.ListResult.Failure -> emptyList()
                    }
                    val today = LocalDate.now()
                    val simpleCompletedForPlan = when (completedAll) {
                        is SupabaseTasksApi.ListResult.Success -> completedAll.tasks.filter { task ->
                            TaskKind.isSimpleTask(task) &&
                                TodayPlanWork.simpleCompletedInPlanToday(
                                    task.id,
                                    homeTodayPlanPinnedCheckedKeys,
                                    homeSimpleCompletedTodayIds,
                                ) &&
                                TodayPlanWork.simpleTaskDueLocalDate(task)?.let { !it.isAfter(today) } == true
                        }
                        is SupabaseTasksApi.ListResult.Failure -> emptyList()
                    }
                    val simpleFutureCompletedForGetAhead = when (completedAll) {
                        is SupabaseTasksApi.ListResult.Success -> completedAll.tasks.filter { task ->
                            TaskKind.isSimpleTask(task) &&
                                TodayPlanWork.simpleTaskDueLocalDate(task)?.isAfter(today) == true &&
                                "${task.id}:simple" in homeGetAheadPinnedCheckedKeys
                        }
                        is SupabaseTasksApi.ListResult.Failure -> emptyList()
                    }
                    val mergedForPlan = (active.tasks + extra + simpleCompletedForPlan + simpleFutureCompletedForGetAhead)
                        .distinctBy { it.id }
                    runOnUiThread {
                        if (showLoading) homeUpcomingLoading.visibility = View.GONE
                        homeUpcomingDisplayedIds = emptyList()
                        resetHomeUpcomingPreview(active.tasks)
                        bindHomeTodayPlan(mergedForPlan)
                    }
                }
                is SupabaseTasksApi.ListResult.Failure -> runOnUiThread {
                    if (showLoading) homeUpcomingLoading.visibility = View.GONE
                    if (showLoading) {
                        homeTodayPlanSection.visibility = View.GONE
                        homeUpcomingCards.removeAllViews()
                        homeUpcomingCards.visibility = View.GONE
                        homeUpcomingEmpty.text =
                            getString(R.string.home_upcoming_load_failed) + "\n" + active.message
                        homeUpcomingEmpty.visibility = View.VISIBLE
                        Toast.makeText(
                            this,
                            getString(R.string.home_upcoming_load_failed) + "\n" + active.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun collectFuturePlanEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<TodayPlanEntry> =
        TodayPlanWork.collectFuturePlanEntries(tasks, today, homeGetAheadPinnedCheckedKeys)

    /** All roadmap steps on or before [today] (raw pool; includes completed overdue). */
    private fun todayPlanScopeEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<TodayPlanEntry> = TodayPlanWork.collectTodayPlanScopeEntries(tasks, today)

    /**
     * **Today's plan** work set: unchecked steps from before [today], plus every step scheduled on [today]
     * (done or not). Completed overdue steps are excluded — they are not part of today's workload.
     */
    private fun todayPlanWorkEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<TodayPlanEntry> {
        val scope = todayPlanScopeEntries(tasks, today)
        val incompleteOverdue = scope.filter { it.recommendedOn.isBefore(today) && !it.isCompleted }
        val todayEntries = scope.filter { entry ->
            when {
                entry.isSimple -> entry.recommendedOn == today && !entry.isCompleted
                else -> entry.recommendedOn == today
            }
        }
        return incompleteOverdue + todayEntries
    }

    /**
     * Progress bar scope: incomplete overdue/today work plus steps finished today (including overdue
     * catch-up). Checked-off overdue steps remain here so done hours accumulate correctly.
     */
    private fun todayPlanProgressEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<TodayPlanEntry> {
        val zone = ZoneId.systemDefault()
        val pinned = homeTodayPlanPinnedCheckedKeys
        val completedToday = homeSimpleCompletedTodayIds
        return todayPlanScopeEntries(tasks, today).filter { entry ->
            if (entry.isSimple) {
                TodayPlanWork.simpleCountsTowardTodayProgress(
                    entry.task,
                    entry.recommendedOn,
                    today,
                    pinned,
                    completedToday,
                )
            } else {
                TodayPlanWork.countsTowardTodayProgress(entry.step!!, entry.recommendedOn, today, zone)
            }
        }
    }

    private fun isTodayWorkPlanComplete(
        tasksSnapshot: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): Boolean {
        val work = todayPlanWorkEntries(tasksSnapshot, today)
        return work.isNotEmpty() && work.all { it.isCompleted }
    }

    /**
     * “View what you completed” for [today]: roadmap steps and simple tasks finished today.
     */
    private fun todayPlanCompletedReviewEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<TodayPlanEntry> {
        val zone = ZoneId.systemDefault()
        val pinned = homeTodayPlanPinnedCheckedKeys
        val completedToday = homeSimpleCompletedTodayIds
        return todayPlanScopeEntries(tasks, today).filter { entry ->
            when {
                entry.isSimple -> entry.isCompleted &&
                    TodayPlanWork.simpleCompletedInPlanToday(entry.task.id, pinned, completedToday)
                else -> TodayPlanWork.wasCompletedToday(entry.step!!, today, zone)
            }
        }
    }

    private fun sortTodayPlanEntries(entries: List<TodayPlanEntry>, today: LocalDate): List<TodayPlanEntry> =
        TodayPlanWork.sortTodayPlanEntries(entries, today)

    /** Overdue tasks (by due date) rise to the top within the past-due step bucket. */
    private fun overdueAwareTaskRankComparator(): Comparator<TodayPlanEntry> =
        Comparator { a, b ->
            val oa = DueDateHumanLabel.isOverdue(a.task.dueDate, a.task.status)
            val ob = DueDateHumanLabel.isOverdue(b.task.dueDate, b.task.status)
            when {
                oa && !ob -> -1
                !oa && ob -> 1
                else -> todayTaskRankComparator().compare(a, b)
            }
        }

    /**
     * Ordered future-day review buckets (excluding today's bucket): include days where at least one
     * step is completed so "started early" work stays visible; exclude the focused Get ahead day
     * while that section is visible to avoid duplicate presentation.
     */
    private fun buildCompletedReviewDays(
        todayEntries: List<TodayPlanEntry>,
        futureEntries: List<TodayPlanEntry>,
        today: LocalDate,
        getAheadVisible: Boolean,
        focusDay: LocalDate?,
    ): List<Pair<LocalDate, List<TodayPlanEntry>>> {
        val out = ArrayList<Pair<LocalDate, List<TodayPlanEntry>>>()
        if (todayEntries.isNotEmpty()) {
            out.add(today to sortTodayPlanEntries(todayEntries, today))
        }
        futureEntries.groupBy { it.recommendedOn }.toSortedMap().forEach { (day, list) ->
            // Keep a future day visible once it has any completed work, even if not fully done yet.
            if (list.isEmpty() || list.none { it.isCompleted }) return@forEach
            if (getAheadVisible && focusDay != null && day == focusDay) return@forEach
            out.add(day to sortGetAheadDayEntries(list))
        }
        return out
    }

    private fun formatTodayPlanCollapsedEmptyText(): String =
        getString(R.string.home_today_plan_empty_ahead)

    private fun pruneCompletedReviewExpandedDays(validDays: Set<LocalDate>) {
        homeCompletedReviewDaysExpanded.removeAll { key ->
            runCatching { LocalDate.parse(key) }.getOrNull() !in validDays
        }
    }

    private fun bindCompletedReviewByDay(
        reviewDays: List<Pair<LocalDate, List<TodayPlanEntry>>>,
        today: LocalDate,
        getAheadVisible: Boolean,
        focusDay: LocalDate?,
        hasRemainingPlanWork: Boolean = false,
    ) {
        homeTodayPlanCompletedTodayHost.removeAllViews()
        homeTodayPlanFutureReviewBeforeFocusHost.removeAllViews()
        homeTodayPlanCompletedByDayHost.removeAllViews()
        if (reviewDays.isEmpty()) {
            homeTodayPlanCompletedTodayHost.visibility = View.GONE
            homeTodayPlanFutureReviewBeforeFocusHost.visibility = View.GONE
            homeTodayPlanCompletedByDayHost.visibility = View.GONE
            return
        }
        var hasTodayBlocks = false
        var hasFutureBeforeFocusBlocks = false
        var hasFutureBlocks = false
        val inflater = LayoutInflater.from(this)
        val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
        val gapBetweenReviewBlocksPx = (12 * resources.displayMetrics.density).toInt()
        for ((day, list) in reviewDays) {
            val targetHost = when {
                day == today -> homeTodayPlanCompletedTodayHost
                getAheadVisible && focusDay != null && day.isBefore(focusDay) ->
                    homeTodayPlanFutureReviewBeforeFocusHost
                else -> homeTodayPlanCompletedByDayHost
            }
            val block = inflater.inflate(R.layout.item_home_completed_day_review, targetHost, false)
            if (targetHost.childCount > 0) {
                val lp = block.layoutParams as? LinearLayout.LayoutParams
                    ?: LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                lp.topMargin = gapBetweenReviewBlocksPx
                block.layoutParams = lp
            }
            val title = block.findViewById<TextView>(R.id.homeCompletedDayTitle)
            val progressBlock = block.findViewById<LinearLayout>(R.id.homeCompletedDayProgressBlock)
            val allDoneBanner = block.findViewById<TextView>(R.id.homeCompletedDayAllDoneBanner)
            val stepsSummary = block.findViewById<TextView>(R.id.homeCompletedDayStepsSummary)
            val hoursSummary = block.findViewById<TextView>(R.id.homeCompletedDayHoursSummary)
            val hoursProgress = block.findViewById<ProgressBar>(R.id.homeCompletedDayHoursProgress)
            val toggle = block.findViewById<MaterialButton>(R.id.homeCompletedDayToggle)
            val stepsHost = block.findViewById<LinearLayout>(R.id.homeCompletedDaySteps)
            title.text = if (day == today) {
                getString(R.string.home_today_plan_review_title_today)
            } else {
                day.format(dateFmt)
            }
            val allDoneOnDay = list.all { it.isCompleted }
            val partiallyDoneFutureDay = day.isAfter(today) && !allDoneOnDay
            val compactTodayReview = day == today && hasRemainingPlanWork
            if (compactTodayReview) {
                title.visibility = View.GONE
                progressBlock.visibility = View.GONE
            } else if (day.isAfter(today) || day == today) {
                title.visibility = View.VISIBLE
                progressBlock.visibility = View.VISIBLE
                bindPlanProgress(
                    entries = list,
                    stepsSummary = stepsSummary,
                    hoursSummary = hoursSummary,
                    hoursProgress = hoursProgress,
                    stepsSummaryRes = if (day == today) {
                        R.string.home_today_plan_steps_summary
                    } else {
                        R.string.home_plan_steps_summary
                    },
                )
            } else {
                title.visibility = View.VISIBLE
                progressBlock.visibility = View.GONE
            }
            when {
                partiallyDoneFutureDay -> allDoneBanner.visibility = View.GONE
                compactTodayReview -> allDoneBanner.visibility = View.GONE
                day == today && list.isNotEmpty() -> {
                    allDoneBanner.visibility = View.VISIBLE
                    allDoneBanner.text = getString(R.string.home_today_plan_all_done_banner_today)
                }
                day.isAfter(today) && allDoneOnDay -> {
                    allDoneBanner.visibility = View.VISIBLE
                    allDoneBanner.text = getString(
                        R.string.home_today_plan_progression_day_done,
                        day.format(dateFmt),
                    )
                }
                else -> allDoneBanner.visibility = View.GONE
            }
            val dayKey = day.toString()
            val expanded = dayKey in homeCompletedReviewDaysExpanded
            if (partiallyDoneFutureDay) {
                toggle.visibility = View.GONE
                stepsHost.visibility = View.VISIBLE
                bindPlanStepRows(stepsHost, list)
            } else {
                toggle.visibility = View.VISIBLE
                toggle.text = when {
                    expanded -> getString(R.string.home_today_plan_show_less)
                    compactTodayReview -> getString(R.string.home_today_plan_view_completed_count, list.size)
                    day.isAfter(today) && allDoneOnDay -> getString(R.string.home_today_plan_view_completed_ahead)
                    else -> getString(R.string.home_today_plan_view_completed)
                }
                if (expanded) {
                    stepsHost.visibility = View.VISIBLE
                    bindPlanStepRows(stepsHost, list)
                } else {
                    stepsHost.visibility = View.GONE
                    stepsHost.removeAllViews()
                }
                toggle.setOnClickListener {
                    if (dayKey in homeCompletedReviewDaysExpanded) {
                        homeCompletedReviewDaysExpanded.remove(dayKey)
                    } else {
                        homeCompletedReviewDaysExpanded.add(dayKey)
                    }
                    bindHomeTodayPlan(homeTasksSnapshot)
                }
            }
            targetHost.addView(block)
            when (targetHost) {
                homeTodayPlanCompletedTodayHost -> hasTodayBlocks = true
                homeTodayPlanFutureReviewBeforeFocusHost -> hasFutureBeforeFocusBlocks = true
                else -> hasFutureBlocks = true
            }
        }
        homeTodayPlanCompletedTodayHost.visibility = if (hasTodayBlocks) View.VISIBLE else View.GONE
        homeTodayPlanFutureReviewBeforeFocusHost.visibility =
            if (hasFutureBeforeFocusBlocks) View.VISIBLE else View.GONE
        homeTodayPlanCompletedByDayHost.visibility = if (hasFutureBlocks) View.VISIBLE else View.GONE
    }

    private fun todayPlanEntryKey(entry: TodayPlanEntry): String = entry.entryKey()

    private fun prunePinnedTodayPlanKeys(entries: List<TodayPlanEntry>) {
        val byKey = entries.associateBy { todayPlanEntryKey(it) }
        homeTodayPlanPinnedCheckedKeys.removeAll { key ->
            val e = byKey[key]
            e == null || !e.isCompleted
        }
    }

    private fun buildCollapsedVisibleEntries(
        entries: List<TodayPlanEntry>,
        today: LocalDate,
    ): List<TodayPlanEntry> {
        prunePinnedTodayPlanKeys(entries)
        val sorted = sortTodayPlanEntries(entries, today)
        val pinnedKeys = homeTodayPlanPinnedCheckedKeys
        var uncheckedShown = 0
        val out = ArrayList<TodayPlanEntry>()
        for (entry in sorted) {
            when {
                !entry.isCompleted && uncheckedShown < 2 -> {
                    out.add(entry)
                    uncheckedShown++
                }
                entry.isCompleted && todayPlanEntryKey(entry) in pinnedKeys -> out.add(entry)
            }
        }
        return out.sortedWith(compareBy({ !it.isSimple }, { sorted.indexOfFirst { e -> todayPlanEntryKey(e) == todayPlanEntryKey(it) } }))
    }

    private fun pruneGetAheadPinnedKeys(futureEntries: List<TodayPlanEntry>) {
        val byKey = futureEntries.associateBy { todayPlanEntryKey(it) }
        homeGetAheadPinnedCheckedKeys.removeAll { key ->
            val e = byKey[key]
            e == null || !e.isCompleted
        }
    }

    private fun todayTaskRankComparator(): Comparator<TodayPlanEntry> =
        compareBy<TodayPlanEntry> { it.step!!.priority }
            .thenByDescending { it.step!!.estimatedHours ?: -1.0 }
            .thenBy(nullsLast()) { it.task.dueDate }

    private fun taskLeadEntry(taskEntries: List<TodayPlanEntry>): TodayPlanEntry =
        taskEntries.filter { !it.isCompleted }.minByOrNull { it.stepIndex }
            ?: taskEntries.minBy { it.stepIndex }

    /**
     * Rank tasks first, then keep each task's roadmap sequence (`stepIndex`) intact.
     */
    private fun sortByTaskRankThenRoadmap(
        entries: List<TodayPlanEntry>,
        taskRankComparator: Comparator<TodayPlanEntry>,
    ): List<TodayPlanEntry> {
        if (entries.isEmpty()) return emptyList()
        val groups = entries.groupBy { it.task.id }.values
        val sortedGroups = groups.sortedWith(
            Comparator<List<TodayPlanEntry>> { a, b ->
                val leadA = taskLeadEntry(a)
                val leadB = taskLeadEntry(b)
                val ranked = taskRankComparator.compare(leadA, leadB)
                if (ranked != 0) ranked else leadA.task.id.compareTo(leadB.task.id)
            },
        )
        return sortedGroups.flatMap { group -> group.sortedBy { it.stepIndex } }
    }

    private fun sortGetAheadDayEntries(dayEntries: List<TodayPlanEntry>): List<TodayPlanEntry> =
        TodayPlanWork.sortFutureDayEntries(dayEntries)

    private fun resolveGetAheadFocusDate(
        futureEntries: List<TodayPlanEntry>,
        requireStartedOnFocusDay: Boolean,
    ): LocalDate? {
        val incompleteDays = futureEntries.groupBy { it.recommendedOn }
            .toSortedMap()
            .entries
            .mapNotNull { (day, list) ->
                val hasIncomplete = list.any { !it.isCompleted }
                if (!hasIncomplete) return@mapNotNull null
                if (requireStartedOnFocusDay && list.none { it.isCompleted }) return@mapNotNull null
                day
            }
        if (incompleteDays.isNotEmpty()) {
            // Earliest calendar day with any incomplete step (e.g. after unchecking “done early” work).
            return incompleteDays.first()
        }
        return homeGetAheadPinnedCheckedKeys.mapNotNull { key ->
            futureEntries.find { todayPlanEntryKey(it) == key }
        }.map { it.recommendedOn }.minOrNull()
    }

    private fun buildGetAheadCollapsedVisible(dayEntries: List<TodayPlanEntry>): List<TodayPlanEntry> {
        val sorted = sortGetAheadDayEntries(dayEntries)
        val pinnedKeys = homeGetAheadPinnedCheckedKeys
        var uncheckedShown = 0
        val out = ArrayList<TodayPlanEntry>()
        for (entry in sorted) {
            when {
                !entry.isCompleted && uncheckedShown < 2 -> {
                    out.add(entry)
                    uncheckedShown++
                }
                entry.isCompleted && todayPlanEntryKey(entry) in pinnedKeys -> out.add(entry)
            }
        }
        for (entry in sorted.filter { it.isCompleted }) {
            if (out.none { todayPlanEntryKey(it) == todayPlanEntryKey(entry) }) {
                out.add(entry)
            }
        }
        val rank = sorted.withIndex().associate { (i, e) -> todayPlanEntryKey(e) to i }
        return out.sortedBy { rank[todayPlanEntryKey(it)] ?: Int.MAX_VALUE }
    }

    /** @return true if the Get ahead block is visible after binding. */
    private fun bindGetAheadSection(
        allActiveTasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
        requireStartedOnFocusDay: Boolean = false,
    ): Boolean {
        var iterations = 0
        while (iterations++ < 400) {
            val futureEntries = collectFuturePlanEntries(allActiveTasks, today)
            pruneGetAheadPinnedKeys(futureEntries)
            if (futureEntries.isEmpty()) {
                homeGetAheadSection.visibility = View.GONE
                homeGetAheadProgressBlock.visibility = View.GONE
                homeGetAheadGroups.removeAllViews()
                homeGetAheadFocusDate = null
                return false
            }
            val focus = resolveGetAheadFocusDate(
                futureEntries = futureEntries,
                requireStartedOnFocusDay = requireStartedOnFocusDay,
            ) ?: run {
                homeGetAheadSection.visibility = View.GONE
                homeGetAheadProgressBlock.visibility = View.GONE
                homeGetAheadGroups.removeAllViews()
                homeGetAheadFocusDate = null
                return false
            }
            homeGetAheadFocusDate = focus
            val dayEntries = futureEntries.filter { it.recommendedOn == focus }
            val hasIncompleteOnDay = dayEntries.any { !it.isCompleted }
            val pinsOnDay = homeGetAheadPinnedCheckedKeys.any { key ->
                futureEntries.find { todayPlanEntryKey(it) == key }?.recommendedOn == focus
            }
            if (!hasIncompleteOnDay && !pinsOnDay) {
                homeGetAheadPinnedCheckedKeys.removeAll { key ->
                    futureEntries.find { todayPlanEntryKey(it) == key }?.recommendedOn == focus
                }
                homeGetAheadFocusDate = null
                continue
            }
            homeGetAheadSection.visibility = View.VISIBLE
            homeGetAheadDateLine.text = getString(
                R.string.home_get_ahead_next_day,
                focus.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())),
            )
            homeGetAheadProgressBlock.visibility = View.VISIBLE
            bindPlanProgress(
                entries = dayEntries,
                stepsSummary = homeGetAheadStepsSummary,
                hoursSummary = homeGetAheadHoursSummary,
                hoursProgress = homeGetAheadHoursProgress,
                stepsSummaryRes = R.string.home_plan_steps_summary,
            )
            val visible = buildGetAheadCollapsedVisible(dayEntries)
            bindPlanStepRows(homeGetAheadGroups, visible)
            return true
        }
        homeGetAheadSection.visibility = View.GONE
        homeGetAheadProgressBlock.visibility = View.GONE
        homeGetAheadGroups.removeAllViews()
        return false
    }

    private fun formatTodaySummaryHours(hours: Double): String {
        if (hours <= 0.0) return "0"
        val rounded = kotlin.math.round(hours * 10.0) / 10.0
        return if (kotlin.math.abs(rounded - rounded.toInt()) < 0.05) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", rounded)
        }
    }

    private fun bindPlanProgress(
        entries: List<TodayPlanEntry>,
        stepsSummary: TextView,
        hoursSummary: TextView,
        hoursProgress: ProgressBar,
        stepsSummaryRes: Int,
    ) {
        val totalSteps = entries.size
        val doneSteps = entries.count { it.isCompleted }
        stepsSummary.text = getString(stepsSummaryRes, doneSteps, totalSteps)
        var doneHours = 0.0
        var totalHours = 0.0
        for (e in entries) {
            val h = e.estimatedHoursForProgress()
            totalHours += h
            if (e.isCompleted) doneHours += h
        }
        hoursSummary.text = getString(
            R.string.home_today_plan_hours_summary,
            formatTodaySummaryHours(doneHours),
            formatTodaySummaryHours(totalHours),
        )
        val hourPercent = if (totalHours > 0) {
            ((doneHours / totalHours) * 1000.0).toInt().coerceIn(0, 1000)
        } else if (totalSteps > 0) {
            ((doneSteps.toDouble() / totalSteps.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        hoursProgress.progress = hourPercent
    }

    private fun applyTodayPlanStepCompletedStyle(
        title: TextView,
        meta: TextView,
        check: MaterialCheckBox,
        completed: Boolean,
    ) {
        val dim = if (completed) 0.5f else 1f
        title.alpha = dim
        meta.alpha = dim
        check.alpha = dim
        title.paintFlags = if (completed) {
            title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }

    private fun deriveStatusFromSteps(steps: List<RoadmapStep>): TaskStatus {
        if (steps.isEmpty()) return TaskStatus.NOT_STARTED
        val completed = steps.count { it.completed }
        return when {
            completed <= 0 -> TaskStatus.NOT_STARTED
            completed >= steps.size -> TaskStatus.COMPLETE
            else -> TaskStatus.IN_PROGRESS
        }
    }

    private fun formatTodayPlanStepMeta(step: RoadmapStep): String {
        val timePart = when (val h = step.estimatedHours) {
            null -> getString(R.string.home_today_plan_step_time_unknown)
            else -> when {
                h < 1.0 / 60.0 -> getString(R.string.home_today_plan_step_time_minutes, 1)
                h < 1.0 -> getString(
                    R.string.home_today_plan_step_time_minutes,
                    (h * 60.0).roundToInt().coerceAtLeast(1),
                )
                else -> getString(R.string.home_today_plan_step_time_hours, h)
            }
        }
        val priorityPart = when (step.priority) {
            RoadmapStep.Priority.HIGH -> getString(R.string.home_today_plan_priority_high)
            RoadmapStep.Priority.MEDIUM -> getString(R.string.home_today_plan_priority_medium)
            RoadmapStep.Priority.LOW -> getString(R.string.home_today_plan_priority_low)
        }
        return getString(R.string.home_today_plan_step_meta, timePart, priorityPart)
    }

    private fun formatSimpleTodayPlanMeta(entry: TodayPlanEntry): String {
        val dueFormatted = entry.task.dueDate?.let { DueDateTimeFormat.displayListRow(it) }
            ?: getString(R.string.due_date_not_set)
        return getString(R.string.task_row_due_line, dueFormatted)
    }

    private fun bindPlanStepRows(parent: LinearLayout, visibleEntries: List<TodayPlanEntry>) {
        parent.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density
        val headerTopFirst = (4 * density).toInt()
        val headerTopRest = (12 * density).toInt()
        val today = LocalDate.now()

        for (entry in visibleEntries.filter { it.isSimple }) {
            bindSimplePlanRow(parent, inflater, entry, today)
        }

        var isFirstHeader = true
        var lastComplexTaskId: String? = null
        for (entry in visibleEntries.filter { !it.isSimple }) {
            if (entry.task.id != lastComplexTaskId) {
                val header = inflater.inflate(R.layout.item_home_today_plan_task_header, parent, false)
                val top = if (isFirstHeader) headerTopFirst else headerTopRest
                isFirstHeader = false
                header.setPadding(header.paddingLeft, top, header.paddingRight, header.paddingBottom)
                header.findViewById<TextView>(R.id.homeTodayPlanTaskHeader).text = entry.task.title
                header.setOnClickListener { openTaskDetail(entry.task) }
                parent.addView(header)
                lastComplexTaskId = entry.task.id
            }
            bindComplexPlanRow(parent, inflater, entry, today)
        }
    }

    private fun bindSimplePlanRow(
        parent: LinearLayout,
        inflater: LayoutInflater,
        entry: TodayPlanEntry,
        today: LocalDate,
    ) {
        val row = inflater.inflate(R.layout.item_home_today_plan_step, parent, false)
        val check = row.findViewById<MaterialCheckBox>(R.id.homeTodayPlanStepCheck)
        val title = row.findViewById<TextView>(R.id.homeTodayPlanStepTitle)
        val meta = row.findViewById<TextView>(R.id.homeTodayPlanStepMeta)
        val isOverdue = entry.recommendedOn.isBefore(today) && !entry.isCompleted
        title.text = entry.task.title
        meta.text = if (isOverdue) {
            getString(R.string.home_today_plan_due_overdue) + " · " + formatSimpleTodayPlanMeta(entry)
        } else {
            formatSimpleTodayPlanMeta(entry)
        }
        meta.setTextColor(
            if (isOverdue) {
                MaterialColors.getColor(meta, com.google.android.material.R.attr.colorError)
            } else {
                MaterialColors.getColor(meta, com.google.android.material.R.attr.colorOnSurfaceVariant)
            },
        )
        check.setOnCheckedChangeListener(null)
        check.isChecked = entry.isCompleted
        check.setOnCheckedChangeListener { _, checked ->
            onSimpleTodayPlanToggled(entry.task.id, checked)
        }
        applyTodayPlanStepCompletedStyle(title, meta, check, entry.isCompleted)
        check.isEnabled = !homeRoadmapPatchInFlight
        val stepRow = row.findViewById<View>(R.id.homeTodayPlanStepRow)
        stepRow.isClickable = !homeRoadmapPatchInFlight
        stepRow.background = null
        stepRow.setOnClickListener {
            if (!homeRoadmapPatchInFlight) check.performClick()
        }
        parent.addView(row)
    }

    private fun bindComplexPlanRow(
        parent: LinearLayout,
        inflater: LayoutInflater,
        entry: TodayPlanEntry,
        today: LocalDate,
    ) {
        val row = inflater.inflate(R.layout.item_home_today_plan_step, parent, false)
        val check = row.findViewById<MaterialCheckBox>(R.id.homeTodayPlanStepCheck)
        val title = row.findViewById<TextView>(R.id.homeTodayPlanStepTitle)
        val meta = row.findViewById<TextView>(R.id.homeTodayPlanStepMeta)
        val step = entry.step!!
        val stepDay = RoadmapStep.recommendedLocalDate(step)
        val isOverdue = stepDay != null && stepDay.isBefore(today) && !entry.isCompleted
        title.text = step.title
        val baseMeta = formatTodayPlanStepMeta(step)
        meta.text = if (isOverdue) {
            getString(R.string.home_today_plan_due_overdue) + " · " + baseMeta
        } else {
            baseMeta
        }
        meta.setTextColor(
            if (isOverdue) {
                MaterialColors.getColor(meta, com.google.android.material.R.attr.colorError)
            } else {
                MaterialColors.getColor(meta, com.google.android.material.R.attr.colorOnSurfaceVariant)
            },
        )
        check.setOnCheckedChangeListener(null)
        check.isChecked = entry.isCompleted
        check.setOnCheckedChangeListener { _, checked ->
            onTodayPlanStepToggled(entry.task.id, entry.stepIndex, checked)
        }
        applyTodayPlanStepCompletedStyle(title, meta, check, entry.isCompleted)
        check.isEnabled = !homeRoadmapPatchInFlight
        val stepRow = row.findViewById<View>(R.id.homeTodayPlanStepRow)
        stepRow.isClickable = !homeRoadmapPatchInFlight
        stepRow.background = null
        stepRow.setOnClickListener {
            if (!homeRoadmapPatchInFlight) check.performClick()
        }
        parent.addView(row)
    }

    private fun bindHomeTodayPlan(allActiveTasks: List<SupabaseTasksApi.TaskRow>) {
        homeTasksSnapshot = allActiveTasks
        homeTodayPlanSection.visibility = View.VISIBLE
        val today = LocalDate.now()
        val workEntries = todayPlanWorkEntries(allActiveTasks, today)
        val progressEntries = todayPlanProgressEntries(allActiveTasks, today)
        val hasIncomplete = workEntries.any { !it.isCompleted }
        val reviewTodayEntries = todayPlanCompletedReviewEntries(allActiveTasks, today)
        val futureEntries = collectFuturePlanEntries(allActiveTasks, today)
        val hasStartedFutureWork = futureEntries.any { it.isCompleted }

        if (progressEntries.isEmpty() || !hasIncomplete) {
            homeTodayPlanProgressBlock.visibility = View.GONE
        } else {
            homeTodayPlanProgressBlock.visibility = View.VISIBLE
            bindPlanProgress(
                entries = progressEntries,
                stepsSummary = homeTodayPlanStepsSummary,
                hoursSummary = homeTodayPlanHoursSummary,
                hoursProgress = homeTodayPlanHoursProgress,
                stepsSummaryRes = R.string.home_today_plan_steps_summary,
            )
        }

        if (hasIncomplete) {
            homeTodayPlanEmpty.visibility = View.GONE
            homeTodayPlanGroups.visibility = View.VISIBLE
            val sorted = sortTodayPlanEntries(workEntries, today)
            val collapsedVisible = buildCollapsedVisibleEntries(workEntries, today)
            val visibleEntries = if (homeTodayPlanExpanded) sorted else collapsedVisible
            val showToggle = homeTodayPlanExpanded || collapsedVisible.size < sorted.size
            homeTodayPlanToggle.visibility = if (showToggle) View.VISIBLE else View.GONE
            homeTodayPlanToggle.text = getString(
                if (homeTodayPlanExpanded) R.string.home_today_plan_show_less else R.string.home_today_plan_see_full
            )
            bindPlanStepRows(homeTodayPlanGroups, visibleEntries)
        } else {
            homeTodayPlanExpanded = false
            homeTodayPlanToggle.visibility = View.GONE
            homeTodayPlanGroups.visibility = View.GONE
            homeTodayPlanGroups.removeAllViews()
        }

        val getAheadVisible = when {
            !hasIncomplete -> bindGetAheadSection(allActiveTasks, today)
            hasStartedFutureWork -> bindGetAheadSection(
                allActiveTasks = allActiveTasks,
                today = today,
                requireStartedOnFocusDay = true,
            )
            else -> {
                homeGetAheadSection.visibility = View.GONE
                homeGetAheadProgressBlock.visibility = View.GONE
                homeGetAheadGroups.removeAllViews()
                homeGetAheadFocusDate = null
                false
            }
        }

        val reviewDays = buildCompletedReviewDays(
            reviewTodayEntries,
            futureEntries,
            today,
            getAheadVisible,
            homeGetAheadFocusDate,
        )
        pruneCompletedReviewExpandedDays(reviewDays.map { it.first }.toSet())
        bindCompletedReviewByDay(
            reviewDays = reviewDays,
            today = today,
            getAheadVisible = getAheadVisible,
            focusDay = homeGetAheadFocusDate,
            hasRemainingPlanWork = hasIncomplete,
        )

        val hasAnythingVisible = hasIncomplete || reviewDays.isNotEmpty() || getAheadVisible
        if (!hasAnythingVisible) {
            homeTodayPlanEmpty.text = formatTodayPlanCollapsedEmptyText()
            homeTodayPlanEmpty.visibility = View.VISIBLE
        } else if (hasIncomplete) {
            homeTodayPlanEmpty.visibility = View.GONE
        } else {
            homeTodayPlanEmpty.visibility = View.GONE
        }
        if (!hasAnythingVisible) {
            homeTodayPlanCompletedTodayHost.visibility = View.GONE
            homeTodayPlanCompletedTodayHost.removeAllViews()
            homeTodayPlanFutureReviewBeforeFocusHost.visibility = View.GONE
            homeTodayPlanFutureReviewBeforeFocusHost.removeAllViews()
            homeTodayPlanCompletedByDayHost.visibility = View.GONE
            homeTodayPlanCompletedByDayHost.removeAllViews()
        }
    }

    private fun onSimpleTodayPlanToggled(taskId: String, checked: Boolean) {
        if (homeRoadmapPatchInFlight) return
        val token = SessionManager.getAccessToken(this) ?: return
        val task = homeTasksSnapshot.find { it.id == taskId } ?: return
        if (!TaskKind.isSimpleTask(task)) return
        if (checked && task.status == TaskStatus.COMPLETE) return
        if (!checked && task.status != TaskStatus.COMPLETE) return

        val pinKey = "$taskId:simple"
        val today = LocalDate.now()
        val due = TodayPlanWork.simpleTaskDueLocalDate(task)
        val isFutureDue = due != null && due.isAfter(today)
        val targetStatus = if (checked) TaskStatus.COMPLETE else TaskStatus.NOT_STARTED
        val userIdForAchievements = achievementsUserId ?: SupabaseUserId.resolveUserId(token)
        val userIdForProfileWrites = userIdForAchievements
        val beforeSnapshot = homeTasksSnapshot
        val beforeTodayHalf = todayHalfwayRatio(beforeSnapshot, today)
        val workPlanCompleteBefore = isTodayWorkPlanComplete(beforeSnapshot, today)
        val hadOverdueBefore = TodayPlanWork.hasIncompleteOverdueSteps(beforeSnapshot, today)

        if (checked) {
            homeSimpleCompletedTodayIds.add(taskId)
            if (isFutureDue) {
                homeGetAheadPinnedCheckedKeys.add(pinKey)
            } else {
                homeTodayPlanPinnedCheckedKeys.add(pinKey)
            }
        } else {
            homeTodayPlanPinnedCheckedKeys.remove(pinKey)
            homeGetAheadPinnedCheckedKeys.remove(pinKey)
            homeSimpleCompletedTodayIds.remove(taskId)
        }

        homeRoadmapPatchInFlight = true
        homeTasksSnapshot = homeTasksSnapshot.map { row ->
            if (row.id == taskId) row.copy(status = targetStatus) else row
        }
        bindHomeTodayPlan(homeTasksSnapshot)
        if (checked) {
            updateHomeUpcomingPreview(homeTasksSnapshot, newlyCompletedTaskId = taskId)
        } else {
            refreshHomeUpcomingPreview(homeTasksSnapshot)
        }

        var planCompleteFired = false
        if (checked) {
            if (userIdForAchievements != null) {
                AchievementManager.ensureLoaded(token, userIdForAchievements)
                AchievementManager.maybeShowFirstTaskCompleted(this, token, userIdForAchievements)
            }
            val overdueClearedNow = hadOverdueBefore &&
                !TodayPlanWork.hasIncompleteOverdueSteps(homeTasksSnapshot, today)
            if (overdueClearedNow) {
                planCompleteFired = true
                AchievementManager.showAllCaughtUp(this)
            }
        }

        val workPlanCompleteAfter = isTodayWorkPlanComplete(homeTasksSnapshot, today)
        if (checked && !planCompleteFired && !workPlanCompleteBefore && workPlanCompleteAfter) {
            planCompleteFired = true
            if (userIdForProfileWrites != null) {
                val uid = userIdForProfileWrites
                networkExecutor.execute {
                    val streak = StreakCoordinator.resolveStreakForPlanComplete(token, uid, today)
                    runOnUiThread {
                        if (!isFinishing) {
                            AchievementManager.showPlanComplete(this@HomeActivity, today, streak)
                        }
                    }
                }
            } else {
                AchievementManager.showPlanComplete(this, today, 1)
            }
        } else if (!checked && workPlanCompleteBefore && !workPlanCompleteAfter) {
            if (userIdForProfileWrites != null) {
                val uid = userIdForProfileWrites
                networkExecutor.execute {
                    StreakCoordinator.undoTodayCompletionIfPossible(token, uid)
                }
            }
        }

        if (checked && userIdForAchievements != null && !planCompleteFired) {
            val afterTodayHalf = todayHalfwayRatio(homeTasksSnapshot, today)
            if (beforeTodayHalf < 0.5 && afterTodayHalf >= 0.5) {
                AchievementManager.ensureLoaded(token, userIdForAchievements)
                AchievementManager.maybeShowHalfwayThroughCurrentTasks(this, token, userIdForAchievements)
            }
        }

        networkExecutor.execute {
            when (val result = SupabaseTasksApi.updateTaskStatus(token, taskId, targetStatus)) {
                is SupabaseTasksApi.PatchResult.Success -> runOnUiThread {
                    homeRoadmapPatchInFlight = false
                    if (isFinishing) return@runOnUiThread
                    homeTasksSnapshot = homeTasksSnapshot.map { row ->
                        if (row.id == taskId) row.copy(status = result.status) else row
                    }
                    bindHomeTodayPlan(homeTasksSnapshot)
                    if (checked) {
                        updateHomeUpcomingPreview(homeTasksSnapshot, newlyCompletedTaskId = taskId)
                    } else {
                        refreshHomeUpcomingPreview(homeTasksSnapshot)
                    }
                    if (currentTab == Tab.Tasks) {
                        loadTasks(showLoading = false)
                    }
                }
                is SupabaseTasksApi.PatchResult.Failure -> runOnUiThread {
                    homeRoadmapPatchInFlight = false
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(
                        this,
                        getString(R.string.error_task_status_update_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                    loadHomeUpcoming(showLoading = false)
                }
            }
        }
    }

    private fun onTodayPlanStepToggled(taskId: String, stepIndex: Int, checked: Boolean) {
        if (homeRoadmapPatchInFlight) return
        if (stepIndex < 0) return
        val token = SessionManager.getAccessToken(this) ?: return
        val userIdForAchievements = achievementsUserId ?: SupabaseUserId.resolveUserId(token)
        // Use the resolved user id for profile writes even if achievements are disabled/unavailable.
        val userIdForProfileWrites = userIdForAchievements
        val task = homeTasksSnapshot.find { it.id == taskId } ?: return
        val beforeSnapshot = homeTasksSnapshot
        val steps = RoadmapStep.parseList(task.roadmap).toMutableList()
        if (stepIndex !in steps.indices) return
        if (steps[stepIndex].completed == checked) return
        val pinKey = "$taskId:$stepIndex"
        val today = LocalDate.now()
        val rec = RoadmapStep.recommendedLocalDate(steps[stepIndex])
        val wasGetAheadStepCompleted = steps[stepIndex].completed
        val wasInTodayPlanScope = rec != null && !rec.isAfter(today)
        val beforeTodayHalf = todayHalfwayRatio(beforeSnapshot, today)
        val workPlanCompleteBefore = isTodayWorkPlanComplete(beforeSnapshot, today)
        val hadOverdueBefore = TodayPlanWork.hasIncompleteOverdueSteps(beforeSnapshot, today)
        when {
            rec == null -> {
                homeTodayPlanPinnedCheckedKeys.remove(pinKey)
                homeGetAheadPinnedCheckedKeys.remove(pinKey)
            }
            !rec.isAfter(today) -> {
                if (checked) {
                    val planEntries = todayPlanWorkEntries(homeTasksSnapshot, today)
                    val hasIncompleteAfter = planEntries.any { e ->
                        val toggled = e.task.id == taskId && e.stepIndex == stepIndex
                        val done = if (toggled) checked else e.isCompleted
                        !done
                    }
                    val todayPlanEffectivelyCollapsed = if (hasIncompleteAfter) {
                        !homeTodayPlanExpanded
                    } else {
                        today.toString() !in homeCompletedReviewDaysExpanded
                    }
                    if (todayPlanEffectivelyCollapsed) homeTodayPlanPinnedCheckedKeys.add(pinKey)
                } else {
                    homeTodayPlanPinnedCheckedKeys.remove(pinKey)
                }
                homeGetAheadPinnedCheckedKeys.remove(pinKey)
            }
            else -> {
                if (checked) {
                    homeGetAheadPinnedCheckedKeys.add(pinKey)
                } else {
                    // If a fully completed future day becomes active again, keep sibling completed
                    // rows visible in collapsed Get ahead so they can still be reviewed/toggled.
                    val day = rec
                    if (day != null) {
                        val futureEntries = collectFuturePlanEntries(homeTasksSnapshot, today)
                        futureEntries.forEach { e ->
                            if (
                                e.recommendedOn == day &&
                                e.isCompleted &&
                                !(e.task.id == taskId && e.stepIndex == stepIndex)
                            ) {
                                homeGetAheadPinnedCheckedKeys.add(todayPlanEntryKey(e))
                            }
                        }
                    }
                    homeGetAheadPinnedCheckedKeys.remove(pinKey)
                }
                homeTodayPlanPinnedCheckedKeys.remove(pinKey)
            }
        }
        homeRoadmapPatchInFlight = true
        steps[stepIndex] = steps[stepIndex].copy(
            completed = checked,
            completedAt = if (checked) Instant.now().toString() else null,
        )
        val previousStatus = task.status
        val derived = deriveStatusFromSteps(steps)
        val newRoadmap = RoadmapStep.toJsonArray(steps)
        homeTasksSnapshot = homeTasksSnapshot.map { row ->
            if (row.id == taskId) row.copy(roadmap = newRoadmap, status = derived) else row
        }
        bindHomeTodayPlan(homeTasksSnapshot)
        val taskJustCompleted = derived == TaskStatus.COMPLETE && previousStatus != TaskStatus.COMPLETE
        if (taskJustCompleted) {
            updateHomeUpcomingPreview(homeTasksSnapshot, newlyCompletedTaskId = taskId)
        } else {
            refreshHomeUpcomingPreview(homeTasksSnapshot)
        }

        // Achievement: "First task completed" — repurposed to step-based:
        // trigger the first time the user checks off ANY step on today's plan.
        if (userIdForAchievements != null && wasInTodayPlanScope && checked) {
            AchievementManager.ensureLoaded(token, userIdForAchievements)
            AchievementManager.maybeShowFirstTaskCompleted(this, token, userIdForAchievements)
        }

        // Achievement: "Getting ahead" (first future step completion via Get Ahead / future-day work).
        if (userIdForAchievements != null && rec != null && rec.isAfter(today) && checked && !wasGetAheadStepCompleted) {
            AchievementManager.ensureLoaded(token, userIdForAchievements)
            AchievementManager.maybeShowGettingAhead(this, token, userIdForAchievements)
        }

        // Achievement: clearing overdue backlog — celebrate catch-up, not a daily streak.
        var planCompleteFired = false
        val overdueClearedNow = checked && hadOverdueBefore &&
            !TodayPlanWork.hasIncompleteOverdueSteps(homeTasksSnapshot, today)
        if (overdueClearedNow) {
            planCompleteFired = true
            AchievementManager.showAllCaughtUp(this)
        }

        // Achievement: "Plan complete" + streak when today's scheduled work is finished (not overdue catch-up).
        val workPlanCompleteAfter = isTodayWorkPlanComplete(homeTasksSnapshot, today)
        if (!planCompleteFired && !workPlanCompleteBefore && workPlanCompleteAfter) {
            planCompleteFired = true
            if (userIdForProfileWrites != null) {
                val uid = userIdForProfileWrites
                networkExecutor.execute {
                    val streak = StreakCoordinator.resolveStreakForPlanComplete(token, uid, today)
                    runOnUiThread {
                        if (!isFinishing) {
                            AchievementManager.showPlanComplete(this@HomeActivity, today, streak)
                        }
                    }
                }
            } else {
                AchievementManager.showPlanComplete(this, today, 1)
            }
        } else if (workPlanCompleteBefore && !workPlanCompleteAfter) {
            if (userIdForProfileWrites != null) {
                val uid = userIdForProfileWrites
                networkExecutor.execute {
                    StreakCoordinator.undoTodayCompletionIfPossible(token, uid)
                }
            }
        }

        // Achievement: "Halfway There!" — based on TODAY's plan work (hours-first).
        // Fire when we cross >= 50% for today's plan, but not if we also completed the day.
        if (userIdForAchievements != null && checked && !planCompleteFired) {
            val afterTodayHalf = todayHalfwayRatio(homeTasksSnapshot, today)
            if (beforeTodayHalf < 0.5 && afterTodayHalf >= 0.5) {
                AchievementManager.ensureLoaded(token, userIdForAchievements)
                AchievementManager.maybeShowHalfwayThroughCurrentTasks(this, token, userIdForAchievements)
            }
        }

        persistTodayPlanRoadmap(token, taskId, steps, derived, previousStatus)
    }

    /**
     * Completion ratio for today's planned work, preferring hours when present.
     * Falls back to steps when all estimated hours are missing/zero.
     */
    private fun todayHalfwayRatio(
        tasksSnapshot: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): Double {
        val progressEntries = todayPlanProgressEntries(tasksSnapshot, today)
        if (progressEntries.isEmpty()) return 0.0
        val totalSteps = progressEntries.size
        val doneSteps = progressEntries.count { it.isCompleted }
        var totalHours = 0.0
        var doneHours = 0.0
        for (e in progressEntries) {
            val h = e.estimatedHoursForProgress()
            totalHours += h
            if (e.isCompleted) doneHours += h
        }
        return if (totalHours > 0.0) {
            (doneHours / totalHours).coerceIn(0.0, 1.0)
        } else if (totalSteps > 0) {
            (doneSteps.toDouble() / totalSteps.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    }

    private fun persistTodayPlanRoadmap(
        token: String,
        taskId: String,
        steps: List<RoadmapStep>,
        derived: TaskStatus,
        previousStatus: TaskStatus,
    ) {
        val payload = RoadmapStep.toJsonArray(steps)
        networkExecutor.execute {
            when (val road = SupabaseTasksApi.updateTaskRoadmap(token, taskId, payload)) {
                is SupabaseTasksApi.PatchRoadmapResult.Failure -> runOnUiThread {
                    homeRoadmapPatchInFlight = false
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(
                        this,
                        getString(R.string.home_today_plan_save_failed, road.message),
                        Toast.LENGTH_LONG,
                    ).show()
                    loadHomeUpcoming(showLoading = false)
                }
                is SupabaseTasksApi.PatchRoadmapResult.Success -> {
                    if (derived != previousStatus) {
                        when (val st = SupabaseTasksApi.updateTaskStatus(token, taskId, derived)) {
                            is SupabaseTasksApi.PatchResult.Failure -> runOnUiThread {
                                homeRoadmapPatchInFlight = false
                                if (isFinishing) return@runOnUiThread
                                Toast.makeText(
                                    this,
                                    getString(R.string.error_task_status_update_failed) + "\n" + st.message,
                                    Toast.LENGTH_LONG,
                                ).show()
                                loadHomeUpcoming(showLoading = false)
                            }
                            is SupabaseTasksApi.PatchResult.Success -> runOnUiThread {
                                homeRoadmapPatchInFlight = false
                                if (isFinishing) return@runOnUiThread
                                homeTasksSnapshot = homeTasksSnapshot.map { row ->
                                    if (row.id == taskId) row.copy(status = st.status) else row
                                }
                                bindHomeTodayPlan(homeTasksSnapshot)
                                refreshHomeUpcomingPreview(homeTasksSnapshot)
                                if (currentTab == Tab.Tasks) {
                                    loadTasks(showLoading = false)
                                }

                                val userId = achievementsUserId ?: SupabaseUserId.resolveUserId(token)
                                if (userId != null) {
                                    AchievementManager.ensureLoaded(token, userId)
                                }
                            }
                        }
                    } else {
                        runOnUiThread {
                            homeRoadmapPatchInFlight = false
                            if (isFinishing) return@runOnUiThread
                            bindHomeTodayPlan(homeTasksSnapshot)
                            refreshHomeUpcomingPreview(homeTasksSnapshot)
                            if (currentTab == Tab.Tasks) {
                                loadTasks(showLoading = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isUpcomingEligible(task: SupabaseTasksApi.TaskRow): Boolean {
        if (task.status == TaskStatus.COMPLETE) return false
        val steps = RoadmapStep.parseList(task.roadmap)
        if (steps.isNotEmpty() && steps.all { it.completed }) return false
        return true
    }

    private fun isFutureUpcomingTask(task: SupabaseTasksApi.TaskRow, today: LocalDate): Boolean {
        val dueDay = task.dueDate?.toLocalDate() ?: return false
        return dueDay.isAfter(today)
    }

    private fun upcomingEligibleSorted(
        tasks: List<SupabaseTasksApi.TaskRow>,
    ): List<SupabaseTasksApi.TaskRow> =
        tasks.filter { isUpcomingEligible(it) }.sortedWith(compareBy(nullsLast()) { it.dueDate })

    private fun resetHomeUpcomingPreview(activeTasks: List<SupabaseTasksApi.TaskRow>) {
        homeUpcomingDisplayedIds = upcomingEligibleSorted(activeTasks).take(3).map { it.id }
        bindHomeUpcomingCards(activeTasks)
    }

    private fun refreshHomeUpcomingPreview(allTasks: List<SupabaseTasksApi.TaskRow>) {
        updateHomeUpcomingPreview(allTasks, newlyCompletedTaskId = null)
    }

    /**
     * Keeps Coming up slots stable while steps progress. When [newlyCompletedTaskId] is set, removes
     * that task and optionally fills the slot with the next due-date future task not already shown.
     */
    private fun updateHomeUpcomingPreview(
        allTasks: List<SupabaseTasksApi.TaskRow>,
        newlyCompletedTaskId: String?,
    ) {
        val today = LocalDate.now()
        if (homeUpcomingDisplayedIds.isEmpty()) {
            resetHomeUpcomingPreview(allTasks)
            return
        }

        val before = homeUpcomingDisplayedIds
        var ids = before.filter { id ->
            if (id == newlyCompletedTaskId) return@filter false
            val task = allTasks.find { it.id == id } ?: return@filter false
            isUpcomingEligible(task)
        }

        if (newlyCompletedTaskId != null && before.contains(newlyCompletedTaskId)) {
            val displayedSet = ids.toSet()
            val replacement = upcomingEligibleSorted(allTasks)
                .firstOrNull { it.id !in displayedSet && isFutureUpcomingTask(it, today) }
            if (replacement != null) {
                ids = ids + replacement.id
            }
        }

        homeUpcomingDisplayedIds = ids
        bindHomeUpcomingCards(allTasks)
    }

    private fun bindHomeUpcomingCards(allTasks: List<SupabaseTasksApi.TaskRow>) {
        val tasksToShow = homeUpcomingDisplayedIds.mapNotNull { id -> allTasks.find { it.id == id } }
        bindHomePreviewCards(tasksToShow)
    }

    private fun bindHomePreviewCards(tasks: List<SupabaseTasksApi.TaskRow>) {
        homeUpcomingCards.removeAllViews()
        if (tasks.isEmpty()) {
            homeUpcomingEmpty.text = getString(R.string.home_empty_upcoming)
            homeUpcomingEmpty.visibility = View.VISIBLE
            homeUpcomingCards.visibility = View.GONE
            return
        }
        homeUpcomingEmpty.visibility = View.GONE
        homeUpcomingCards.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        val today = LocalDate.now()
        tasks.forEachIndexed { index, task ->
            val row = inflater.inflate(R.layout.item_home_upcoming_task, homeUpcomingCards, false)
            styleHomeUpcomingCard(row, task, urgent = index == 0, today)
            row.setOnClickListener { openTaskDetail(task) }
            homeUpcomingCards.addView(row)
        }
    }

    private fun styleHomeUpcomingCard(
        root: View,
        task: SupabaseTasksApi.TaskRow,
        urgent: Boolean,
        today: LocalDate,
    ) {
        val card = root.findViewById<MaterialCardView>(R.id.homeTaskCard)
        val title = root.findViewById<TextView>(R.id.homeCardTitle)
        val duePill = root.findViewById<TextView>(R.id.homeCardDuePill)
        val statusLine = root.findViewById<TextView>(R.id.homeCardStatus)
        val progress = root.findViewById<ProgressBar>(R.id.homeCardProgress)

        val overdue = DueDateHumanLabel.isOverdue(task.dueDate, task.status)

        title.text = task.title
        duePill.text = DueDateHumanLabel.compactDuePhrase(this, task.dueDate, today, task.status)
        val statusLabel = TaskStatusUi.label(task.status)
        statusLine.text = if (overdue && task.dueDate != null) {
            "$statusLabel · ${DueDateHumanLabel.wasDueDetail(this, task.dueDate)}"
        } else {
            statusLabel
        }

        val summary = RoadmapProgress.summarize(task.roadmap)
        if (summary.total <= 0) {
            progress.visibility = View.GONE
        } else {
            progress.visibility = View.VISIBLE
            progress.progress = summary.percent
        }
        when {
            overdue -> {
                // Keep the card calm; flag overdue only on the due pill.
                card.setCardBackgroundColor(
                    MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceVariant)
                )
                card.strokeWidth = 0
                duePill.setBackgroundResource(R.drawable.bg_home_due_pill_overdue)
                duePill.setTextColor(
                    MaterialColors.getColor(duePill, com.google.android.material.R.attr.colorError)
                )
            }
            urgent -> {
                card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.home_urgent_card_fill))
                card.strokeWidth = resources.getDimensionPixelSize(R.dimen.home_urgent_card_stroke_width)
                card.strokeColor = ContextCompat.getColor(this, R.color.home_urgent_card_stroke)
                duePill.setBackgroundResource(R.drawable.bg_home_due_pill_urgent)
                duePill.setTextColor(ContextCompat.getColor(this, R.color.home_urgent_pill_text))
            }
            else -> {
                card.setCardBackgroundColor(
                    MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceVariant)
                )
                card.strokeWidth = 0
                duePill.setBackgroundResource(R.drawable.bg_home_due_pill_neutral)
                duePill.setTextColor(
                    MaterialColors.getColor(duePill, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
        }
    }

    private fun openTaskDetail(task: SupabaseTasksApi.TaskRow) {
        openTaskDetailLauncher.launch(TaskDetailActivity.createIntent(this, task))
    }

    private fun onSimpleTaskToggled(task: SupabaseTasksApi.TaskRow, checked: Boolean) {
        if (checked == (task.status == TaskStatus.COMPLETE)) return
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
            return
        }
        val targetStatus = if (checked) TaskStatus.COMPLETE else TaskStatus.NOT_STARTED
        val pinKey = "${task.id}:simple"
        val today = LocalDate.now()
        val due = TodayPlanWork.simpleTaskDueLocalDate(task)
        val inTodayPlanScope = due != null && !due.isAfter(today)
        if (checked && inTodayPlanScope) {
            homeSimpleCompletedTodayIds.add(task.id)
            homeTodayPlanPinnedCheckedKeys.add(pinKey)
        } else if (!checked) {
            homeSimpleCompletedTodayIds.remove(task.id)
            homeTodayPlanPinnedCheckedKeys.remove(pinKey)
        }
        networkExecutor.execute {
            when (val result = SupabaseTasksApi.updateTaskStatus(token, task.id, targetStatus)) {
                is SupabaseTasksApi.PatchResult.Success -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    loadTasks(showLoading = false)
                    loadHomeUpcoming(showLoading = false)
                }
                is SupabaseTasksApi.PatchResult.Failure -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (checked && inTodayPlanScope) {
                        homeSimpleCompletedTodayIds.remove(task.id)
                        homeTodayPlanPinnedCheckedKeys.remove(pinKey)
                    } else if (!checked) {
                        homeSimpleCompletedTodayIds.add(task.id)
                        homeTodayPlanPinnedCheckedKeys.add(pinKey)
                    }
                    tasksAdapter.notifyDataSetChanged()
                    Toast.makeText(
                        this,
                        getString(R.string.error_task_status_update_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun maybeShowStreakOnAppLaunch(token: String) {
        synchronized(streakLaunchLock) {
            if (streakLaunchPopupShownThisProcess) return
        }
        networkExecutor.execute {
            val uid = achievementsUserId ?: SupabaseUserId.resolveUserId(token) ?: return@execute
            when (val r = SupabaseProfilesApi.get(token, uid)) {
                is SupabaseProfilesApi.GetResult.Success -> {
                    if (r.row.currentStreak < 2) return@execute
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        synchronized(streakLaunchLock) {
                            if (streakLaunchPopupShownThisProcess) return@runOnUiThread
                            streakLaunchPopupShownThisProcess = true
                        }
                        AchievementPopup.show(
                            activity = this,
                            emoji = "🔥",
                            title = "Hey!",
                            message = "You're on a 🔥 ${r.row.currentStreak} day streak! Keep it up!",
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private fun loadTasks(showLoading: Boolean = true) {
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            tasksAdapter.submitList(emptyList())
            tasksEmptyView.text = getString(R.string.error_task_not_signed_in)
            tasksEmptyView.visibility = View.VISIBLE
            tasksRecyclerView.visibility = View.GONE
            tasksLoading.visibility = View.GONE
            return
        }

        if (showLoading) {
            tasksLoading.visibility = View.VISIBLE
            tasksEmptyView.visibility = View.GONE
            tasksRecyclerView.visibility = View.INVISIBLE
        }

        networkExecutor.execute {
            when (val result = SupabaseTasksApi.listTasks(token, TaskListFilter.ACTIVE)) {
                is SupabaseTasksApi.ListResult.Success -> runOnUiThread {
                    if (showLoading) tasksLoading.visibility = View.GONE
                    tasksAdapter.submitList(result.tasks)
                    if (result.tasks.isEmpty()) {
                        tasksEmptyView.text = getString(R.string.tasks_empty)
                        tasksEmptyView.visibility = View.VISIBLE
                        tasksRecyclerView.visibility = View.GONE
                    } else {
                        tasksEmptyView.visibility = View.GONE
                        tasksRecyclerView.visibility =
                            if (panelTasks.visibility == View.VISIBLE) View.VISIBLE else View.GONE
                    }
                }
                is SupabaseTasksApi.ListResult.Failure -> runOnUiThread {
                    if (showLoading) tasksLoading.visibility = View.GONE
                    if (showLoading) {
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
    }

    companion object {
        private val streakLaunchLock = Any()
        private var streakLaunchPopupShownThisProcess = false

        const val EXTRA_SELECTED_TAB = "selected_tab"
        const val TAB_HOME = "home"
        const val TAB_TASKS = "tasks"
        const val TAB_PROFILE = "profile"
    }
}
