package com.example.productivityapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class LoginActivity : AppCompatActivity() {
    private val networkExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SessionManager.isLoggedIn(this)) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val emailInput = findViewById<EditText>(R.id.loginEmailInput)
        val passwordInput = findViewById<EditText>(R.id.loginPasswordInput)
        val logInButton = findViewById<Button>(R.id.logInButton)
        val googleLogInButton = findViewById<Button>(R.id.googleLogInButton)
        val goToSignUpButton = findViewById<Button>(R.id.goToSignUpButton)
        val statusText = findViewById<TextView>(R.id.loginStatusText)

        logInButton.setOnClickListener {
            logIn(emailInput, passwordInput, logInButton, statusText)
        }

        goToSignUpButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        googleLogInButton.setOnClickListener {
            launchGoogleOAuth(statusText)
        }

        applyOAuthStatusFromIntent(intent, statusText)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val statusText = findViewById<TextView>(R.id.loginStatusText)
        applyOAuthStatusFromIntent(intent, statusText)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }

    private fun logIn(
        emailInput: EditText,
        passwordInput: EditText,
        logInButton: Button,
        statusText: TextView
    ) {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (email.isBlank() || password.isBlank()) {
            statusText.text = getString(R.string.status_login_enter_credentials)
            return
        }

        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            statusText.text = getString(R.string.status_missing_config)
            return
        }

        statusText.text = getString(R.string.status_login_working)
        logInButton.isEnabled = false

        networkExecutor.execute {
            try {
                val authUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/token?grant_type=password"
                val connection = URL(authUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true

                val body = JSONObject()
                    .put("email", email)
                    .put("password", password)
                    .toString()
                    .toByteArray()
                connection.outputStream.use { it.write(body) }

                val responseCode = connection.responseCode
                val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                runOnUiThread {
                    logInButton.isEnabled = true
                    if (responseCode in 200..299) {
                        val payload = JSONObject(responseBody)
                        val accessToken = payload.optString("access_token")
                        val refreshToken = payload.optString("refresh_token")
                        val expiresIn = if (payload.has("expires_in")) payload.optLong("expires_in") else null
                        if (accessToken.isNotBlank()) {
                            SessionManager.saveSession(this, accessToken, refreshToken, expiresIn)
                        }
                        val successMessage = getString(R.string.status_login_success)
                        statusText.text = successMessage
                        Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show()
                        val intent = Intent(this, HomeActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                        finish()
                    } else {
                        statusText.text = cleanLoginError(parseErrorMessage(responseBody))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logInButton.isEnabled = true
                    statusText.text = getString(R.string.status_network_error)
                }
            }
        }
    }

    private fun parseErrorMessage(responseBody: String): String {
        return try {
            val obj = JSONObject(responseBody)
            obj.optString("msg")
                .ifBlank { obj.optString("error_description") }
                .ifBlank { obj.optString("error") }
                .ifBlank { "Unknown error" }
        } catch (_: Exception) {
            if (responseBody.isBlank()) "Unknown error" else responseBody
        }
    }

    private fun launchGoogleOAuth(statusText: TextView) {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            statusText.text = getString(R.string.status_missing_config)
            return
        }

        val redirectTo = Uri.encode(getString(R.string.oauth_redirect_url))
        val authUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/authorize?provider=google&redirect_to=$redirectTo&flow_type=implicit"
        statusText.text = getString(R.string.status_google_opening)

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
        } catch (_: Exception) {
            statusText.text = getString(R.string.status_google_failed)
        }
    }

    private fun applyOAuthStatusFromIntent(intent: Intent, statusText: TextView) {
        val message = intent.getStringExtra("oauth_result_message") ?: return
        statusText.text = message
        intent.removeExtra("oauth_result_message")
    }

    private fun cleanLoginError(raw: String): String {
        val msg = raw.lowercase()
        return when {
            msg.contains("invalid login credentials") -> getString(R.string.status_login_invalid)
            msg.contains("email not confirmed") -> getString(R.string.status_login_email_not_confirmed)
            msg.contains("rate limit") || msg.contains("too many requests") -> {
                getString(R.string.status_rate_limited)
            }
            msg.contains("network") || msg.contains("timeout") -> getString(R.string.status_network_error)
            msg == "unknown error" -> getString(R.string.status_generic_error)
            else -> raw
        }
    }
}
