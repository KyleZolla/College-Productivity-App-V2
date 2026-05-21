package com.example.productivityapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object NotificationPermissionHelper {

    private const val PREFS_NAME = "notification_permission_prefs"
    private const val KEY_FIRST_LAUNCH = "first_launch"

    fun shouldRequestOnFirstLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH, true)) return false
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }
}
