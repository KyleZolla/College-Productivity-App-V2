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
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
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

    private val addCourseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        loadMyCourses(showLoading = false)
        if (currentTab == Tab.Home) {
            loadHomeUpcoming(showLoading = false)
        }
        val wasEdit = result.data?.getBooleanExtra(AddCourseActivity.EXTRA_WAS_EDIT, false) == true
        Toast.makeText(
            this,
            if (wasEdit) R.string.course_updated else R.string.course_saved,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private val backgroundCreateListener = object : BackgroundCreateJobs.Listener {
        override fun onCourseCreateSucceeded(profileSyncFailed: Boolean) {
            if (isFinishing) return
            loadMyCourses(showLoading = false)
            if (currentTab == Tab.Home) {
                loadHomeUpcoming(showLoading = false)
            }
            Toast.makeText(
                this@HomeActivity,
                if (profileSyncFailed) {
                    R.string.course_profile_generation_failed
                } else {
                    R.string.course_saved
                },
                Toast.LENGTH_LONG,
            ).show()
        }

        override fun onCourseCreateFailed(message: String) {
            if (isFinishing) return
            Toast.makeText(
                this@HomeActivity,
                getString(R.string.error_course_save_failed) + "\n" + message,
                Toast.LENGTH_LONG,
            ).show()
        }

        override fun onTaskCreateSucceeded() {
            if (isFinishing) return
            loadTasks(showLoading = false)
            if (currentTab == Tab.Home) {
                loadHomeUpcoming(showLoading = false)
            }
            Toast.makeText(this@HomeActivity, R.string.task_saved, Toast.LENGTH_SHORT).show()
        }

        override fun onTaskCreateFailed(message: String) {
            if (isFinishing) return
            Toast.makeText(
                this@HomeActivity,
                getString(R.string.error_task_create_failed) + "\n" + message,
                Toast.LENGTH_LONG,
            ).show()
        }

        override fun onTaskCreateNotice(title: String, message: String) {
            if (isFinishing) return
            MaterialAlertDialogBuilder(this@HomeActivity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
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
    private lateinit var homeOverdueRecoveryCard: MaterialCardView
    private lateinit var homeOverdueRecoverySubtitle: TextView
    private lateinit var homeOverdueRecoveryAddToToday: MaterialButton
    private lateinit var homeOverdueRecoverySpreadOut: MaterialButton
    private lateinit var homeOverdueRecoveryKeepAsIs: MaterialButton
    private lateinit var homeTodayPlanSection: LinearLayout
    private lateinit var homeTodayPlanProgressBlock: LinearLayout
    private lateinit var homeTodayPlanStepsSummary: TextView
    private lateinit var homeTodayPlanHoursSummary: TextView
    private lateinit var homeTodayPlanHoursProgress: ProgressBar
    private lateinit var homeTodayPlanTimeLimitBlock: LinearLayout
    private lateinit var homeTodayPlanTimeLimitStatus: TextView
    private lateinit var homeTodayPlanTimeLimitChip30m: Chip
    private lateinit var homeTodayPlanTimeLimitChip1h: Chip
    private lateinit var homeTodayPlanTimeLimitChip2h: Chip
    private lateinit var homeTodayPlanTimeLimitChipCustom: Chip
    private lateinit var homeTodayPlanTimeLimitChipClear: Chip
    private lateinit var homeTodayPlanAdjustmentCard: MaterialCardView
    private lateinit var homeTodayPlanAdjustmentSummary: TextView
    private lateinit var homeTodayPlanAdjustmentFocusList: LinearLayout
    private lateinit var homeTodayPlanAdjustmentMoveList: LinearLayout
    private lateinit var homeTodayPlanAdjustmentBlockedNote: TextView
    private lateinit var homeTodayPlanAdjustmentApply: MaterialButton
    private lateinit var homeTodayPlanAdjustmentKeepOriginal: MaterialButton
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
    private lateinit var homeGetAheadCompletedReviewHost: LinearLayout
    private lateinit var homeUpcomingLoading: ProgressBar
    private lateinit var homeWeekAheadSection: LinearLayout
    private lateinit var homeWeekAheadCard: MaterialCardView
    private lateinit var homeWeekAheadSummaryLine: TextView
    private lateinit var homeWeekAheadBusiestLine: TextView
    private lateinit var homeWeekAheadNextLine: TextView
    private lateinit var homeWeekAheadExpandedHost: LinearLayout

    private var homeTasksSnapshot: List<SupabaseTasksApi.TaskRow> = emptyList()
    /** Task ids currently shown in Coming up; stable until a task fully completes or data reloads. */
    private var homeUpcomingDisplayedIds: List<String> = emptyList()
    private var homeRoadmapPatchInFlight = false
    private var homeTodayPlanExpanded = false
    private var homeWeekAheadExpanded = false
    private val homeCompletedReviewDaysExpanded = mutableSetOf<String>()
    private val homeTodayPlanPinnedCheckedKeys = mutableSetOf<String>()
    private val homeGetAheadPinnedCheckedKeys = mutableSetOf<String>()
    /** taskId:stepIndex keys where the user already answered the estimate feedback prompt this session. */
    private val estimateFeedbackAnsweredKeys = mutableSetOf<String>()
    private var homeGetAheadFocusDate: LocalDate? = null
    private lateinit var homeUpcomingEmpty: TextView
    private lateinit var homeUpcomingCards: LinearLayout
    private lateinit var homeViewAllTasks: TextView

    private var achievementsUserId: String? = null
    private var courseLabelsById: Map<String, String> = emptyMap()

    private lateinit var profileDisplayName: TextView
    private lateinit var profileEmail: TextView
    private lateinit var profileSchoolInput: TextInputEditText
    private lateinit var profileYearInSchoolDropdown: MaterialAutoCompleteTextView
    private lateinit var profileSaveAccountButton: MaterialButton
    private lateinit var profileCoursesLoading: ProgressBar
    private lateinit var profileCoursesEmpty: TextView
    private lateinit var profileCoursesList: LinearLayout
    private var courseDeleteInFlight = false
    private var profileAccountSaveInFlight = false

    private lateinit var homeRoot: View
    /** taskId:stepIndex -> original recommendedDate before a recovery reschedule. */
    private var overdueRecoveryUndoSnapshot: Map<String, String>? = null
    private var overdueRecoveryUndoWasDismissOnly = false
    private var overdueRecoveryKeepAsIsPending = false
    private var overdueRecoveryUndoMessageRes: Int? = null
    private var overdueRecoveryUndoSnackbar: Snackbar? = null
    /** When false, programmatic snackbar dismiss must not clear undo state. */
    private var overdueRecoverySnackbarDismissShouldFinalize = true
    private var overdueRecoveryOperationGeneration = 0

    /** taskId|stepIndex -> original recommendedDate before a today-plan adjustment apply. */
    private var todayPlanAdjustmentUndoSnapshot: Map<String, String>? = null
    private var todayPlanAdjustmentUndoSnackbar: Snackbar? = null
    private var todayPlanAdjustmentOperationGeneration = 0
    private var todayPlanAdjustmentDismissed = false
    private var todayPlanAdjustmentSuppressChipCallback = false
    private var todayPlanAdjustmentCachedPlan: TodayPlanAdjustment.Plan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        homeRoot = findViewById(R.id.homeRoot)
        val bottomBar = findViewById<LinearLayout>(R.id.bottomNavBar)

        ViewCompat.setOnApplyWindowInsetsListener(homeRoot) { v, insets ->
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
        homeOverdueRecoveryCard = findViewById(R.id.homeOverdueRecoveryCard)
        homeOverdueRecoverySubtitle = findViewById(R.id.homeOverdueRecoverySubtitle)
        homeOverdueRecoveryAddToToday = findViewById(R.id.homeOverdueRecoveryAddToToday)
        homeOverdueRecoverySpreadOut = findViewById(R.id.homeOverdueRecoverySpreadOut)
        homeOverdueRecoveryKeepAsIs = findViewById(R.id.homeOverdueRecoveryKeepAsIs)
        homeOverdueRecoveryAddToToday.setOnClickListener {
            applyOverdueRecoveryReschedule(OverdueStepRecovery.RescheduleMode.ADD_TO_TODAY)
        }
        homeOverdueRecoverySpreadOut.setOnClickListener {
            applyOverdueRecoveryReschedule(OverdueStepRecovery.RescheduleMode.SPREAD_OUT)
        }
        homeOverdueRecoveryKeepAsIs.setOnClickListener { dismissOverdueRecoveryCard() }
        homeTodayPlanSection = findViewById(R.id.homeTodayPlanSection)
        homeTodayPlanProgressBlock = findViewById(R.id.homeTodayPlanProgressBlock)
        homeTodayPlanStepsSummary = findViewById(R.id.homeTodayPlanStepsSummary)
        homeTodayPlanHoursSummary = findViewById(R.id.homeTodayPlanHoursSummary)
        homeTodayPlanHoursProgress = findViewById(R.id.homeTodayPlanHoursProgress)
        homeTodayPlanTimeLimitBlock = findViewById(R.id.homeTodayPlanTimeLimitBlock)
        homeTodayPlanTimeLimitStatus = findViewById(R.id.homeTodayPlanTimeLimitStatus)
        homeTodayPlanTimeLimitChip30m = findViewById(R.id.homeTodayPlanTimeLimit30m)
        homeTodayPlanTimeLimitChip1h = findViewById(R.id.homeTodayPlanTimeLimit1h)
        homeTodayPlanTimeLimitChip2h = findViewById(R.id.homeTodayPlanTimeLimit2h)
        homeTodayPlanTimeLimitChipCustom = findViewById(R.id.homeTodayPlanTimeLimitCustom)
        homeTodayPlanTimeLimitChipClear = findViewById(R.id.homeTodayPlanTimeLimitClear)
        homeTodayPlanAdjustmentCard = findViewById(R.id.homeTodayPlanAdjustmentCard)
        homeTodayPlanAdjustmentSummary = findViewById(R.id.homeTodayPlanAdjustmentSummary)
        homeTodayPlanAdjustmentFocusList = findViewById(R.id.homeTodayPlanAdjustmentFocusList)
        homeTodayPlanAdjustmentMoveList = findViewById(R.id.homeTodayPlanAdjustmentMoveList)
        homeTodayPlanAdjustmentBlockedNote = findViewById(R.id.homeTodayPlanAdjustmentBlockedNote)
        homeTodayPlanAdjustmentApply = findViewById(R.id.homeTodayPlanAdjustmentApply)
        homeTodayPlanAdjustmentKeepOriginal = findViewById(R.id.homeTodayPlanAdjustmentKeepOriginal)
        homeTodayPlanAdjustmentApply.setOnClickListener { applyTodayPlanAdjustment() }
        homeTodayPlanAdjustmentKeepOriginal.setOnClickListener { dismissTodayPlanAdjustmentCard() }
        homeTodayPlanTimeLimitChip30m.setOnClickListener { setTodayPlanTimeLimitMinutes(30) }
        homeTodayPlanTimeLimitChip1h.setOnClickListener { setTodayPlanTimeLimitMinutes(60) }
        homeTodayPlanTimeLimitChip2h.setOnClickListener { setTodayPlanTimeLimitMinutes(120) }
        homeTodayPlanTimeLimitChipCustom.setOnClickListener { showCustomTodayPlanTimeLimitDialog() }
        homeTodayPlanTimeLimitChipClear.setOnClickListener { clearTodayPlanTimeLimit() }
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
        homeGetAheadCompletedReviewHost = findViewById(R.id.homeGetAheadCompletedReviewHost)
        homeUpcomingLoading = findViewById(R.id.homeUpcomingLoading)
        homeWeekAheadSection = findViewById(R.id.homeWeekAheadSection)
        homeWeekAheadCard = findViewById(R.id.homeWeekAheadCard)
        homeWeekAheadSummaryLine = findViewById(R.id.homeWeekAheadSummaryLine)
        homeWeekAheadBusiestLine = findViewById(R.id.homeWeekAheadBusiestLine)
        homeWeekAheadNextLine = findViewById(R.id.homeWeekAheadNextLine)
        homeWeekAheadExpandedHost = findViewById(R.id.homeWeekAheadExpandedHost)
        homeWeekAheadCard.setOnClickListener {
            homeWeekAheadExpanded = !homeWeekAheadExpanded
            bindWeekAheadSection(homeTasksSnapshot.filter { it.status != TaskStatus.COMPLETE })
        }
        homeUpcomingEmpty = findViewById(R.id.homeUpcomingEmpty)
        homeUpcomingCards = findViewById(R.id.homeUpcomingCards)
        homeViewAllTasks = findViewById(R.id.homeViewAllTasks)
        homeViewAllTasks.setOnClickListener { showTab(Tab.Tasks) }

        profileDisplayName = findViewById(R.id.profileDisplayName)
        profileEmail = findViewById(R.id.profileEmail)
        profileSchoolInput = findViewById(R.id.profileSchoolInput)
        profileYearInSchoolDropdown = findViewById(R.id.profileYearInSchoolDropdown)
        profileSaveAccountButton = findViewById(R.id.profileSaveAccountButton)
        YearInSchoolHelper.bind(this, profileYearInSchoolDropdown, selected = null)
        profileCoursesLoading = findViewById(R.id.profileCoursesLoading)
        profileCoursesEmpty = findViewById(R.id.profileCoursesEmpty)
        profileCoursesList = findViewById(R.id.profileCoursesList)

        findViewById<MaterialButton>(R.id.addCourseButton).setOnClickListener {
            addCourseLauncher.launch(AddCourseActivity.createIntent(this))
        }

        profileSaveAccountButton.setOnClickListener {
            saveProfileAccountFields()
        }

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

    override fun onStart() {
        super.onStart()
        BackgroundCreateJobs.addListener(backgroundCreateListener)
    }

    override fun onStop() {
        BackgroundCreateJobs.removeListener(backgroundCreateListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        when (currentTab) {
            Tab.Tasks -> loadTasks(showLoading = true)
            Tab.Home -> {
                refreshHomeHeader()
                loadHomeUpcoming(showLoading = true)
            }
            Tab.Profile -> {
                refreshProfileCard()
                loadProfileAccountFields()
                loadMyCourses(showLoading = true)
            }
        }
        val token = SessionManager.getAccessToken(this)
        if (!token.isNullOrBlank()) {
            // Preload earned achievements (best-effort).
            networkExecutor.execute {
                achievementsUserId = SupabaseUserId.resolveUserId(token)
                achievementsUserId?.let { uid ->
                    AchievementManager.ensureLoaded(token, uid)
                    when (val result = SupabaseRoadmapStepEstimateFeedbackApi.listAnsweredKeys(token, uid)) {
                        is SupabaseRoadmapStepEstimateFeedbackApi.ListResult.Success -> runOnUiThread {
                            estimateFeedbackAnsweredKeys.clear()
                            estimateFeedbackAnsweredKeys.addAll(
                                answeredKeysForCompletedSteps(result.answeredKeys, homeTasksSnapshot),
                            )
                            if (
                                !homeRoadmapPatchInFlight &&
                                ::homeTodayPlanSection.isInitialized &&
                                homeTasksSnapshot.isNotEmpty()
                            ) {
                                bindHomeTodayPlan(homeTasksSnapshot)
                            }
                        }
                        is SupabaseRoadmapStepEstimateFeedbackApi.ListResult.Failure -> Unit
                    }
                }
            }
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
            Tab.Profile -> {
                refreshProfileCard()
                loadProfileAccountFields()
                loadMyCourses(showLoading = true)
            }
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
            profileSchoolInput.setText("")
            YearInSchoolHelper.bind(this, profileYearInSchoolDropdown, selected = null)
            profileSaveAccountButton.isEnabled = false
            profileCoursesList.removeAllViews()
            profileCoursesEmpty.text = getString(R.string.error_task_not_signed_in)
            profileCoursesEmpty.visibility = View.VISIBLE
            profileCoursesLoading.visibility = View.GONE
            return
        }
        val name = AuthUserDisplayName.displayNameForProfile(token)
        profileDisplayName.text = name.ifBlank { getString(R.string.profile_name_fallback) }
        profileEmail.text = AuthUserDisplayName.emailFromAccessToken(token)
    }

    private fun loadProfileAccountFields() {
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            profileSchoolInput.setText("")
            YearInSchoolHelper.bind(this, profileYearInSchoolDropdown, selected = null)
            profileSaveAccountButton.isEnabled = false
            return
        }
        profileSaveAccountButton.isEnabled = true
        val userId = SupabaseUserId.resolveUserId(token)
        if (userId.isNullOrBlank()) return

        networkExecutor.execute {
            SignupProfilePending.flushBlocking(applicationContext, token)
            when (val result = SupabaseProfilesApi.get(token, userId)) {
                is SupabaseProfilesApi.GetResult.Success -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    profileSchoolInput.setText(result.row.school.orEmpty())
                    YearInSchoolHelper.bind(this, profileYearInSchoolDropdown, result.row.yearInSchool)
                }
                is SupabaseProfilesApi.GetResult.NotFound -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    profileSchoolInput.setText("")
                    YearInSchoolHelper.bind(this, profileYearInSchoolDropdown, selected = null)
                }
                is SupabaseProfilesApi.GetResult.Failure -> Unit
            }
        }
    }

    private fun saveProfileAccountFields() {
        if (profileAccountSaveInFlight) return
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
            return
        }
        val userId = SupabaseUserId.resolveUserId(token)
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_user_unknown, Toast.LENGTH_SHORT).show()
            return
        }

        val school = profileSchoolInput.text?.toString()?.trim().orEmpty().ifBlank { null }
        val yearInSchool = YearInSchoolHelper.selectedValue(profileYearInSchoolDropdown)

        profileAccountSaveInFlight = true
        profileSaveAccountButton.isEnabled = false
        profileSaveAccountButton.text = getString(R.string.profile_account_saving)

        networkExecutor.execute {
            when (val result = SupabaseProfilesApi.upsertAccountFields(token, userId, school, yearInSchool)) {
                is SupabaseProfilesApi.PatchResult.Success -> runOnUiThread {
                    profileAccountSaveInFlight = false
                    if (isFinishing) return@runOnUiThread
                    profileSaveAccountButton.isEnabled = true
                    profileSaveAccountButton.text = getString(R.string.profile_save_account)
                    Toast.makeText(this, R.string.profile_account_saved, Toast.LENGTH_SHORT).show()
                }
                is SupabaseProfilesApi.PatchResult.Failure -> runOnUiThread {
                    profileAccountSaveInFlight = false
                    if (isFinishing) return@runOnUiThread
                    profileSaveAccountButton.isEnabled = true
                    profileSaveAccountButton.text = getString(R.string.profile_save_account)
                    Toast.makeText(
                        this,
                        getString(R.string.profile_account_save_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun loadMyCourses(showLoading: Boolean = true) {
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            profileCoursesList.removeAllViews()
            profileCoursesEmpty.text = getString(R.string.error_task_not_signed_in)
            profileCoursesEmpty.visibility = View.VISIBLE
            profileCoursesLoading.visibility = View.GONE
            return
        }

        val userId = SupabaseUserId.resolveUserId(token)
        if (userId.isNullOrBlank()) {
            profileCoursesList.removeAllViews()
            profileCoursesEmpty.text = getString(R.string.error_task_user_unknown)
            profileCoursesEmpty.visibility = View.VISIBLE
            profileCoursesLoading.visibility = View.GONE
            return
        }

        if (showLoading) {
            profileCoursesLoading.visibility = View.VISIBLE
            profileCoursesEmpty.visibility = View.GONE
            profileCoursesList.removeAllViews()
        }

        networkExecutor.execute {
            when (val result = SupabaseCoursesApi.listCourses(token, userId)) {
                is SupabaseCoursesApi.ListResult.Success -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    profileCoursesLoading.visibility = View.GONE
                    bindMyCourses(result.courses)
                }
                is SupabaseCoursesApi.ListResult.Failure -> runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    profileCoursesLoading.visibility = View.GONE
                    profileCoursesList.removeAllViews()
                    profileCoursesEmpty.text =
                        getString(R.string.my_courses_load_failed) + "\n" + result.message
                    profileCoursesEmpty.visibility = View.VISIBLE
                    if (showLoading) {
                        Toast.makeText(
                            this,
                            getString(R.string.my_courses_load_failed) + "\n" + result.message,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun bindMyCourses(courses: List<SupabaseCoursesApi.CourseRow>) {
        profileCoursesList.removeAllViews()
        if (courses.isEmpty()) {
            profileCoursesEmpty.text = getString(R.string.my_courses_empty)
            profileCoursesEmpty.visibility = View.VISIBLE
            return
        }
        profileCoursesEmpty.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (course in courses) {
            val row = inflater.inflate(R.layout.item_profile_course, profileCoursesList, false)
            row.findViewById<TextView>(R.id.profileCourseName).text = course.name
            row.findViewById<TextView>(R.id.profileCourseLevel).text =
                getString(R.string.course_level_display, course.level)
            row.findViewById<MaterialButton>(R.id.profileCourseEditButton).setOnClickListener {
                addCourseLauncher.launch(AddCourseActivity.editIntent(this, course.id))
            }
            row.findViewById<MaterialButton>(R.id.profileCourseDeleteButton).setOnClickListener {
                confirmDeleteCourse(course)
            }
            profileCoursesList.addView(row)
        }
    }

    private fun confirmDeleteCourse(course: SupabaseCoursesApi.CourseRow) {
        if (courseDeleteInFlight) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.course_delete_confirm_title)
            .setMessage(R.string.course_delete_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.course_delete_confirm_button) { _, _ ->
                deleteCourseFromServer(course)
            }
            .show()
    }

    private fun deleteCourseFromServer(course: SupabaseCoursesApi.CourseRow) {
        if (courseDeleteInFlight) return
        val token = SessionManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_task_not_signed_in, Toast.LENGTH_SHORT).show()
            return
        }
        courseDeleteInFlight = true
        networkExecutor.execute {
            when (val result = SupabaseCoursesApi.deleteCourse(token, course.id)) {
                is SupabaseCoursesApi.DeleteResult.Success -> runOnUiThread {
                    courseDeleteInFlight = false
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(this, R.string.course_deleted, Toast.LENGTH_SHORT).show()
                    loadMyCourses(showLoading = false)
                }
                is SupabaseCoursesApi.DeleteResult.Failure -> runOnUiThread {
                    courseDeleteInFlight = false
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(
                        this,
                        getString(R.string.error_course_delete_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
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
            homeWeekAheadSection.visibility = View.GONE
            homeOverdueRecoveryCard.visibility = View.GONE
            homeUpcomingEmpty.text = getString(R.string.error_task_not_signed_in)
            homeUpcomingEmpty.visibility = View.VISIBLE
            return
        }

        if (showLoading) {
            homeUpcomingLoading.visibility = View.VISIBLE
            homeUpcomingEmpty.visibility = View.GONE
            homeUpcomingCards.visibility = View.GONE
            homeTodayPlanSection.visibility = View.GONE
            homeWeekAheadSection.visibility = View.GONE
            homeOverdueRecoveryCard.visibility = View.GONE
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
                                TodayPlanWork.wasTaskCompletedToday(task, today) &&
                                TodayPlanWork.simpleTaskDueLocalDate(task)?.let { !it.isAfter(today) } == true
                        }
                        is SupabaseTasksApi.ListResult.Failure -> emptyList()
                    }
                    val simpleFutureCompletedForGetAhead = when (completedAll) {
                        is SupabaseTasksApi.ListResult.Success -> completedAll.tasks.filter { task ->
                            TaskKind.isSimpleTask(task) &&
                                TodayPlanWork.simpleTaskDueLocalDate(task)?.isAfter(today) == true &&
                                TodayPlanWork.wasTaskCompletedToday(task, today)
                        }
                        is SupabaseTasksApi.ListResult.Failure -> emptyList()
                    }
                    val mergedForPlan = (active.tasks + extra + simpleCompletedForPlan + simpleFutureCompletedForGetAhead)
                        .distinctBy { it.id }
                    val labels = fetchCourseLabels(token)
                    runOnUiThread {
                        courseLabelsById = labels
                        tasksAdapter.setCourseLabels(labels)
                        if (showLoading) homeUpcomingLoading.visibility = View.GONE
                        homeUpcomingDisplayedIds = emptyList()
                        resetHomeUpcomingPreview(active.tasks)
                        if (!homeRoadmapPatchInFlight) {
                            bindHomeTodayPlan(mergedForPlan)
                        }
                    }
                }
                is SupabaseTasksApi.ListResult.Failure -> runOnUiThread {
                    if (showLoading) homeUpcomingLoading.visibility = View.GONE
                    if (showLoading) {
                        homeTodayPlanSection.visibility = View.GONE
                        homeWeekAheadSection.visibility = View.GONE
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

    private fun estimateFeedbackKey(taskId: String, stepIndex: Int): String = "$taskId:$stepIndex"

    /** Ignore feedback rows for steps that are not currently marked complete in [tasks]. */
    private fun answeredKeysForCompletedSteps(
        keys: Set<String>,
        tasks: List<SupabaseTasksApi.TaskRow>,
    ): Set<String> =
        keys.filter { key ->
            val parts = key.split(':', limit = 2)
            if (parts.size != 2) return@filter false
            val taskId = parts[0]
            val stepIndex = parts[1].toIntOrNull() ?: return@filter false
            val task = tasks.find { it.id == taskId } ?: return@filter false
            val steps = RoadmapStep.parseList(task.roadmap)
            stepIndex in steps.indices && steps[stepIndex].completed
        }.toSet()

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
                // Match complex steps: keep simple tasks due today visible after check-off (pinned/review).
                entry.isSimple -> entry.recommendedOn == today
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
        return todayPlanScopeEntries(tasks, today).filter { entry ->
            if (entry.isSimple) {
                TodayPlanWork.simpleCountsTowardTodayProgress(
                    entry.task,
                    entry.recommendedOn,
                    today,
                    zone,
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
        return todayPlanScopeEntries(tasks, today).filter { entry ->
            when {
                entry.isSimple -> TodayPlanWork.wasTaskCompletedToday(entry.task, today, zone)
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
     * Ordered review buckets: today only after the full day plan is complete; future days only when
     * today's work is done — fully completed days before the active Get ahead focus, or all fully
     * completed future days when Get ahead is hidden because everything ahead is done.
     */
    private fun buildCompletedReviewDays(
        todayEntries: List<TodayPlanEntry>,
        futureEntries: List<TodayPlanEntry>,
        today: LocalDate,
        getAheadVisible: Boolean,
        focusDay: LocalDate?,
        includeTodayReview: Boolean,
        hasIncompleteToday: Boolean,
    ): List<Pair<LocalDate, List<TodayPlanEntry>>> {
        val out = ArrayList<Pair<LocalDate, List<TodayPlanEntry>>>()
        if (includeTodayReview && todayEntries.isNotEmpty()) {
            out.add(today to sortTodayPlanEntries(todayEntries, today))
        }
        if (hasIncompleteToday) {
            return out
        }
        futureEntries.groupBy { it.recommendedOn }.toSortedMap().forEach { (day, list) ->
            if (list.isEmpty() || list.none { it.isCompleted }) return@forEach
            val allDoneOnDay = list.all { it.isCompleted }
            when {
                getAheadVisible && focusDay != null -> {
                    if (day == focusDay) return@forEach
                    if (!day.isBefore(focusDay)) return@forEach
                    if (!allDoneOnDay) return@forEach
                }
                else -> {
                    if (!allDoneOnDay) return@forEach
                }
            }
            out.add(day to sortGetAheadDayEntries(list))
        }
        return out
    }

    private fun formatTodayPlanCollapsedEmptyText(): String =
        getString(R.string.home_today_plan_empty_ahead)

    /** Shown when today has no active steps left — even if Get ahead or review sections are visible. */
    private fun bindTodayPlanIdleMessage(
        hasIncomplete: Boolean,
        workEntries: List<TodayPlanEntry>,
        reviewDays: List<Pair<LocalDate, List<TodayPlanEntry>>>,
        today: LocalDate,
    ) {
        if (hasIncomplete) {
            homeTodayPlanEmpty.visibility = View.GONE
            return
        }
        val todayReviewVisible = reviewDays.any { (day, list) -> day == today && list.isNotEmpty() }
        if (todayReviewVisible) {
            homeTodayPlanEmpty.visibility = View.GONE
            return
        }
        homeTodayPlanEmpty.text = if (workEntries.isEmpty()) {
            formatTodayPlanCollapsedEmptyText()
        } else {
            getString(R.string.home_today_plan_all_done_banner_today)
        }
        homeTodayPlanEmpty.visibility = View.VISIBLE
    }

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
            if (day.isAfter(today) || day == today) {
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

    private fun isPlanEntryCompletedToday(entry: TodayPlanEntry, today: LocalDate): Boolean {
        val zone = ZoneId.systemDefault()
        return when {
            entry.isSimple -> TodayPlanWork.wasTaskCompletedToday(entry.task, today, zone)
            else -> TodayPlanWork.wasCompletedToday(entry.step!!, today, zone)
        }
    }

    private fun buildCollapsedVisibleEntries(
        workEntries: List<TodayPlanEntry>,
        scopeEntries: List<TodayPlanEntry>,
        today: LocalDate,
    ): List<TodayPlanEntry> {
        prunePinnedTodayPlanKeys(scopeEntries)
        val sorted = sortTodayPlanEntries(workEntries, today)
        val pinnedKeys = homeTodayPlanPinnedCheckedKeys
        var uncheckedShown = 0
        val out = ArrayList<TodayPlanEntry>()
        for (entry in sorted) {
            when {
                !entry.isCompleted && uncheckedShown < 2 -> {
                    out.add(entry)
                    uncheckedShown++
                }
                entry.isCompleted && (
                    todayPlanEntryKey(entry) in pinnedKeys ||
                        isPlanEntryCompletedToday(entry, today)
                    ) -> out.add(entry)
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

    /** Earliest future calendar day that still has at least one incomplete step. */
    private fun resolveGetAheadFocusDate(futureEntries: List<TodayPlanEntry>): LocalDate? =
        futureEntries.groupBy { it.recommendedOn }
            .toSortedMap()
            .entries
            .firstOrNull { (_, list) -> list.any { !it.isCompleted } }
            ?.key

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
        val rank = sorted.withIndex().associate { (i, e) -> todayPlanEntryKey(e) to i }
        return out.sortedBy { rank[todayPlanEntryKey(it)] ?: Int.MAX_VALUE }
    }

    private fun clearGetAheadSection() {
        homeGetAheadSection.visibility = View.GONE
        homeGetAheadProgressBlock.visibility = View.GONE
        homeGetAheadGroups.removeAllViews()
        homeGetAheadCompletedReviewHost.removeAllViews()
        homeGetAheadCompletedReviewHost.visibility = View.GONE
        homeGetAheadFocusDate = null
    }

    /** @return true if the Get ahead block is visible after binding. */
    private fun bindGetAheadSection(
        allActiveTasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): Boolean {
        var iterations = 0
        while (iterations++ < 400) {
            val futureEntries = collectFuturePlanEntries(allActiveTasks, today)
            pruneGetAheadPinnedKeys(futureEntries)
            if (futureEntries.isEmpty()) {
                clearGetAheadSection()
                return false
            }
            val focus = resolveGetAheadFocusDate(futureEntries) ?: run {
                clearGetAheadSection()
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
        clearGetAheadSection()
        return false
    }

    /** Collapsible completed steps for the active Get ahead day (progress stays in the header above). */
    private fun bindGetAheadFocusDayCompletedReview(
        focusDay: LocalDate,
        dayEntries: List<TodayPlanEntry>,
        visibleInMainListKeys: Set<String>,
    ) {
        homeGetAheadCompletedReviewHost.removeAllViews()
        val completed = sortGetAheadDayEntries(
            dayEntries.filter {
                it.isCompleted && todayPlanEntryKey(it) !in visibleInMainListKeys
            },
        )
        if (completed.isEmpty()) {
            homeGetAheadCompletedReviewHost.visibility = View.GONE
            return
        }
        homeGetAheadCompletedReviewHost.visibility = View.VISIBLE
        val block = LayoutInflater.from(this).inflate(
            R.layout.item_home_completed_day_review,
            homeGetAheadCompletedReviewHost,
            false,
        )
        block.findViewById<TextView>(R.id.homeCompletedDayTitle).visibility = View.GONE
        block.findViewById<LinearLayout>(R.id.homeCompletedDayProgressBlock).visibility = View.GONE
        block.findViewById<TextView>(R.id.homeCompletedDayAllDoneBanner).visibility = View.GONE
        val toggle = block.findViewById<MaterialButton>(R.id.homeCompletedDayToggle)
        val stepsHost = block.findViewById<LinearLayout>(R.id.homeCompletedDaySteps)
        val dayKey = focusDay.toString()
        val expanded = dayKey in homeCompletedReviewDaysExpanded
        toggle.text = if (expanded) {
            getString(R.string.home_today_plan_show_less)
        } else {
            getString(R.string.home_today_plan_view_completed)
        }
        if (expanded) {
            stepsHost.visibility = View.VISIBLE
            bindPlanStepRows(stepsHost, completed)
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
        homeGetAheadCompletedReviewHost.addView(block)
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

    private fun orderComplexPlanEntriesByRoadmap(entries: List<TodayPlanEntry>): List<TodayPlanEntry> {
        if (entries.isEmpty()) return entries
        val byTask = entries.groupBy { it.task.id }
        return entries.map { it.task.id }.distinct().flatMap { taskId ->
            byTask[taskId].orEmpty().sortedBy { it.stepIndex }
        }
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
        for (entry in orderComplexPlanEntriesByRoadmap(visibleEntries.filter { !it.isSimple })) {
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
        RoadmapEstimateFeedbackUi.bind(
            root = row,
            showPrompt = entry.isCompleted &&
                estimateFeedbackKey(entry.task.id, entry.stepIndex) !in estimateFeedbackAnsweredKeys,
            enabled = true,
            onFeedbackSelected = { feedback ->
                onRoadmapEstimateFeedbackSelected(entry.task.id, entry.stepIndex, feedback)
            },
        )
        parent.addView(row)
    }

    private fun bindOverdueRecoveryCard(allActiveTasks: List<SupabaseTasksApi.TaskRow>, today: LocalDate) {
        if (overdueRecoveryKeepAsIsPending) {
            homeOverdueRecoveryCard.visibility = View.GONE
            return
        }
        if (OverdueRecoveryDismissal.isDismissedForToday(this, today)) {
            homeOverdueRecoveryCard.visibility = View.GONE
            return
        }
        val summary = OverdueStepRecovery.collectSummary(allActiveTasks, today)
        if (summary.stepCount == 0) {
            homeOverdueRecoveryCard.visibility = View.GONE
            return
        }
        homeOverdueRecoverySubtitle.text = when {
            summary.taskCount == 1 && summary.singleTaskTitle != null ->
                getString(
                    R.string.home_overdue_recovery_subtitle_single_task,
                    summary.stepCount,
                    summary.singleTaskTitle,
                )
            summary.taskCount > 1 ->
                getString(
                    R.string.home_overdue_recovery_subtitle_multi_task,
                    summary.stepCount,
                    summary.taskCount,
                )
            else ->
                getString(R.string.home_overdue_recovery_subtitle_generic, summary.stepCount)
        }
        val actionsEnabled = !homeRoadmapPatchInFlight
        homeOverdueRecoveryAddToToday.isEnabled = actionsEnabled
        homeOverdueRecoverySpreadOut.isEnabled = actionsEnabled
        homeOverdueRecoveryKeepAsIs.isEnabled = actionsEnabled
        homeOverdueRecoveryCard.visibility = View.VISIBLE
    }

    private fun dismissOverdueRecoveryCard() {
        overdueRecoveryKeepAsIsPending = true
        homeOverdueRecoveryCard.visibility = View.GONE
        overdueRecoveryUndoSnapshot = null
        overdueRecoveryUndoWasDismissOnly = true
        showOverdueRecoveryUndoSnackbar(R.string.home_overdue_recovery_kept_as_is)
    }

    private fun captureOverdueRecoveryOriginalDates(): Map<String, String> {
        val today = LocalDate.now()
        val summary = OverdueStepRecovery.collectSummary(homeTasksSnapshot, today)
        val originalDates = LinkedHashMap<String, String>()
        for (ref in summary.steps) {
            val task = homeTasksSnapshot.find { it.id == ref.taskId } ?: continue
            val steps = RoadmapStep.parseList(task.roadmap)
            if (ref.stepIndex !in steps.indices) continue
            originalDates[overdueRecoveryStepKey(ref.taskId, ref.stepIndex)] =
                steps[ref.stepIndex].recommendedDate
        }
        return originalDates
    }

    private fun overdueRecoveryStepKey(taskId: String, stepIndex: Int): String = "$taskId|$stepIndex"

    private fun parseOverdueRecoveryStepKey(key: String): Pair<String, Int>? {
        val separator = key.lastIndexOf('|')
        if (separator <= 0 || separator == key.lastIndex) return null
        val taskId = key.substring(0, separator)
        val stepIndex = key.substring(separator + 1).toIntOrNull() ?: return null
        return taskId to stepIndex
    }

    private fun buildOverdueRecoveryRestoreUpdates(
        originalDates: Map<String, String>,
    ): List<OverdueStepRecovery.TaskRoadmapUpdate> {
        val updates = ArrayList<OverdueStepRecovery.TaskRoadmapUpdate>()
        val keysByTask = originalDates.keys.groupBy { key ->
            parseOverdueRecoveryStepKey(key)?.first ?: key.substringBeforeLast('|')
        }
        for ((taskId, keys) in keysByTask) {
            val task = homeTasksSnapshot.find { it.id == taskId } ?: continue
            val steps = RoadmapStep.parseList(task.roadmap).toMutableList()
            for (key in keys) {
                val (parsedTaskId, stepIndex) = parseOverdueRecoveryStepKey(key) ?: continue
                if (parsedTaskId != taskId) continue
                val originalDate = originalDates[key] ?: continue
                if (stepIndex !in steps.indices) continue
                steps[stepIndex] = steps[stepIndex].copy(recommendedDate = originalDate)
            }
            updates.add(
                OverdueStepRecovery.TaskRoadmapUpdate(
                    taskId = taskId,
                    roadmap = RoadmapStep.toJsonArray(steps),
                ),
            )
        }
        return updates
    }

    private fun finalizeOverdueRecoveryWithoutUndo() {
        if (overdueRecoveryUndoWasDismissOnly) {
            OverdueRecoveryDismissal.dismissForToday(this)
        }
        clearOverdueRecoveryUndoState()
    }

    private fun dismissOverdueRecoveryUndoSnackbar(finalizeIfNeeded: Boolean) {
        overdueRecoverySnackbarDismissShouldFinalize = finalizeIfNeeded
        overdueRecoveryUndoSnackbar?.dismiss()
        overdueRecoveryUndoSnackbar = null
        overdueRecoverySnackbarDismissShouldFinalize = true
    }

    private fun clearOverdueRecoveryUndoState() {
        overdueRecoveryUndoSnapshot = null
        overdueRecoveryUndoWasDismissOnly = false
        overdueRecoveryKeepAsIsPending = false
        overdueRecoveryUndoMessageRes = null
    }

    private fun hasPendingOverdueRecoveryUndo(): Boolean =
        overdueRecoveryKeepAsIsPending || overdueRecoveryUndoSnapshot != null

    private fun maybeReshowOverdueRecoveryUndoSnackbar() {
        val messageRes = overdueRecoveryUndoMessageRes ?: return
        if (!hasPendingOverdueRecoveryUndo()) return
        if (overdueRecoveryUndoSnackbar?.isShown == true) return
        showOverdueRecoveryUndoSnackbar(messageRes, trackPending = false)
    }

    private fun showOverdueRecoveryUndoSnackbar(messageRes: Int, trackPending: Boolean = true) {
        if (trackPending) {
            overdueRecoveryUndoMessageRes = messageRes
        }
        dismissOverdueRecoveryUndoSnackbar(finalizeIfNeeded = false)
        val snackbar = Snackbar.make(homeRoot, getString(messageRes), Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.undo) { undoOverdueRecoveryAction() }
            .setAnchorView(findViewById(R.id.bottomNavBar))
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    if (event == DISMISS_EVENT_ACTION) return
                    if (!overdueRecoverySnackbarDismissShouldFinalize) return
                    finalizeOverdueRecoveryWithoutUndo()
                }
            })
        overdueRecoveryUndoSnackbar = snackbar
        snackbar.show()
    }

    private fun undoOverdueRecoveryAction() {
        if (overdueRecoveryUndoWasDismissOnly) {
            dismissOverdueRecoveryUndoSnackbar(finalizeIfNeeded = false)
            clearOverdueRecoveryUndoState()
            bindHomeTodayPlan(homeTasksSnapshot)
            return
        }
        val originalDates = overdueRecoveryUndoSnapshot ?: return
        if (originalDates.isEmpty()) return
        val token = SessionManager.getAccessToken(this) ?: return
        val updates = buildOverdueRecoveryRestoreUpdates(originalDates)
        if (updates.isEmpty()) return

        dismissOverdueRecoveryUndoSnackbar(finalizeIfNeeded = false)

        val opGen = ++overdueRecoveryOperationGeneration
        homeRoadmapPatchInFlight = true
        homeTasksSnapshot = OverdueStepRecovery.applyUpdatesToSnapshot(homeTasksSnapshot, updates)
        clearOverdueRecoveryUndoState()
        bindHomeTodayPlan(homeTasksSnapshot)

        networkExecutor.execute {
            var failureMessage: String? = null
            for (update in updates) {
                when (
                    val result = SupabaseTasksApi.updateTaskRoadmap(token, update.taskId, update.roadmap)
                ) {
                    is SupabaseTasksApi.PatchRoadmapResult.Success -> Unit
                    is SupabaseTasksApi.PatchRoadmapResult.Failure -> {
                        failureMessage = result.message
                        break
                    }
                }
            }
            runOnUiThread {
                homeRoadmapPatchInFlight = false
                if (isFinishing || opGen != overdueRecoveryOperationGeneration) return@runOnUiThread
                if (failureMessage != null) {
                    Toast.makeText(
                        this,
                        getString(R.string.home_overdue_recovery_failed, failureMessage),
                        Toast.LENGTH_LONG,
                    ).show()
                    loadHomeUpcoming(showLoading = false)
                } else {
                    loadHomeUpcoming(showLoading = false)
                    if (currentTab == Tab.Tasks) {
                        loadTasks(showLoading = false)
                    }
                }
            }
        }
    }

    private fun applyOverdueRecoveryReschedule(mode: OverdueStepRecovery.RescheduleMode) {
        if (homeRoadmapPatchInFlight) return
        val token = SessionManager.getAccessToken(this) ?: return
        val today = LocalDate.now()
        val updates = OverdueStepRecovery.buildUpdates(homeTasksSnapshot, today, mode)
        if (updates.isEmpty()) return

        val undoSnapshot = captureOverdueRecoveryOriginalDates()
        val opGen = ++overdueRecoveryOperationGeneration
        overdueRecoveryUndoSnapshot = undoSnapshot
        overdueRecoveryUndoWasDismissOnly = false

        homeRoadmapPatchInFlight = true
        homeOverdueRecoveryAddToToday.isEnabled = false
        homeOverdueRecoverySpreadOut.isEnabled = false
        homeOverdueRecoveryKeepAsIs.isEnabled = false

        homeTasksSnapshot = OverdueStepRecovery.applyUpdatesToSnapshot(homeTasksSnapshot, updates)
        bindHomeTodayPlan(homeTasksSnapshot)

        val confirmationRes = when (mode) {
            OverdueStepRecovery.RescheduleMode.ADD_TO_TODAY -> R.string.home_overdue_recovery_added_to_today
            OverdueStepRecovery.RescheduleMode.SPREAD_OUT -> R.string.home_overdue_recovery_spread_out_done
        }
        showOverdueRecoveryUndoSnackbar(confirmationRes)

        networkExecutor.execute {
            var failureMessage: String? = null
            for (update in updates) {
                when (
                    val result = SupabaseTasksApi.updateTaskRoadmap(token, update.taskId, update.roadmap)
                ) {
                    is SupabaseTasksApi.PatchRoadmapResult.Success -> Unit
                    is SupabaseTasksApi.PatchRoadmapResult.Failure -> {
                        failureMessage = result.message
                        break
                    }
                }
            }
            runOnUiThread {
                homeRoadmapPatchInFlight = false
                if (isFinishing || opGen != overdueRecoveryOperationGeneration) return@runOnUiThread
                if (failureMessage != null) {
                    dismissOverdueRecoveryUndoSnackbar(finalizeIfNeeded = true)
                    Toast.makeText(
                        this,
                        getString(R.string.home_overdue_recovery_failed, failureMessage),
                        Toast.LENGTH_LONG,
                    ).show()
                    loadHomeUpcoming(showLoading = false)
                } else {
                    loadHomeUpcoming(showLoading = false)
                    if (currentTab == Tab.Tasks) {
                        loadTasks(showLoading = false)
                    }
                }
            }
        }
    }

    private fun bindTodayPlanTimeLimitAndAdjustment(
        workEntries: List<TodayPlanEntry>,
        today: LocalDate,
    ) {
        val incompleteComplex = TodayPlanAdjustment.incompleteComplexEntries(workEntries)
        if (incompleteComplex.isEmpty()) {
            homeTodayPlanTimeLimitBlock.visibility = View.GONE
            homeTodayPlanAdjustmentCard.visibility = View.GONE
            todayPlanAdjustmentCachedPlan = null
            return
        }

        homeTodayPlanTimeLimitBlock.visibility = View.VISIBLE
        val selectedMinutes = TodayPlanTimeLimitStore.getAvailableMinutes(this, today)
        updateTodayPlanTimeLimitChipSelection(selectedMinutes)

        if (selectedMinutes == null) {
            homeTodayPlanTimeLimitStatus.visibility = View.GONE
            homeTodayPlanAdjustmentCard.visibility = View.GONE
            todayPlanAdjustmentCachedPlan = null
            return
        }

        val availableHours = TodayPlanTimeLimitStore.minutesToHours(selectedMinutes)
        val plan = TodayPlanAdjustment.computePlan(workEntries, availableHours, today)
        todayPlanAdjustmentCachedPlan = plan

        if (plan.fitsAvailableTime) {
            homeTodayPlanTimeLimitStatus.visibility = View.VISIBLE
            homeTodayPlanTimeLimitStatus.text = getString(R.string.home_today_plan_time_limit_fits)
            homeTodayPlanAdjustmentCard.visibility = View.GONE
            return
        }

        homeTodayPlanTimeLimitStatus.visibility = View.GONE
        if (todayPlanAdjustmentDismissed) {
            homeTodayPlanAdjustmentCard.visibility = View.GONE
            return
        }

        homeTodayPlanAdjustmentCard.visibility = View.VISIBLE
        homeTodayPlanAdjustmentSummary.text = getString(
            R.string.home_today_plan_adjustment_over_budget,
            formatPlanHoursLabel(plan.plannedHours),
            formatPlanHoursLabel(plan.availableHours),
        )
        bindAdjustmentStepList(homeTodayPlanAdjustmentFocusList, plan.recommendedFocus)
        bindAdjustmentStepList(homeTodayPlanAdjustmentMoveList, plan.moveLater)
        if (plan.blockedOnToday.isNotEmpty()) {
            homeTodayPlanAdjustmentBlockedNote.visibility = View.VISIBLE
            val names = plan.blockedOnToday.joinToString { entry ->
                entry.step?.title ?: entry.task.title
            }
            homeTodayPlanAdjustmentBlockedNote.text =
                getString(R.string.home_today_plan_adjustment_blocked, names)
        } else {
            homeTodayPlanAdjustmentBlockedNote.visibility = View.GONE
        }
        val actionsEnabled = !homeRoadmapPatchInFlight
        homeTodayPlanAdjustmentApply.isEnabled = actionsEnabled
        homeTodayPlanAdjustmentKeepOriginal.isEnabled = actionsEnabled
    }

    private fun bindAdjustmentStepList(host: LinearLayout, entries: List<TodayPlanEntry>) {
        host.removeAllViews()
        val density = resources.displayMetrics.density
        val topPad = (2 * density).toInt()
        for (entry in entries) {
            val line = TextView(this)
            line.setPadding(0, topPad, 0, topPad)
            line.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            line.setTextColor(
                MaterialColors.getColor(line, com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
            val title = entry.step?.title ?: entry.task.title
            line.text = getString(
                R.string.home_today_plan_adjustment_step_line,
                title,
                formatAdjustmentStepTime(entry),
            )
            host.addView(line)
        }
    }

    private fun formatAdjustmentStepTime(entry: TodayPlanEntry): String {
        val hours = TodayPlanAdjustment.stepHours(entry)
        return when {
            hours <= 0.0 -> getString(R.string.home_today_plan_step_time_unknown)
            hours < 1.0 -> getString(
                R.string.home_today_plan_step_time_minutes,
                (hours * 60.0).roundToInt().coerceAtLeast(1),
            )
            else -> getString(R.string.home_today_plan_step_time_hours, hours)
        }
    }

    private fun formatPlanHoursLabel(hours: Double): String {
        if (hours <= 0.0) return "0 hrs"
        val rounded = kotlin.math.round(hours * 10.0) / 10.0
        val value = if (kotlin.math.abs(rounded - rounded.toInt()) < 0.05) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", rounded)
        }
        return if (rounded == 1.0) "$value hr" else "$value hrs"
    }

    private fun updateTodayPlanTimeLimitChipSelection(selectedMinutes: Int?) {
        todayPlanAdjustmentSuppressChipCallback = true
        homeTodayPlanTimeLimitChip30m.isChecked = selectedMinutes == 30
        homeTodayPlanTimeLimitChip1h.isChecked = selectedMinutes == 60
        homeTodayPlanTimeLimitChip2h.isChecked = selectedMinutes == 120
        val isPreset = selectedMinutes == 30 || selectedMinutes == 60 || selectedMinutes == 120
        homeTodayPlanTimeLimitChipCustom.isChecked = selectedMinutes != null && !isPreset
        homeTodayPlanTimeLimitChipClear.isChecked = false
        todayPlanAdjustmentSuppressChipCallback = false
    }

    private fun setTodayPlanTimeLimitMinutes(minutes: Int) {
        if (todayPlanAdjustmentSuppressChipCallback) return
        TodayPlanTimeLimitStore.setAvailableMinutes(this, minutes)
        todayPlanAdjustmentDismissed = false
        updateTodayPlanTimeLimitChipSelection(minutes)
        bindHomeTodayPlan(homeTasksSnapshot)
    }

    private fun clearTodayPlanTimeLimit() {
        if (todayPlanAdjustmentSuppressChipCallback) return
        TodayPlanTimeLimitStore.clear(this)
        todayPlanAdjustmentDismissed = false
        todayPlanAdjustmentCachedPlan = null
        dismissTodayPlanAdjustmentUndoSnackbar(finalize = true)
        updateTodayPlanTimeLimitChipSelection(null)
        bindHomeTodayPlan(homeTasksSnapshot)
    }

    private fun showCustomTodayPlanTimeLimitDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_today_plan_custom_time, null)
        val hoursInput = dialogView.findViewById<TextInputEditText>(R.id.customTimeHours)
        val minutesInput = dialogView.findViewById<TextInputEditText>(R.id.customTimeMinutes)
        val existing = TodayPlanTimeLimitStore.getAvailableMinutes(this)
        if (existing != null) {
            hoursInput.setText((existing / 60).toString())
            minutesInput.setText((existing % 60).toString())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_today_plan_time_limit_custom_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_course) { _, _ ->
                val hours = hoursInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
                val minutes = minutesInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
                val totalMinutes = (hours.coerceAtLeast(0) * 60) + minutes.coerceAtLeast(0)
                if (totalMinutes <= 0) {
                    Toast.makeText(this, R.string.home_today_plan_time_limit_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                setTodayPlanTimeLimitMinutes(totalMinutes)
            }
            .show()
    }

    private fun dismissTodayPlanAdjustmentCard() {
        todayPlanAdjustmentDismissed = true
        homeTodayPlanAdjustmentCard.visibility = View.GONE
    }

    private fun applyTodayPlanAdjustment() {
        if (homeRoadmapPatchInFlight) return
        val plan = todayPlanAdjustmentCachedPlan ?: return
        if (plan.fitsAvailableTime) return
        val token = SessionManager.getAccessToken(this) ?: return
        val today = LocalDate.now()
        val updates = TodayPlanAdjustment.buildApplyUpdates(plan, today)
        if (updates.isEmpty() && plan.blockedOnToday.isEmpty()) return

        val undoSnapshot = TodayPlanAdjustment.captureOriginalDates(plan.moveLater)
        if (undoSnapshot.isEmpty() && updates.isEmpty()) return

        val opGen = ++todayPlanAdjustmentOperationGeneration
        todayPlanAdjustmentUndoSnapshot = undoSnapshot
        todayPlanAdjustmentDismissed = true

        homeRoadmapPatchInFlight = true
        homeTodayPlanAdjustmentApply.isEnabled = false
        homeTodayPlanAdjustmentKeepOriginal.isEnabled = false
        homeTasksSnapshot = OverdueStepRecovery.applyUpdatesToSnapshot(homeTasksSnapshot, updates)
        bindHomeTodayPlan(homeTasksSnapshot)
        showTodayPlanAdjustmentUndoSnackbar()

        networkExecutor.execute {
            var failureMessage: String? = null
            for (update in updates) {
                when (val result = SupabaseTasksApi.updateTaskRoadmap(token, update.taskId, update.roadmap)) {
                    is SupabaseTasksApi.PatchRoadmapResult.Success -> Unit
                    is SupabaseTasksApi.PatchRoadmapResult.Failure -> {
                        failureMessage = result.message
                        break
                    }
                }
            }
            runOnUiThread {
                homeRoadmapPatchInFlight = false
                if (isFinishing || opGen != todayPlanAdjustmentOperationGeneration) return@runOnUiThread
                homeTodayPlanAdjustmentApply.isEnabled = true
                homeTodayPlanAdjustmentKeepOriginal.isEnabled = true
                if (failureMessage != null) {
                    dismissTodayPlanAdjustmentUndoSnackbar(finalize = true)
                    todayPlanAdjustmentUndoSnapshot = null
                    Toast.makeText(
                        this,
                        getString(R.string.home_today_plan_adjustment_failed, failureMessage),
                        Toast.LENGTH_LONG,
                    ).show()
                    loadHomeUpcoming(showLoading = false)
                } else {
                    loadHomeUpcoming(showLoading = false)
                    if (currentTab == Tab.Tasks) {
                        loadTasks(showLoading = false)
                    }
                }
            }
        }
    }

    private fun showTodayPlanAdjustmentUndoSnackbar() {
        dismissTodayPlanAdjustmentUndoSnackbar(finalize = false)
        val snackbar = Snackbar.make(
            homeRoot,
            getString(R.string.home_today_plan_adjustment_applied),
            Snackbar.LENGTH_LONG,
        )
            .setAction(R.string.undo) { undoTodayPlanAdjustment() }
            .setAnchorView(findViewById(R.id.bottomNavBar))
        todayPlanAdjustmentUndoSnackbar = snackbar
        snackbar.show()
    }

    private fun dismissTodayPlanAdjustmentUndoSnackbar(finalize: Boolean) {
        todayPlanAdjustmentUndoSnackbar?.dismiss()
        todayPlanAdjustmentUndoSnackbar = null
        if (finalize) {
            todayPlanAdjustmentUndoSnapshot = null
        }
    }

    private fun undoTodayPlanAdjustment() {
        val originalDates = todayPlanAdjustmentUndoSnapshot ?: return
        if (originalDates.isEmpty()) return
        val token = SessionManager.getAccessToken(this) ?: return
        val updates = TodayPlanAdjustment.buildRestoreUpdates(originalDates, homeTasksSnapshot)
        if (updates.isEmpty()) return

        dismissTodayPlanAdjustmentUndoSnackbar(finalize = false)
        val opGen = ++todayPlanAdjustmentOperationGeneration
        homeRoadmapPatchInFlight = true
        homeTasksSnapshot = OverdueStepRecovery.applyUpdatesToSnapshot(homeTasksSnapshot, updates)
        todayPlanAdjustmentUndoSnapshot = null
        todayPlanAdjustmentDismissed = false
        bindHomeTodayPlan(homeTasksSnapshot)

        networkExecutor.execute {
            var failureMessage: String? = null
            for (update in updates) {
                when (val result = SupabaseTasksApi.updateTaskRoadmap(token, update.taskId, update.roadmap)) {
                    is SupabaseTasksApi.PatchRoadmapResult.Success -> Unit
                    is SupabaseTasksApi.PatchRoadmapResult.Failure -> {
                        failureMessage = result.message
                        break
                    }
                }
            }
            runOnUiThread {
                homeRoadmapPatchInFlight = false
                if (isFinishing || opGen != todayPlanAdjustmentOperationGeneration) return@runOnUiThread
                if (failureMessage != null) {
                    Toast.makeText(
                        this,
                        getString(R.string.home_today_plan_adjustment_failed, failureMessage),
                        Toast.LENGTH_LONG,
                    ).show()
                    loadHomeUpcoming(showLoading = false)
                } else {
                    loadHomeUpcoming(showLoading = false)
                    if (currentTab == Tab.Tasks) {
                        loadTasks(showLoading = false)
                    }
                }
            }
        }
    }

    private fun bindHomeTodayPlan(allActiveTasks: List<SupabaseTasksApi.TaskRow>) {
        homeTasksSnapshot = allActiveTasks
        homeTodayPlanSection.visibility = View.VISIBLE
        val today = LocalDate.now()
        bindOverdueRecoveryCard(allActiveTasks, today)
        val scopeEntries = todayPlanScopeEntries(allActiveTasks, today)
        val workEntries = todayPlanWorkEntries(allActiveTasks, today)
        val progressEntries = todayPlanProgressEntries(allActiveTasks, today)
        val hasIncomplete = workEntries.any { !it.isCompleted }
        val todayPlanComplete = isTodayWorkPlanComplete(allActiveTasks, today)
        val reviewTodayEntries = todayPlanCompletedReviewEntries(allActiveTasks, today)
        val futureEntries = collectFuturePlanEntries(allActiveTasks, today)

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

        bindTodayPlanTimeLimitAndAdjustment(workEntries, today)

        if (hasIncomplete) {
            homeTodayPlanEmpty.visibility = View.GONE
            homeTodayPlanGroups.visibility = View.VISIBLE
            val sorted = sortTodayPlanEntries(workEntries, today)
            val collapsedVisible = buildCollapsedVisibleEntries(workEntries, scopeEntries, today)
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

        val getAheadVisible = if (!hasIncomplete) {
            bindGetAheadSection(allActiveTasks, today)
        } else {
            clearGetAheadSection()
            false
        }

        val reviewDays = buildCompletedReviewDays(
            reviewTodayEntries,
            futureEntries,
            today,
            getAheadVisible,
            homeGetAheadFocusDate,
            includeTodayReview = todayPlanComplete,
            hasIncompleteToday = hasIncomplete,
        )
        val expandedReviewValidDays = reviewDays.map { it.first }.toMutableSet()
        if (getAheadVisible && homeGetAheadFocusDate != null &&
            futureEntries.any { it.recommendedOn == homeGetAheadFocusDate && it.isCompleted }
        ) {
            expandedReviewValidDays.add(homeGetAheadFocusDate!!)
        }
        pruneCompletedReviewExpandedDays(expandedReviewValidDays)
        bindCompletedReviewByDay(
            reviewDays = reviewDays,
            today = today,
            getAheadVisible = getAheadVisible,
            focusDay = homeGetAheadFocusDate,
        )
        if (getAheadVisible && homeGetAheadFocusDate != null) {
            val focusEntries = futureEntries.filter { it.recommendedOn == homeGetAheadFocusDate }
            val visibleInMain = buildGetAheadCollapsedVisible(focusEntries)
            val visibleKeys = visibleInMain.map { todayPlanEntryKey(it) }.toSet()
            bindGetAheadFocusDayCompletedReview(homeGetAheadFocusDate!!, focusEntries, visibleKeys)
        } else {
            homeGetAheadCompletedReviewHost.removeAllViews()
            homeGetAheadCompletedReviewHost.visibility = View.GONE
        }

        val hasAnythingVisible = hasIncomplete || reviewDays.isNotEmpty() || getAheadVisible
        bindTodayPlanIdleMessage(hasIncomplete, workEntries, reviewDays, today)
        if (!hasAnythingVisible) {
            homeTodayPlanCompletedTodayHost.visibility = View.GONE
            homeTodayPlanCompletedTodayHost.removeAllViews()
            homeTodayPlanFutureReviewBeforeFocusHost.visibility = View.GONE
            homeTodayPlanFutureReviewBeforeFocusHost.removeAllViews()
            homeTodayPlanCompletedByDayHost.visibility = View.GONE
            homeTodayPlanCompletedByDayHost.removeAllViews()
        }
        maybeReshowOverdueRecoveryUndoSnackbar()
        bindWeekAheadSection(allActiveTasks.filter { it.status != TaskStatus.COMPLETE })
    }

    private fun bindWeekAheadSection(activeTasks: List<SupabaseTasksApi.TaskRow>) {
        homeWeekAheadSection.visibility = View.VISIBLE
        val today = LocalDate.now()
        val summary = WeekAheadWork.computeSummary(activeTasks, today)
        val tasksDuePhrase = resources.getQuantityString(
            R.plurals.home_week_ahead_tasks_due,
            summary.tasksDueCount,
            summary.tasksDueCount,
        )
        homeWeekAheadSummaryLine.text = getString(
            R.string.home_week_ahead_summary,
            tasksDuePhrase,
            WeekAheadWork.formatPlannedHours(summary.totalPlannedHours),
        )
        homeWeekAheadBusiestLine.text = if (summary.busiestDayHours > 0.0 && summary.busiestDay != null) {
            val dayName = summary.busiestDay.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL,
                Locale.getDefault(),
            )
            getString(
                R.string.home_week_ahead_busiest_day,
                dayName,
                WeekAheadWork.formatPlannedHours(summary.busiestDayHours),
            )
        } else {
            getString(R.string.home_week_ahead_no_roadmap_hours)
        }
        val nextTask = summary.nextDueTask
        homeWeekAheadNextLine.text = if (nextTask?.dueDate != null) {
            getString(
                R.string.home_week_ahead_next_up,
                nextTask.title,
                WeekAheadWork.dueDayLabel(nextTask.dueDate, today),
            )
        } else {
            getString(R.string.home_week_ahead_no_tasks_due)
        }
        if (homeWeekAheadExpanded) {
            homeWeekAheadExpandedHost.visibility = View.VISIBLE
            bindWeekAheadExpandedDays(summary.dayBreakdowns, today)
        } else {
            homeWeekAheadExpandedHost.visibility = View.GONE
            homeWeekAheadExpandedHost.removeAllViews()
        }
    }

    private fun bindWeekAheadExpandedDays(
        breakdowns: List<WeekAheadDayBreakdown>,
        today: LocalDate,
    ) {
        homeWeekAheadExpandedHost.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (day in breakdowns) {
            val block = inflater.inflate(R.layout.item_home_week_ahead_day, homeWeekAheadExpandedHost, false)
            val title = block.findViewById<TextView>(R.id.homeWeekAheadDayTitle)
            title.text = if (day.date == today) {
                getString(R.string.home_week_ahead_day_today)
            } else {
                day.date.dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.FULL,
                    Locale.getDefault(),
                )
            }
            val hoursLine = block.findViewById<TextView>(R.id.homeWeekAheadDayHours)
            hoursLine.text = if (day.plannedHours > 0.0) {
                getString(
                    R.string.home_week_ahead_day_hours,
                    WeekAheadWork.formatPlannedHours(day.plannedHours),
                )
            } else {
                getString(R.string.home_week_ahead_day_no_hours)
            }
            val tasksHost = block.findViewById<LinearLayout>(R.id.homeWeekAheadDayTasksHost)
            if (day.tasksDue.isEmpty()) {
                val line = inflater.inflate(
                    R.layout.item_home_week_ahead_task_line,
                    tasksHost,
                    false,
                ) as TextView
                line.text = getString(R.string.home_week_ahead_day_no_tasks)
                tasksHost.addView(line)
            } else {
                for (task in day.tasksDue) {
                    val line = inflater.inflate(
                        R.layout.item_home_week_ahead_task_line,
                        tasksHost,
                        false,
                    ) as TextView
                    line.text = getString(R.string.home_week_ahead_day_task_due, task.title)
                    tasksHost.addView(line)
                }
            }
            homeWeekAheadExpandedHost.addView(block)
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
        val beforeSnapshot = homeTasksSnapshot
        val beforeTodayHalf = todayHalfwayRatio(beforeSnapshot, today)
        val workPlanCompleteBefore = isTodayWorkPlanComplete(beforeSnapshot, today)
        val hadOverdueBefore = TodayPlanWork.hasIncompleteOverdueSteps(beforeSnapshot, today)

        if (checked) {
            if (isFutureDue) {
                homeGetAheadPinnedCheckedKeys.add(pinKey)
            } else {
                homeTodayPlanPinnedCheckedKeys.add(pinKey)
            }
        } else {
            homeTodayPlanPinnedCheckedKeys.remove(pinKey)
            homeGetAheadPinnedCheckedKeys.remove(pinKey)
        }

        homeRoadmapPatchInFlight = true
        val completedAt = if (checked) Instant.now().toString() else null
        homeTasksSnapshot = homeTasksSnapshot.map { row ->
            if (row.id == taskId) row.copy(status = targetStatus, completedAt = completedAt) else row
        }
        bindHomeTodayPlan(homeTasksSnapshot)
        if (checked) {
            updateHomeUpcomingPreview(homeTasksSnapshot, newlyCompletedTaskId = taskId)
        } else {
            updateHomeUpcomingPreview(homeTasksSnapshot, newlyUncompletedTaskId = taskId)
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
            AchievementManager.showPlanComplete(this, today)
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
                        if (row.id == taskId) {
                            row.copy(status = result.status, completedAt = result.completedAt)
                        } else {
                            row
                        }
                    }
                    bindHomeTodayPlan(homeTasksSnapshot)
                    if (checked) {
                        updateHomeUpcomingPreview(homeTasksSnapshot, newlyCompletedTaskId = taskId)
                    } else {
                        updateHomeUpcomingPreview(homeTasksSnapshot, newlyUncompletedTaskId = taskId)
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
                    homeGetAheadPinnedCheckedKeys.removeAll { key ->
                        collectFuturePlanEntries(homeTasksSnapshot, today)
                            .find { todayPlanEntryKey(it) == key }
                            ?.recommendedOn == rec
                    }
                }
                homeTodayPlanPinnedCheckedKeys.remove(pinKey)
            }
        }
        homeRoadmapPatchInFlight = true
        estimateFeedbackAnsweredKeys.remove(estimateFeedbackKey(taskId, stepIndex))
        steps[stepIndex] = steps[stepIndex].copy(
            completed = checked,
            completedAt = if (checked) Instant.now().toString() else null,
        )
        val previousStatus = task.status
        val derived = deriveStatusFromSteps(steps)
        val newRoadmap = RoadmapStep.toJsonArray(steps)
        val taskCompletedAt = when {
            derived == TaskStatus.COMPLETE && previousStatus != TaskStatus.COMPLETE ->
                Instant.now().toString()
            derived != TaskStatus.COMPLETE -> null
            else -> task.completedAt
        }
        homeTasksSnapshot = homeTasksSnapshot.map { row ->
            if (row.id == taskId) {
                row.copy(roadmap = newRoadmap, status = derived, completedAt = taskCompletedAt)
            } else {
                row
            }
        }
        bindHomeTodayPlan(homeTasksSnapshot)
        val taskJustCompleted = derived == TaskStatus.COMPLETE && previousStatus != TaskStatus.COMPLETE
        val taskJustUncompleted = derived != TaskStatus.COMPLETE && previousStatus == TaskStatus.COMPLETE
        when {
            taskJustCompleted ->
                updateHomeUpcomingPreview(homeTasksSnapshot, newlyCompletedTaskId = taskId)
            taskJustUncompleted ->
                updateHomeUpcomingPreview(homeTasksSnapshot, newlyUncompletedTaskId = taskId)
            else -> refreshHomeUpcomingPreview(homeTasksSnapshot)
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

        // Achievement: clearing overdue backlog — celebrate catch-up.
        var planCompleteFired = false
        val overdueClearedNow = checked && hadOverdueBefore &&
            !TodayPlanWork.hasIncompleteOverdueSteps(homeTasksSnapshot, today)
        if (overdueClearedNow) {
            planCompleteFired = true
            AchievementManager.showAllCaughtUp(this)
        }

        // Achievement: "Plan complete" when today's scheduled work is finished (not overdue catch-up).
        val workPlanCompleteAfter = isTodayWorkPlanComplete(homeTasksSnapshot, today)
        if (!planCompleteFired && !workPlanCompleteBefore && workPlanCompleteAfter) {
            planCompleteFired = true
            AchievementManager.showPlanComplete(this, today)
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

    private fun onRoadmapEstimateFeedbackSelected(taskId: String, stepIndex: Int, feedback: String) {
        val normalized = EstimateFeedback.normalize(feedback) ?: return
        val key = estimateFeedbackKey(taskId, stepIndex)
        if (key in estimateFeedbackAnsweredKeys) return

        val token = SessionManager.getAccessToken(this) ?: return
        val userId = achievementsUserId ?: SupabaseUserId.resolveUserId(token) ?: return
        val task = homeTasksSnapshot.find { it.id == taskId } ?: return
        val steps = RoadmapStep.parseList(task.roadmap)
        if (stepIndex !in steps.indices) return
        val step = steps[stepIndex]
        if (!step.completed) return

        estimateFeedbackAnsweredKeys.add(key)
        bindHomeTodayPlan(homeTasksSnapshot)

        networkExecutor.execute {
            when (
                val result = SupabaseRoadmapStepEstimateFeedbackApi.upsert(
                    accessToken = token,
                    userId = userId,
                    task = task,
                    stepIndex = stepIndex,
                    step = step,
                    feedback = normalized,
                )
            ) {
                is SupabaseRoadmapStepEstimateFeedbackApi.UpsertResult.Failure -> runOnUiThread {
                    estimateFeedbackAnsweredKeys.remove(key)
                    if (isFinishing) return@runOnUiThread
                    bindHomeTodayPlan(homeTasksSnapshot)
                    Toast.makeText(
                        this,
                        getString(R.string.roadmap_estimate_feedback_save_failed) + "\n" + result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is SupabaseRoadmapStepEstimateFeedbackApi.UpsertResult.Success -> Unit
            }
        }
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
                                    if (row.id == taskId) {
                                        row.copy(status = st.status, completedAt = st.completedAt)
                                    } else {
                                        row
                                    }
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
     * When [newlyUncompletedTaskId] is set, adds that task back if eligible and re-ranks to the top 3.
     */
    private fun updateHomeUpcomingPreview(
        allTasks: List<SupabaseTasksApi.TaskRow>,
        newlyCompletedTaskId: String? = null,
        newlyUncompletedTaskId: String? = null,
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

        if (newlyUncompletedTaskId != null) {
            val task = allTasks.find { it.id == newlyUncompletedTaskId }
            if (task != null && isUpcomingEligible(task) && newlyUncompletedTaskId !in ids) {
                ids = (ids + newlyUncompletedTaskId)
                    .mapNotNull { id -> allTasks.find { it.id == id } }
                    .filter { isUpcomingEligible(it) }
                    .sortedWith(compareBy(nullsLast()) { it.dueDate })
                    .take(3)
                    .map { it.id }
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
        val courseLine = root.findViewById<TextView>(R.id.homeCardCourse)
        val metaLine = root.findViewById<TextView>(R.id.homeCardMeta)
        val nextStepLine = root.findViewById<TextView>(R.id.homeCardNextStep)
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

        val courseLabel = CourseSelectorHelper.labelFor(task.courseId, courseLabelsById)
        if (courseLabel.isNullOrBlank()) {
            courseLine.visibility = View.GONE
        } else {
            courseLine.visibility = View.VISIBLE
            courseLine.text = courseLabel
        }

        val upcomingDetails = buildUpcomingTaskDetails(task)
        metaLine.text = upcomingDetails.meta
        nextStepLine.text = upcomingDetails.nextStep

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

    private data class UpcomingTaskDetails(val meta: String, val nextStep: String)

    private fun buildUpcomingTaskDetails(task: SupabaseTasksApi.TaskRow): UpcomingTaskDetails {
        val steps = RoadmapStep.parseList(task.roadmap)
        return if (steps.isEmpty()) {
            val stepsLeftLabel = resources.getQuantityString(R.plurals.home_upcoming_steps_left, 1, 1)
            val timeLabel = formatUpcomingRemainingTime(TodayPlanWork.SIMPLE_TASK_HOURS)
            UpcomingTaskDetails(
                meta = getString(R.string.home_upcoming_meta, stepsLeftLabel, timeLabel),
                nextStep = getString(
                    R.string.home_upcoming_next_step,
                    getString(R.string.task_detail_mark_complete),
                ),
            )
        } else {
            val remaining = steps.filter { !it.completed }
            val stepsLeft = remaining.size.coerceAtLeast(1)
            val stepsLeftLabel = resources.getQuantityString(
                R.plurals.home_upcoming_steps_left,
                stepsLeft,
                stepsLeft,
            )
            val remainingHours = remaining.sumOf { step ->
                step.estimatedHours?.takeIf { !it.isNaN() && it > 0.0 }
                    ?: TodayPlanWork.SIMPLE_TASK_HOURS
            }
            val nextTitle = remaining.firstOrNull()?.title?.trim().orEmpty()
                .ifBlank { getString(R.string.task_detail_mark_complete) }
            UpcomingTaskDetails(
                meta = getString(
                    R.string.home_upcoming_meta,
                    stepsLeftLabel,
                    formatUpcomingRemainingTime(remainingHours),
                ),
                nextStep = getString(R.string.home_upcoming_next_step, nextTitle),
            )
        }
    }

    private fun formatUpcomingRemainingTime(hours: Double): String {
        return when {
            hours <= 0.0 -> getString(R.string.home_upcoming_time_remaining_unknown)
            hours < 1.0 / 60.0 -> getString(R.string.home_upcoming_time_remaining_minutes, 1)
            hours < 1.0 -> getString(
                R.string.home_upcoming_time_remaining_minutes,
                (hours * 60.0).roundToInt().coerceAtLeast(1),
            )
            else -> getString(R.string.home_upcoming_time_remaining_hours, hours)
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
        val due = TodayPlanWork.simpleTaskDueLocalDate(task)
        val today = LocalDate.now()
        val isFutureDue = due != null && due.isAfter(today)
        if (checked) {
            if (isFutureDue) {
                homeGetAheadPinnedCheckedKeys.add(pinKey)
            } else if (due != null && !due.isAfter(today)) {
                homeTodayPlanPinnedCheckedKeys.add(pinKey)
            }
        } else {
            homeTodayPlanPinnedCheckedKeys.remove(pinKey)
            homeGetAheadPinnedCheckedKeys.remove(pinKey)
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
                    if (checked) {
                        homeTodayPlanPinnedCheckedKeys.remove(pinKey)
                        homeGetAheadPinnedCheckedKeys.remove(pinKey)
                    } else if (isFutureDue) {
                        homeGetAheadPinnedCheckedKeys.add(pinKey)
                    } else if (due != null && !due.isAfter(today)) {
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
            val labels = fetchCourseLabels(token)
            when (val result = SupabaseTasksApi.listTasks(token, TaskListFilter.ACTIVE)) {
                is SupabaseTasksApi.ListResult.Success -> runOnUiThread {
                    courseLabelsById = labels
                    tasksAdapter.setCourseLabels(labels)
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

    private fun fetchCourseLabels(accessToken: String): Map<String, String> {
        val userId = SupabaseUserId.resolveUserId(accessToken) ?: return emptyMap()
        return when (val result = SupabaseCoursesApi.listCourses(accessToken, userId)) {
            is SupabaseCoursesApi.ListResult.Success -> CourseSelectorHelper.labelsById(result.courses)
            is SupabaseCoursesApi.ListResult.Failure -> emptyMap()
        }
    }

    companion object {
        const val EXTRA_SELECTED_TAB = "selected_tab"
        const val TAB_HOME = "home"
        const val TAB_TASKS = "tasks"
        const val TAB_PROFILE = "profile"
    }
}
