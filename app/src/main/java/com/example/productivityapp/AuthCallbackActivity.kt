package com.example.productivityapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AuthCallbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        val fragmentParams = parseFragmentParams(data?.fragment)
        val accessToken = fragmentParams["access_token"]
        val refreshToken = fragmentParams["refresh_token"]
        val authCode = data?.getQueryParameter("code")

        val resultMessage = if (!accessToken.isNullOrBlank() || !authCode.isNullOrBlank()) {
            getString(R.string.status_google_success)
        } else {
            getString(R.string.status_google_cancelled)
        }

        if (!accessToken.isNullOrBlank()) {
            getSharedPreferences("auth_prefs", MODE_PRIVATE).edit()
                .putString("access_token", accessToken)
                .putString("refresh_token", refreshToken.orEmpty())
                .apply()
        }

        val destination = Intent(this, LoginActivity::class.java).apply {
            putExtra("oauth_result_message", resultMessage)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(destination)
        finish()
    }

    private fun parseFragmentParams(fragment: String?): Map<String, String> {
        if (fragment.isNullOrBlank()) return emptyMap()
        return fragment.split("&")
            .mapNotNull { part ->
                val idx = part.indexOf("=")
                if (idx <= 0) return@mapNotNull null
                val key = Uri.decode(part.substring(0, idx))
                val value = Uri.decode(part.substring(idx + 1))
                key to value
            }
            .toMap()
    }
}
