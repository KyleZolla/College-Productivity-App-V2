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
import java.time.LocalDate
import java.time.LocalTime
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
    private var homeRoadmapPatchInFlight = false
    private var homeTodayPlanExpanded = false
    private val homeCompletedReviewDaysExpanded = mutableSetOf<String>()
    private val homeTodayPlanPinnedCheckedKeys = mutableSetOf<String>()
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
        tasksAdapter = TaskListAdapter { task -> openTaskDetail(task) }
        tasksRecyclerView.adapter = tasksAdapter

        findViewById<TextView>(R.id.tasksViewCompletedButton).setOnClickListener {
            startActivity(Intent(this, CompletedTasksActivity::class.java))
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
                    val extra = when (completedRm) {
                        is SupabaseTasksApi.ListResult.Success -> completedRm.tasks
                        is SupabaseTasksApi.ListResult.Failure -> emptyList()
                    }
                    val mergedForPlan = active.tasks + extra
                    runOnUiThread {
                        if (showLoading) homeUpcomingLoading.visibility = View.GONE
                        bindHomePreviewCards(active.tasks.take(3))
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

    private data class HomeTodayPlanEntry(
        val task: SupabaseTasksApi.TaskRow,
        val step: RoadmapStep,
        val stepIndex: Int,
        val recommendedOn: LocalDate,
    )

    private fun collectTodayPlanEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<HomeTodayPlanEntry> {
        val out = ArrayList<HomeTodayPlanEntry>()
        for (task in tasks) {
            val steps = RoadmapStep.parseList(task.roadmap)
            steps.forEachIndexed { index, step ->
                val on = RoadmapStep.recommendedLocalDate(step) ?: return@forEachIndexed
                if (on.isAfter(today)) return@forEachIndexed
                out.add(
                    HomeTodayPlanEntry(
                        task = task,
                        step = step,
                        stepIndex = index,
                        recommendedOn = on,
                    ),
                )
            }
        }
        return out
    }

    private fun collectFuturePlanEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<HomeTodayPlanEntry> {
        val out = ArrayList<HomeTodayPlanEntry>()
        for (task in tasks) {
            val steps = RoadmapStep.parseList(task.roadmap)
            steps.forEachIndexed { index, step ->
                val on = RoadmapStep.recommendedLocalDate(step) ?: return@forEachIndexed
                if (!on.isAfter(today)) return@forEachIndexed
                out.add(
                    HomeTodayPlanEntry(
                        task = task,
                        step = step,
                        stepIndex = index,
                        recommendedOn = on,
                    ),
                )
            }
        }
        return out
    }

    private fun sortTodayPlanEntries(entries: List<HomeTodayPlanEntry>, today: LocalDate): List<HomeTodayPlanEntry> {
        val (past, dueToday) = entries.partition { it.recommendedOn.isBefore(today) }
        val sortedPast = past
            .groupBy { it.recommendedOn }
            .toSortedMap()
            .values
            .flatMap { dayEntries ->
                sortByTaskRankThenRoadmap(dayEntries, todayTaskRankComparator())
            }
        val sortedToday = sortByTaskRankThenRoadmap(dueToday, todayTaskRankComparator())
        return sortedPast + sortedToday
    }

    /**
     * Ordered future-day review buckets (excluding today's bucket): include days where at least one
     * step is completed so "started early" work stays visible; exclude the focused Get ahead day
     * while that section is visible to avoid duplicate presentation.
     */
    private fun buildCompletedReviewDays(
        todayEntries: List<HomeTodayPlanEntry>,
        futureEntries: List<HomeTodayPlanEntry>,
        today: LocalDate,
        getAheadVisible: Boolean,
        focusDay: LocalDate?,
    ): List<Pair<LocalDate, List<HomeTodayPlanEntry>>> {
        val out = ArrayList<Pair<LocalDate, List<HomeTodayPlanEntry>>>()
        if (todayEntries.isNotEmpty()) {
            out.add(today to sortTodayPlanEntries(todayEntries, today))
        }
        futureEntries.groupBy { it.recommendedOn }.toSortedMap().forEach { (day, list) ->
            // Keep a future day visible once it has any completed work, even if not fully done yet.
            if (list.isEmpty() || list.none { it.step.completed }) return@forEach
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
        reviewDays: List<Pair<LocalDate, List<HomeTodayPlanEntry>>>,
        today: LocalDate,
        getAheadVisible: Boolean,
        focusDay: LocalDate?,
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
        for ((day, list) in reviewDays) {
            val targetHost = when {
                day == today -> homeTodayPlanCompletedTodayHost
                getAheadVisible && focusDay != null && day.isBefore(focusDay) ->
                    homeTodayPlanFutureReviewBeforeFocusHost
                else -> homeTodayPlanCompletedByDayHost
            }
            val block = inflater.inflate(R.layout.item_home_completed_day_review, targetHost, false)
            val title = block.findViewById<TextView>(R.id.homeCompletedDayTitle)
            val subtitle = block.findViewById<TextView>(R.id.homeCompletedDaySubtitle)
            val progressBlock = block.findViewById<LinearLayout>(R.id.homeCompletedDayProgressBlock)
            val stepsSummary = block.findViewById<TextView>(R.id.homeCompletedDayStepsSummary)
            val hoursSummary = block.findViewById<TextView>(R.id.homeCompletedDayHoursSummary)
            val hoursProgress = block.findViewById<ProgressBar>(R.id.homeCompletedDayHoursProgress)
            val toggle = block.findViewById<MaterialButton>(R.id.homeCompletedDayToggle)
            val stepsHost = block.findViewById<LinearLayout>(R.id.homeCompletedDaySteps)
            title.text = day.format(dateFmt)
            val allDoneOnDay = list.all { it.step.completed }
            val partiallyDoneFutureDay = day.isAfter(today) && !allDoneOnDay
            if (day.isAfter(today) && allDoneOnDay) {
                subtitle.visibility = View.VISIBLE
                subtitle.text = getString(R.string.home_today_plan_progression_day_done, day.format(dateFmt))
            } else {
                subtitle.visibility = View.GONE
            }
            if (day.isAfter(today)) {
                progressBlock.visibility = View.VISIBLE
                bindPlanProgress(
                    entries = list,
                    stepsSummary = stepsSummary,
                    hoursSummary = hoursSummary,
                    hoursProgress = hoursProgress,
                    stepsSummaryRes = R.string.home_plan_steps_summary,
                )
            } else {
                progressBlock.visibility = View.GONE
            }
            val dayKey = day.toString()
            val expanded = dayKey in homeCompletedReviewDaysExpanded
            if (partiallyDoneFutureDay) {
                toggle.visibility = View.GONE
                stepsHost.visibility = View.VISIBLE
                bindPlanStepRows(stepsHost, list)
            } else {
                toggle.visibility = View.VISIBLE
                toggle.text = getString(
                    when {
                        expanded -> R.string.home_today_plan_show_less
                        day.isAfter(today) && allDoneOnDay -> R.string.home_today_plan_view_completed_ahead
                        else -> R.string.home_today_plan_view_completed
                    },
                )
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

    private fun todayPlanEntryKey(entry: HomeTodayPlanEntry): String =
        "${entry.task.id}:${entry.stepIndex}"

    private fun prunePinnedTodayPlanKeys(entries: List<HomeTodayPlanEntry>) {
        val byKey = entries.associateBy { todayPlanEntryKey(it) }
        homeTodayPlanPinnedCheckedKeys.removeAll { key ->
            val e = byKey[key]
            e == null || !e.step.completed
        }
    }

    private fun buildCollapsedVisibleEntries(
        entries: List<HomeTodayPlanEntry>,
        today: LocalDate,
    ): List<HomeTodayPlanEntry> {
        prunePinnedTodayPlanKeys(entries)
        val uncheckedSorted = sortTodayPlanEntries(entries.filter { !it.step.completed }, today)
        val topUnchecked = uncheckedSorted.take(2)
        val pinned = homeTodayPlanPinnedCheckedKeys.mapNotNull { key ->
            entries.find { todayPlanEntryKey(it) == key && it.step.completed }
        }
        val combined = (topUnchecked + pinned).distinctBy { todayPlanEntryKey(it) }
        val rank = sortTodayPlanEntries(entries, today).withIndex().associate { (i, e) ->
            todayPlanEntryKey(e) to i
        }
        return combined.sortedWith(
            compareBy<HomeTodayPlanEntry> { it.step.completed }
                .thenBy { rank[todayPlanEntryKey(it)] ?: Int.MAX_VALUE },
        )
    }

    private fun pruneGetAheadPinnedKeys(futureEntries: List<HomeTodayPlanEntry>) {
        val byKey = futureEntries.associateBy { todayPlanEntryKey(it) }
        homeGetAheadPinnedCheckedKeys.removeAll { key ->
            val e = byKey[key]
            e == null || !e.step.completed
        }
    }

    private fun todayTaskRankComparator(): Comparator<HomeTodayPlanEntry> =
        compareBy<HomeTodayPlanEntry> { it.step.priority }
            .thenByDescending { it.step.estimatedHours ?: -1.0 }
            .thenBy(nullsLast()) { it.task.dueDate }

    private fun taskLeadEntry(taskEntries: List<HomeTodayPlanEntry>): HomeTodayPlanEntry =
        taskEntries.filter { !it.step.completed }.minByOrNull { it.stepIndex }
            ?: taskEntries.minBy { it.stepIndex }

    /**
     * Rank tasks first, then keep each task's roadmap sequence (`stepIndex`) intact.
     */
    private fun sortByTaskRankThenRoadmap(
        entries: List<HomeTodayPlanEntry>,
        taskRankComparator: Comparator<HomeTodayPlanEntry>,
    ): List<HomeTodayPlanEntry> {
        if (entries.isEmpty()) return emptyList()
        val groups = entries.groupBy { it.task.id }.values
        val sortedGroups = groups.sortedWith(
            Comparator<List<HomeTodayPlanEntry>> { a, b ->
                val leadA = taskLeadEntry(a)
                val leadB = taskLeadEntry(b)
                val ranked = taskRankComparator.compare(leadA, leadB)
                if (ranked != 0) ranked else leadA.task.id.compareTo(leadB.task.id)
            },
        )
        return sortedGroups.flatMap { group -> group.sortedBy { it.stepIndex } }
    }

    private fun sortGetAheadDayEntries(dayEntries: List<HomeTodayPlanEntry>): List<HomeTodayPlanEntry> {
        if (dayEntries.isEmpty()) return emptyList()
        val byTask = dayEntries.groupBy { it.task.id }.values
        val taskComparator = Comparator<List<HomeTodayPlanEntry>> { a, b ->
            val leadA = taskLeadEntry(a)
            val leadB = taskLeadEntry(b)
            val overdueA = DueDateHumanLabel.isOverdue(leadA.task.dueDate, leadA.task.status)
            val overdueB = DueDateHumanLabel.isOverdue(leadB.task.dueDate, leadB.task.status)
            when {
                overdueA && !overdueB -> -1
                !overdueA && overdueB -> 1
                else -> {
                    val ranked = todayTaskRankComparator().compare(leadA, leadB)
                    if (ranked != 0) ranked else leadA.task.id.compareTo(leadB.task.id)
                }
            }
        }
        return byTask
            .sortedWith(taskComparator)
            .flatMap { group -> group.sortedBy { it.stepIndex } }
    }

    private fun resolveGetAheadFocusDate(
        futureEntries: List<HomeTodayPlanEntry>,
        requireStartedOnFocusDay: Boolean,
    ): LocalDate? {
        val incompleteDays = futureEntries.groupBy { it.recommendedOn }
            .toSortedMap()
            .entries
            .mapNotNull { (day, list) ->
                val hasIncomplete = list.any { !it.step.completed }
                if (!hasIncomplete) return@mapNotNull null
                if (requireStartedOnFocusDay && list.none { it.step.completed }) return@mapNotNull null
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

    private fun buildGetAheadCollapsedVisible(dayEntries: List<HomeTodayPlanEntry>): List<HomeTodayPlanEntry> {
        val sortedDay = sortGetAheadDayEntries(dayEntries)
        val uncheckedSorted = sortedDay.filter { !it.step.completed }
        val topUnchecked = uncheckedSorted.take(2)
        // Keep completed rows visible for the focused day so unchecking one future step does not
        // hide the other completed work for that same day.
        val completedRows = sortedDay.filter { it.step.completed }
        val pinned = homeGetAheadPinnedCheckedKeys.mapNotNull { key ->
            dayEntries.find { todayPlanEntryKey(it) == key && it.step.completed }
        }
        val combined = (topUnchecked + completedRows + pinned).distinctBy { todayPlanEntryKey(it) }
        val rank = sortedDay.withIndex().associate { (i, e) ->
            todayPlanEntryKey(e) to i
        }
        return combined.sortedWith(
            compareBy<HomeTodayPlanEntry> { it.step.completed }
                .thenBy { rank[todayPlanEntryKey(it)] ?: Int.MAX_VALUE },
        )
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
            val hasIncompleteOnDay = dayEntries.any { !it.step.completed }
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

    private fun bindTodayPlanProgress(todayEntries: List<HomeTodayPlanEntry>) {
        if (todayEntries.isEmpty()) {
            homeTodayPlanProgressBlock.visibility = View.GONE
            return
        }
        homeTodayPlanProgressBlock.visibility = View.VISIBLE
        bindPlanProgress(
            entries = todayEntries,
            stepsSummary = homeTodayPlanStepsSummary,
            hoursSummary = homeTodayPlanHoursSummary,
            hoursProgress = homeTodayPlanHoursProgress,
            stepsSummaryRes = R.string.home_today_plan_steps_summary,
        )
    }

    private fun bindPlanProgress(
        entries: List<HomeTodayPlanEntry>,
        stepsSummary: TextView,
        hoursSummary: TextView,
        hoursProgress: ProgressBar,
        stepsSummaryRes: Int,
    ) {
        val totalSteps = entries.size
        val doneSteps = entries.count { it.step.completed }
        stepsSummary.text = getString(stepsSummaryRes, doneSteps, totalSteps)
        var doneHours = 0.0
        var totalHours = 0.0
        for (e in entries) {
            val h = e.step.estimatedHours ?: 0.0
            totalHours += h
            if (e.step.completed) doneHours += h
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

    private fun bindPlanStepRows(parent: LinearLayout, visibleEntries: List<HomeTodayPlanEntry>) {
        parent.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density
        val headerTopFirst = (4 * density).toInt()
        val headerTopRest = (12 * density).toInt()
        val taskOrder = visibleEntries.map { it.task.id }.distinct()
        val taskRank = taskOrder.withIndex().associate { (idx, id) -> id to idx }
        val orderedEntries = visibleEntries.sortedWith(
            compareBy<HomeTodayPlanEntry> { taskRank[it.task.id] ?: Int.MAX_VALUE }
                .thenBy { it.stepIndex },
        )
        var isFirstHeader = true
        var lastTaskId: String? = null
        for (entry in orderedEntries) {
            if (entry.task.id != lastTaskId) {
                val header = inflater.inflate(R.layout.item_home_today_plan_task_header, parent, false)
                val top = if (isFirstHeader) headerTopFirst else headerTopRest
                isFirstHeader = false
                header.setPadding(header.paddingLeft, top, header.paddingRight, header.paddingBottom)
                header.findViewById<TextView>(R.id.homeTodayPlanTaskHeader).text = entry.task.title
                header.setOnClickListener { openTaskDetail(entry.task) }
                parent.addView(header)
                lastTaskId = entry.task.id
            }
            val row = inflater.inflate(R.layout.item_home_today_plan_step, parent, false)
            val check = row.findViewById<MaterialCheckBox>(R.id.homeTodayPlanStepCheck)
            val title = row.findViewById<TextView>(R.id.homeTodayPlanStepTitle)
            val meta = row.findViewById<TextView>(R.id.homeTodayPlanStepMeta)
            val stepDay = RoadmapStep.recommendedLocalDate(entry.step)
            val isOverdue = stepDay != null && stepDay.isBefore(LocalDate.now()) && !entry.step.completed
            title.text = entry.step.title
            val baseMeta = formatTodayPlanStepMeta(entry.step)
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
                }
            )
            check.setOnCheckedChangeListener(null)
            check.isChecked = entry.step.completed
            check.setOnCheckedChangeListener { _, checked ->
                onTodayPlanStepToggled(entry.task.id, entry.stepIndex, checked)
            }
            applyTodayPlanStepCompletedStyle(title, meta, check, entry.step.completed)
            check.isEnabled = !homeRoadmapPatchInFlight
            val stepRow = row.findViewById<View>(R.id.homeTodayPlanStepRow)
            stepRow.isClickable = !homeRoadmapPatchInFlight
            stepRow.background = null
            stepRow.setOnClickListener {
                if (!homeRoadmapPatchInFlight) check.performClick()
            }
            parent.addView(row)
        }
    }

    private fun bindHomeTodayPlan(allActiveTasks: List<SupabaseTasksApi.TaskRow>) {
        homeTasksSnapshot = allActiveTasks
        homeTodayPlanSection.visibility = View.VISIBLE
        val today = LocalDate.now()
        val entries = collectTodayPlanEntries(allActiveTasks, today)
        // Show overdue (unchecked) steps + today's steps.
        val todayEntries = entries.filter { it.recommendedOn == today }
        val overdueEntries = entries.filter { it.recommendedOn.isBefore(today) && !it.step.completed }
        // Overdue first, then today's items; keep stable order after sorting.
        val displayEntries = overdueEntries + todayEntries
        if (displayEntries.isEmpty()) {
            homeTodayPlanProgressBlock.visibility = View.GONE
        } else {
            homeTodayPlanProgressBlock.visibility = View.VISIBLE
            // Since we can include overdue steps, use the generic summary (not "done today").
            bindPlanProgress(
                entries = displayEntries,
                stepsSummary = homeTodayPlanStepsSummary,
                hoursSummary = homeTodayPlanHoursSummary,
                hoursProgress = homeTodayPlanHoursProgress,
                stepsSummaryRes = if (overdueEntries.isEmpty()) {
                    R.string.home_today_plan_steps_summary
                } else {
                    R.string.home_plan_steps_summary
                },
            )
        }
        val hasIncomplete = displayEntries.any { !it.step.completed }
        if (!hasIncomplete) {
            val getAheadVisible = bindGetAheadSection(allActiveTasks, today)
            val futureEntries = collectFuturePlanEntries(allActiveTasks, today)
            homeTodayPlanEmpty.text = formatTodayPlanCollapsedEmptyText()
            homeTodayPlanToggle.visibility = View.GONE
            homeTodayPlanGroups.visibility = View.GONE
            homeTodayPlanGroups.removeAllViews()
            val reviewDays = buildCompletedReviewDays(
                displayEntries,
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
            )
            if (reviewDays.isEmpty()) {
                homeTodayPlanEmpty.visibility = View.VISIBLE
                homeTodayPlanCompletedTodayHost.visibility = View.GONE
                homeTodayPlanCompletedTodayHost.removeAllViews()
                homeTodayPlanFutureReviewBeforeFocusHost.visibility = View.GONE
                homeTodayPlanFutureReviewBeforeFocusHost.removeAllViews()
                homeTodayPlanCompletedByDayHost.visibility = View.GONE
                homeTodayPlanCompletedByDayHost.removeAllViews()
            } else {
                homeTodayPlanEmpty.visibility =
                    if (homeCompletedReviewDaysExpanded.isEmpty()) View.VISIBLE else View.GONE
            }
        } else {
            homeTodayPlanEmpty.visibility = View.GONE
            homeTodayPlanGroups.visibility = View.VISIBLE
            val sorted = sortTodayPlanEntries(displayEntries, today)
            val collapsedVisible = buildCollapsedVisibleEntries(displayEntries, today)
            val visibleEntries = if (homeTodayPlanExpanded) sorted else collapsedVisible
            val showToggle = homeTodayPlanExpanded || collapsedVisible.size < sorted.size
            homeTodayPlanToggle.visibility = if (showToggle) View.VISIBLE else View.GONE
            homeTodayPlanToggle.text = getString(
                if (homeTodayPlanExpanded) R.string.home_today_plan_show_less else R.string.home_today_plan_see_full
            )
            bindPlanStepRows(homeTodayPlanGroups, visibleEntries)
            val futureEntries = collectFuturePlanEntries(allActiveTasks, today)
            val hasStartedFutureWork = futureEntries.any { it.step.completed }
            val getAheadVisible = if (hasStartedFutureWork) {
                bindGetAheadSection(
                    allActiveTasks = allActiveTasks,
                    today = today,
                    requireStartedOnFocusDay = true,
                )
            } else {
                false
            }
            if (hasStartedFutureWork) {
                val futureReviewDays = buildCompletedReviewDays(
                    emptyList(),
                    futureEntries,
                    today,
                    getAheadVisible,
                    homeGetAheadFocusDate,
                )
                pruneCompletedReviewExpandedDays(futureReviewDays.map { it.first }.toSet())
                bindCompletedReviewByDay(
                    reviewDays = futureReviewDays,
                    today = today,
                    getAheadVisible = getAheadVisible,
                    focusDay = homeGetAheadFocusDate,
                )
            } else {
                homeGetAheadSection.visibility = View.GONE
                homeGetAheadProgressBlock.visibility = View.GONE
                homeGetAheadGroups.removeAllViews()
                homeGetAheadFocusDate = null
                homeTodayPlanCompletedTodayHost.visibility = View.GONE
                homeTodayPlanCompletedTodayHost.removeAllViews()
                homeTodayPlanFutureReviewBeforeFocusHost.visibility = View.GONE
                homeTodayPlanFutureReviewBeforeFocusHost.removeAllViews()
                homeTodayPlanCompletedByDayHost.visibility = View.GONE
                homeTodayPlanCompletedByDayHost.removeAllViews()
                homeCompletedReviewDaysExpanded.clear()
            }
        }
    }

    private fun onTodayPlanStepToggled(taskId: String, stepIndex: Int, checked: Boolean) {
        if (homeRoadmapPatchInFlight) return
        val token = SessionManager.getAccessToken(this) ?: return
        val userIdForAchievements = achievementsUserId ?: SupabaseUserId.resolveUserId(token)
        val task = homeTasksSnapshot.find { it.id == taskId } ?: return
        val beforeSnapshot = homeTasksSnapshot
        val steps = RoadmapStep.parseList(task.roadmap).toMutableList()
        if (stepIndex !in steps.indices) return
        if (steps[stepIndex].completed == checked) return
        val pinKey = "$taskId:$stepIndex"
        val today = LocalDate.now()
        val rec = RoadmapStep.recommendedLocalDate(steps[stepIndex])
        val wasGetAheadStepCompleted = steps[stepIndex].completed
        val wasTodayStep = rec != null && rec == today
        val beforeTodayHalf = todayHalfwayRatio(beforeSnapshot, today)
        val dayBeforeComplete = rec?.takeIf { !it.isBefore(today) }?.let { day ->
            isPlanCompleteForDay(beforeSnapshot, today, day)
        } ?: false
        when {
            rec == null -> {
                homeTodayPlanPinnedCheckedKeys.remove(pinKey)
                homeGetAheadPinnedCheckedKeys.remove(pinKey)
            }
            !rec.isAfter(today) -> {
                if (checked) {
                    val planEntries = collectTodayPlanEntries(homeTasksSnapshot, today)
                        .filter { it.recommendedOn == today }
                    val hasIncompleteAfter = planEntries.any { e ->
                        val toggled = e.task.id == taskId && e.stepIndex == stepIndex
                        val done = if (toggled) checked else e.step.completed
                        !done
                    }
                    val todayPlanEffectivelyCollapsed = if (hasIncompleteAfter) {
                        !homeTodayPlanExpanded
                    } else {
                        rec.toString() !in homeCompletedReviewDaysExpanded
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
                                e.step.completed &&
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
        steps[stepIndex] = steps[stepIndex].copy(completed = checked)
        val previousStatus = task.status
        val derived = deriveStatusFromSteps(steps)
        val newRoadmap = RoadmapStep.toJsonArray(steps)
        homeTasksSnapshot = homeTasksSnapshot.map { row ->
            if (row.id == taskId) row.copy(roadmap = newRoadmap, status = derived) else row
        }
        bindHomeTodayPlan(homeTasksSnapshot)
        bindHomePreviewCards(homeTasksSnapshot.take(3))

        // Achievement: "First task completed" — repurposed to step-based:
        // trigger the first time the user checks off ANY step on today's plan.
        if (userIdForAchievements != null && wasTodayStep && checked) {
            AchievementManager.ensureLoaded(token, userIdForAchievements)
            AchievementManager.maybeShowFirstTaskCompleted(this, token, userIdForAchievements)
        }

        // Achievement: "Getting ahead" (first future step completion via Get Ahead / future-day work).
        if (userIdForAchievements != null && rec != null && rec.isAfter(today) && checked && !wasGetAheadStepCompleted) {
            AchievementManager.ensureLoaded(token, userIdForAchievements)
            AchievementManager.maybeShowGettingAhead(this, token, userIdForAchievements)
        }

        // Achievement: "Plan complete" (fires each time a day transitions to fully done).
        var planCompleteFired = false
        if (rec != null && !rec.isBefore(today)) {
            val afterComplete = isPlanCompleteForDay(homeTasksSnapshot, today, rec)
            if (!dayBeforeComplete && afterComplete) {
                planCompleteFired = true
                if (userIdForAchievements != null) {
                    val uid = userIdForAchievements
                    val dayForPopup = rec
                    networkExecutor.execute {
                        val streak = StreakCoordinator.resolveStreakForPlanComplete(token, uid, dayForPopup)
                        runOnUiThread {
                            if (!isFinishing) {
                                AchievementManager.showPlanComplete(this@HomeActivity, dayForPopup, streak)
                            }
                        }
                    }
                } else {
                    AchievementManager.showPlanComplete(this, rec, 1)
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
        val todayEntries = collectTodayPlanEntries(tasksSnapshot, today)
            .filter { it.recommendedOn == today }
        if (todayEntries.isEmpty()) return 0.0
        val totalSteps = todayEntries.size
        val doneSteps = todayEntries.count { it.step.completed }
        var totalHours = 0.0
        var doneHours = 0.0
        for (e in todayEntries) {
            val h = e.step.estimatedHours ?: 0.0
            totalHours += h
            if (e.step.completed) doneHours += h
        }
        return if (totalHours > 0.0) {
            (doneHours / totalHours).coerceIn(0.0, 1.0)
        } else if (totalSteps > 0) {
            (doneSteps.toDouble() / totalSteps.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    }

    private fun isPlanCompleteForDay(
        tasksSnapshot: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
        day: LocalDate,
    ): Boolean {
        val entries = if (day.isAfter(today)) {
            collectFuturePlanEntries(tasksSnapshot, today)
        } else {
            collectTodayPlanEntries(tasksSnapshot, today)
        }.filter { it.recommendedOn == day }
        return entries.isNotEmpty() && entries.all { it.step.completed }
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
                                bindHomePreviewCards(homeTasksSnapshot.take(3))
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
                            bindHomePreviewCards(homeTasksSnapshot.take(3))
                            if (currentTab == Tab.Tasks) {
                                loadTasks(showLoading = false)
                            }
                        }
                    }
                }
            }
        }
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

        title.text = task.title
        duePill.text = DueDateHumanLabel.format(this, task.dueDate, today, task.status)
        statusLine.text = TaskStatusUi.label(task.status)

        val summary = RoadmapProgress.summarize(task.roadmap)
        if (summary.total <= 0) {
            progress.visibility = View.GONE
        } else {
            progress.visibility = View.VISIBLE
            progress.progress = summary.percent
        }

        val overdue = DueDateHumanLabel.isOverdue(task.dueDate, task.status)
        if (urgent || overdue) {
            card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.home_urgent_card_fill))
            card.strokeWidth = resources.getDimensionPixelSize(R.dimen.home_urgent_card_stroke_width)
            card.strokeColor = ContextCompat.getColor(this, R.color.home_urgent_card_stroke)
            duePill.setBackgroundResource(R.drawable.bg_home_due_pill_urgent)
            duePill.setTextColor(ContextCompat.getColor(this, R.color.home_urgent_pill_text))
        } else {
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

    private fun openTaskDetail(task: SupabaseTasksApi.TaskRow) {
        openTaskDetailLauncher.launch(TaskDetailActivity.createIntent(this, task))
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
