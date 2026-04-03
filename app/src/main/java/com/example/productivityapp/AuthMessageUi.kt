package com.example.productivityapp

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

enum class AuthMessageTone {
    /** Body / hints — theme onSurfaceVariant */
    INSTRUCTION,
    SUCCESS,
    ERROR
}

fun TextView.showAuthMessage(message: CharSequence, tone: AuthMessageTone) {
    val visible = message.isNotBlank()
    visibility = if (visible) View.VISIBLE else View.GONE
    text = message
    val color = when (tone) {
        AuthMessageTone.ERROR -> MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorError,
            ContextCompat.getColor(context, R.color.auth_message_error_fallback)
        )
        AuthMessageTone.SUCCESS -> ContextCompat.getColor(context, R.color.auth_message_success)
        AuthMessageTone.INSTRUCTION -> MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            ContextCompat.getColor(context, R.color.auth_message_neutral_fallback)
        )
    }
    setTextColor(color)
}

fun TextView.clearAuthMessage() {
    showAuthMessage("", AuthMessageTone.INSTRUCTION)
}
