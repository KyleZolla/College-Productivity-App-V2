package com.example.productivityapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import android.widget.TextView

object AchievementPopup {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(
        activity: Activity,
        emoji: String,
        title: String,
        message: String,
        autoDismissMs: Long = 3_000L,
    ) {
        if (activity.isFinishing) return

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_achievement_popup)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Ensure the overlay covers the full screen. Without this, some devices/themes
            // size the dialog to its content, making the dim overlay look like a thin strip.
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }

        dialog.findViewById<TextView>(R.id.achievementEmoji)?.text = emoji
        dialog.findViewById<TextView>(R.id.achievementTitle)?.text = title
        dialog.findViewById<TextView>(R.id.achievementMessage)?.text = message

        dialog.show()

        mainHandler.postDelayed(
            {
                runCatching {
                    if (dialog.isShowing) dialog.dismiss()
                }
            },
            autoDismissMs,
        )
    }
}

