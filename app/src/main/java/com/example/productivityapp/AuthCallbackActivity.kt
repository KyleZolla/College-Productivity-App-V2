package com.example.productivityapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class AuthCallbackActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        val fragmentParams = AuthUtils.parseFragmentParams(data?.fragment)
        val accessToken = fragmentParams["access_token"]
        val refreshToken = fragmentParams["refresh_token"]
        val authCode = data?.getQueryParameter("code")

        val resultMessage = if (!accessToken.isNullOrBlank() || !authCode.isNullOrBlank()) {
            getString(R.string.status_google_success)
        } else {
            getString(R.string.status_google_cancelled)
        }

        if (!accessToken.isNullOrBlank()) {
            val expiresIn = fragmentParams["expires_in"]?.toLongOrNull()
            val token = accessToken
            val refresh = refreshToken.orEmpty()
            networkExecutor.execute {
                SignupProfilePending.flushBlocking(applicationContext, token)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    SessionManager.saveSession(this, token, refresh, expiresIn)
                    FcmTokenRegistrar.syncIfLoggedIn(this)
                    startActivity(
                        Intent(this, HomeActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        },
                    )
                    finish()
                }
            }
            return
        }

        val destination = if (!authCode.isNullOrBlank()) {
            Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        } else {
            Intent(this, LoginActivity::class.java).apply {
                putExtra("oauth_result_message", resultMessage)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
        startActivity(destination)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }
}
