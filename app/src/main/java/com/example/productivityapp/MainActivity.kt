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

class MainActivity : AppCompatActivity() {
    private val networkExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val emailInput = findViewById<EditText>(R.id.emailInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val signUpButton = findViewById<Button>(R.id.signUpButton)
        val googleSignInButton = findViewById<Button>(R.id.googleSignInButton)
        val goToLoginButton = findViewById<Button>(R.id.goToLoginButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        signUpButton.setOnClickListener {
            createAccount(emailInput, passwordInput, signUpButton, statusText)
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
        return Patterns.EMAIL_ADDRESS.matcher(email).matches() && password.length >= 6
    }

    private fun createAccount(
        emailInput: EditText,
        passwordInput: EditText,
        signUpButton: Button,
        statusText: TextView
    ) {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

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
        performSignUpRequest(email, password, signUpButton, statusText)
    }

    private fun performSignUpRequest(
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
                        val successMessage = getString(R.string.status_success)
                        statusText.text = successMessage
                        Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show()
                    } else {
                        val errorMessage = parseErrorMessage(responseBody)
                        statusText.text = "Sign-up failed ($responseCode): $errorMessage"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    signUpButton.isEnabled = true
                    statusText.text = "Sign-up failed: ${e.message ?: "Unknown error"}"
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
}