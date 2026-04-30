package com.example.productivityapp

import android.app.Activity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object AchievementManager {

    private data class Cache(
        var loaded: Boolean = false,
        var firstTaskCompleted: Boolean = false,
        var gettingAhead: Boolean = false,
        var halfwayThroughCurrentTasks: Boolean = false,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val cacheByUserId = ConcurrentHashMap<String, Cache>()

    fun resolveUserIdFromToken(accessToken: String): String? = SupabaseUserId.resolveUserId(accessToken)

    fun ensureLoaded(accessToken: String, userId: String) {
        val id = userId.trim()
        if (id.isEmpty()) return
        val cache = cacheByUserId.getOrPut(id) { Cache() }
        if (cache.loaded) return
        executor.execute {
            when (val res = SupabaseAchievementsApi.get(accessToken, id)) {
                is SupabaseAchievementsApi.GetResult.Success -> {
                    val row = res.row
                    val c = cacheByUserId.getOrPut(id) { Cache() }
                    c.firstTaskCompleted = row.firstTaskCompleted
                    c.gettingAhead = row.gettingAhead
                    c.halfwayThroughCurrentTasks = row.halfwayThroughCurrentTasks
                    c.loaded = true
                }
                is SupabaseAchievementsApi.GetResult.NotFound -> {
                    // Keep defaults, just mark as loaded so we don't refetch constantly.
                    val c = cacheByUserId.getOrPut(id) { Cache() }
                    c.loaded = true
                }
                is SupabaseAchievementsApi.GetResult.Failure -> {
                    // Don't mark loaded so we can retry later.
                }
            }
        }
    }

    fun maybeShowFirstTaskCompleted(activity: Activity, accessToken: String, userId: String) {
        val id = userId.trim()
        if (id.isEmpty()) return
        val cache = cacheByUserId.getOrPut(id) { Cache() }
        if (cache.firstTaskCompleted) return
        cache.firstTaskCompleted = true
        AchievementPopup.show(
            activity = activity,
            emoji = "🎉",
            title = "First Task Done!",
            message = "You completed your first task. The journey begins! 🎉",
        )
        executor.execute {
            SupabaseAchievementsApi.upsert(accessToken, id, firstTaskCompleted = true)
        }
    }

    fun maybeShowGettingAhead(activity: Activity, accessToken: String, userId: String) {
        val id = userId.trim()
        if (id.isEmpty()) return
        val cache = cacheByUserId.getOrPut(id) { Cache() }
        if (cache.gettingAhead) return
        cache.gettingAhead = true
        AchievementPopup.show(
            activity = activity,
            emoji = "💪",
            title = "Getting Ahead!",
            message = "You're working ahead of schedule. Keep it up 💪",
        )
        executor.execute {
            SupabaseAchievementsApi.upsert(accessToken, id, gettingAhead = true)
        }
    }

    fun maybeShowHalfwayThroughCurrentTasks(activity: Activity, accessToken: String, userId: String) {
        val id = userId.trim()
        if (id.isEmpty()) return
        val cache = cacheByUserId.getOrPut(id) { Cache() }
        if (cache.halfwayThroughCurrentTasks) return
        cache.halfwayThroughCurrentTasks = true
        AchievementPopup.show(
            activity = activity,
            emoji = "🎯",
            title = "Halfway There!",
            message = "You're halfway through your current tasks. Don't stop now 🎯",
        )
        executor.execute {
            SupabaseAchievementsApi.upsert(accessToken, id, halfwayThroughCurrentTasks = true)
        }
    }

    fun showPlanComplete(activity: Activity, day: LocalDate) {
        val today = LocalDate.now()
        val title = when {
            day == today -> "Today's Plan Crushed!"
            day == today.plusDays(1) -> "Tomorrow's Plan Crushed!"
            else -> {
                val dow = day.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
                "${dow}'s Plan Crushed!"
            }
        }
        AchievementPopup.show(
            activity = activity,
            emoji = "🔥",
            title = title,
            message = "You finished everything on your list for that day. 🔥",
        )
    }
}

