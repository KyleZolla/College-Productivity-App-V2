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

class MainActivity : AppCompatActivity() {
    private val networkExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SessionManager.isLoggedIn(this)) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val emailInput = findViewById<EditText>(R.id.emailInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val firstNameInput = findViewById<EditText>(R.id.firstNameInput)
        val lastNameInput = findViewById<EditText>(R.id.lastNameInput)
        val signUpButton = findViewById<Button>(R.id.signUpButton)
        val googleSignInButton = findViewById<Button>(R.id.googleSignInButton)
        val goToLoginButton = findViewById<Button>(R.id.goToLoginButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        signUpButton.setOnClickListener {
            createAccount(firstNameInput, lastNameInput, emailInput, passwordInput, signUpButton, statusText)
        }

        goToLoginButton.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        googleSignInButton.setOnClickListener {
            launchGoogleOAuth(statusText)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }

    private fun isValidInput(email: String, password: String): Boolean {
        return AuthUtils.isValidEmailAndPassword(email, password)
    }

    private fun createAccount(
        firstNameInput: EditText,
        lastNameInput: EditText,
        emailInput: EditText,
        passwordInput: EditText,
        signUpButton: Button,
        statusText: TextView
    ) {
        val firstName = firstNameInput.text.toString().trim()
        val lastName = lastNameInput.text.toString().trim()
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (firstName.isBlank() || lastName.isBlank()) {
            statusText.text = getString(R.string.status_name_required)
            return
        }

        if (!isValidInput(email, password)) {
            statusText.text = getString(R.string.status_invalid_input)
            return
        }

        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            statusText.text = getString(R.string.status_missing_config)
            return
        }

        statusText.text = getString(R.string.status_working)
        signUpButton.isEnabled = false
        performSignUpRequest(firstName, lastName, email, password, signUpButton, statusText)
    }

    private fun performSignUpRequest(
        firstName: String,
        lastName: String,
        email: String,
        password: String,
        signUpButton: Button,
        statusText: TextView
    ) {
        networkExecutor.execute {
            try {
                val authUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/signup"
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
                    .put(
                        "data",
                        JSONObject()
                            .put("first_name", firstName)
                            .put("last_name", lastName)
                    )
                    .toString()
                    .toByteArray()

                connection.outputStream.use { it.write(body) }

                val responseCode = connection.responseCode
                val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                runOnUiThread {
                    signUpButton.isEnabled = true
                    if (responseCode in 200..299) {
                        val payload = JSONObject(responseBody)
                        val responseError = parseErrorMessage(responseBody)
                        if (responseError != "Unknown error") {
                            statusText.text = cleanSignUpError(responseError)
                            return@runOnUiThread
                        }

                        val accessToken = payload.optString("access_token")
                        val refreshToken = payload.optString("refresh_token")
                        val expiresIn = if (payload.has("expires_in")) payload.optLong("expires_in") else null

                        if (accessToken.isNotBlank()) {
                            SessionManager.saveSession(this, accessToken, refreshToken, expiresIn)
                            val intent = Intent(this, HomeActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            startActivity(intent)
                            finish()
                            return@runOnUiThread
                        }

                        val message = getString(R.string.status_signup_check_email)
                        statusText.text = message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    } else {
                        statusText.text = cleanSignUpError(parseErrorMessage(responseBody))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    signUpButton.isEnabled = true
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

    private fun cleanSignUpError(raw: String): String {
        val msg = raw.lowercase()
        return when {
            msg.contains("already registered") || msg.contains("already been registered") -> {
                getString(R.string.status_signup_existing_account)
            }
            msg.contains("rate limit") || msg.contains("too many requests") -> {
                getString(R.string.status_rate_limited)
            }
            msg.contains("network") || msg.contains("timeout") -> getString(R.string.status_network_error)
            msg == "unknown error" -> getString(R.string.status_generic_error)
            else -> raw
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
}