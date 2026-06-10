package com.example.productivityapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Handles the `productivityapp://auth/callback` redirect using the PKCE code flow.
 *
 * Security notes:
 * - Tokens arriving in the URL fragment (legacy implicit flow) are deliberately ignored:
 *   this activity is exported, so accepting raw tokens would let any app inject a session.
 * - The auth code is only exchanged when a verifier from a flow *we* started is pending,
 *   and the exchange fails server-side unless code and verifier match.
 */
class AuthCallbackActivity : AppCompatActivity() {

    private val networkExecutor = Executors.newSingleThreadExecutor()

    private data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Long?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authCode = intent?.data?.getQueryParameter("code")
        if (authCode.isNullOrBlank()) {
            goToLogin(getString(R.string.status_google_cancelled))
            return
        }

        val codeVerifier = PendingOAuth.consume(this)
        if (codeVerifier == null) {
            // Callback without a pending flow started by this app — reject it.
            Log.w(TAG, "OAuth callback received with no pending PKCE verifier; ignoring.")
            goToLogin(getString(R.string.status_google_cancelled))
            return
        }

        networkExecutor.execute {
            val session = exchangeCodeForSession(authCode, codeVerifier)
            if (session == null) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    goToLogin(getString(R.string.status_google_failed_exchange))
                }
                return@execute
            }
            SignupProfilePending.flushBlocking(applicationContext, session.accessToken)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                SessionManager.saveSession(
                    this,
                    session.accessToken,
                    session.refreshToken,
                    session.expiresInSeconds,
                )
                FcmTokenRegistrar.syncIfLoggedIn(this)
                startActivity(
                    Intent(this, HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    },
                )
                finish()
            }
        }
    }

    /**
     * Exchanges the one-time auth code + PKCE verifier for a session via
     * `POST /auth/v1/token?grant_type=pkce`. Returns null on any failure.
     */
    private fun exchangeCodeForSession(authCode: String, codeVerifier: String): Session? {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) return null
        return try {
            val url = URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/token?grant_type=pkce")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true

            val body = JSONObject()
                .put("auth_code", authCode)
                .put("code_verifier", codeVerifier)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                Log.e(TAG, "PKCE code exchange failed: HTTP $responseCode")
                return null
            }

            val payload = JSONObject(responseBody)
            val accessToken = payload.optString("access_token")
            if (accessToken.isBlank()) return null
            Session(
                accessToken = accessToken,
                refreshToken = payload.optString("refresh_token"),
                expiresInSeconds = if (payload.has("expires_in")) payload.optLong("expires_in") else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "PKCE code exchange error", e)
            null
        }
    }

    private fun goToLogin(message: String) {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                putExtra("oauth_result_message", message)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }

    companion object {
        private const val TAG = "AuthCallbackActivity"
    }
}
