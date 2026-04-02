package com.example.productivityapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
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

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches() || password.length < 6) {
            statusText.text = getString(R.string.status_invalid_input)
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
                        val successMessage = getString(R.string.status_login_success)
                        statusText.text = successMessage
                        Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show()
                    } else {
                        statusText.text = "Login failed ($responseCode): ${parseErrorMessage(responseBody)}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logInButton.isEnabled = true
                    statusText.text = "Login failed: ${e.message ?: "Unknown error"}"
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
}
