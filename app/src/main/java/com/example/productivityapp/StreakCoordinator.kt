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
                lastCompletedDateBackup = null,
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

        // Backup the previous lastCompletedDate so we can undo if today's completion is reversed later.
        when (
            val w = SupabaseProfilesApi.upsertTodayStreakWithLastCompletedDateBackup(
                accessToken = accessToken,
                userId = userId,
                newCurrentStreak = newStreak,
                newLastCompletedDate = today,
                lastCompletedDateBackup = profile.lastCompletedDate,
            )
        ) {
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

    /**
     * Opposite of completing today's plan: if the user unchecks something after completion and today
     * flips from complete → incomplete, restore streak fields from the backup and clear it.
     */
    fun undoTodayCompletionIfPossible(
        accessToken: String,
        userId: String,
    ) {
        val today = LocalDate.now()
        val profile = when (val r = SupabaseProfilesApi.get(accessToken, userId)) {
            is SupabaseProfilesApi.GetResult.Success -> r.row
            else -> return
        }

        // Only undo if today's completion is what we currently have recorded.
        if (profile.lastCompletedDate != today) return

        when (
            val w = SupabaseProfilesApi.undoTodayCompletionDecrementStreakAndRestoreLastCompletedDate(
                accessToken,
                userId,
            )
        ) {
            is SupabaseProfilesApi.PatchResult.Success -> Unit
            is SupabaseProfilesApi.PatchResult.Failure ->
                Log.w("StreakCoordinator", "Could not undo today's streak completion: ${w.message}")
        }
    }
}
