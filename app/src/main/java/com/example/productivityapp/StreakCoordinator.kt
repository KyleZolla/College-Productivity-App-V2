package com.example.productivityapp

import android.util.Log
import java.time.LocalDate

/**
 * Streak rules when the user completes all steps for [completedDay]:
 * - **Future day** (`completedDay` after today): sets `lastCompletedDate` to that plan day only;
 *   does **not** change `currentStreak`.
 * - **Today**: updates streak via `currentStreak` / `lastCompletedDate` (yesterday → increment, etc.).
 */
object StreakCoordinator {

    /**
     * @return streak count to show in the day-complete popup (after any DB update for today).
     */
    fun resolveStreakForPlanComplete(
        accessToken: String,
        userId: String,
        completedDay: LocalDate,
    ): Int {
        val today = LocalDate.now()
        val profile = when (val r = SupabaseProfilesApi.get(accessToken, userId)) {
            is SupabaseProfilesApi.GetResult.Success -> r.row
            is SupabaseProfilesApi.GetResult.NotFound -> SupabaseProfilesApi.ProfileRow(
                id = userId,
                currentStreak = 0,
                lastCompletedDate = null,
            )
            is SupabaseProfilesApi.GetResult.Failure -> {
                Log.w("StreakCoordinator", "Could not load profile for streak; popup streak may be wrong.")
                return 1
            }
        }

        if (completedDay.isAfter(today)) {
            when (
                val w = SupabaseProfilesApi.patchLastCompletedDateForFuturePlanDayOnly(
                    accessToken,
                    userId,
                    completedDay,
                )
            ) {
                is SupabaseProfilesApi.PatchResult.Failure ->
                    Log.w("StreakCoordinator", "Could not save lastCompletedDate for future plan: ${w.message}")
                is SupabaseProfilesApi.PatchResult.Success -> Unit
            }
            return profile.currentStreak.coerceAtLeast(1)
        }

        if (completedDay != today) {
            return profile.currentStreak.coerceAtLeast(1)
        }

        val last = profile.lastCompletedDate
        val yesterday = today.minusDays(1)

        if (last == today) {
            return profile.currentStreak.coerceAtLeast(1)
        }

        val newStreak = when {
            last == null -> 1
            last == yesterday -> profile.currentStreak + 1
            last.isBefore(yesterday) -> 1
            else -> 1
        }

        when (val w = SupabaseProfilesApi.upsertStreak(accessToken, userId, newStreak, today)) {
            is SupabaseProfilesApi.PatchResult.Success -> {
                // Show exactly what PostgREST stored (source of truth after write).
                return when (val again = SupabaseProfilesApi.get(accessToken, userId)) {
                    is SupabaseProfilesApi.GetResult.Success ->
                        again.row.currentStreak.coerceAtLeast(1)
                    else -> newStreak
                }
            }
            is SupabaseProfilesApi.PatchResult.Failure -> {
                Log.w("StreakCoordinator", "Could not save streak to profiles: ${w.message}")
                return newStreak
            }
        }
    }
}
