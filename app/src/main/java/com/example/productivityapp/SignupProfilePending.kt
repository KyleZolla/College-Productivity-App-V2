package com.example.productivityapp

import android.content.Context

/**
 * Holds school / year-in-school entered at sign-up until they are written to [profiles].
 * Needed when email confirmation delays the session, and as a retry buffer when the
 * profile row is created asynchronously after auth signup.
 */
object SignupProfilePending {

    private const val PREFS_NAME = "signup_profile_pending"
    private const val KEY_SCHOOL = "school"
    private const val KEY_YEAR_IN_SCHOOL = "year_in_school"

    private const val MAX_ATTEMPTS = 6
    private const val RETRY_DELAY_MS = 400L

    fun save(context: Context, school: String?, yearInSchool: String?) {
        val schoolValue = school?.trim()?.takeIf { it.isNotEmpty() }
        val yearValue = yearInSchool?.trim()?.takeIf { it.isNotEmpty() }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (schoolValue == null && yearValue == null) {
            prefs.edit().clear().apply()
            return
        }
        prefs.edit()
            .putString(KEY_SCHOOL, schoolValue)
            .putString(KEY_YEAR_IN_SCHOOL, yearValue)
            .apply()
    }

    fun hasPending(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_SCHOOL, null).isNullOrBlank() ||
            !prefs.getString(KEY_YEAR_IN_SCHOOL, null).isNullOrBlank()
    }

    /**
     * Writes pending fields to Supabase when possible. Returns true if there was nothing
     * pending or the upsert succeeded.
     */
    fun flushBlocking(context: Context, accessToken: String): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val school = prefs.getString(KEY_SCHOOL, null)?.trim()?.takeIf { it.isNotEmpty() }
        val yearInSchool = prefs.getString(KEY_YEAR_IN_SCHOOL, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (school == null && yearInSchool == null) return true

        val userId = SupabaseUserId.resolveUserId(accessToken) ?: return false

        repeat(MAX_ATTEMPTS) { attempt ->
            when (SupabaseProfilesApi.upsertAccountFields(accessToken, userId, school, yearInSchool)) {
                is SupabaseProfilesApi.PatchResult.Success -> {
                    clear(appContext)
                    return true
                }
                is SupabaseProfilesApi.PatchResult.Failure -> {
                    if (attempt < MAX_ATTEMPTS - 1) {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                    }
                }
            }
        }
        return false
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
