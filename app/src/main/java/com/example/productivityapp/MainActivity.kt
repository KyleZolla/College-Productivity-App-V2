package com.example.productivityapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private lateinit var passwordInput: EditText
    private lateinit var confirmPasswordInput: EditText
    private lateinit var statusText: TextView
    private var signUpRequestInFlight = false

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
        passwordInput = findViewById(R.id.passwordInput)
        confirmPasswordInput = findViewById(R.id.confirmPasswordInput)
        val firstNameInput = findViewById<EditText>(R.id.firstNameInput)
        val lastNameInput = findViewById<EditText>(R.id.lastNameInput)
        val signUpButton = findViewById<Button>(R.id.signUpButton)
        val googleSignInButton = findViewById<Button>(R.id.googleSignInButton)
        val goToLoginButton = findViewById<Button>(R.id.goToLoginButton)
        statusText = findViewById(R.id.statusText)

        passwordInput.doAfterTextChanged { refreshPasswordMatchUi() }
        confirmPasswordInput.doAfterTextChanged { refreshPasswordMatchUi() }

        signUpButton.setOnClickListener {
            createAccount(
                firstNameInput,
                lastNameInput,
                emailInput,
                passwordInput,
                confirmPasswordInput,
                signUpButton,
                statusText
            )
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

    private fun refreshPasswordMatchUi() {
        if (signUpRequestInFlight) return
        val password = passwordInput.text.toString()
        val confirm = confirmPasswordInput.text.toString()
        when {
            confirm.isEmpty() -> statusText.clearAuthMessage()
            password != confirm ->
                statusText.showAuthMessage(getString(R.string.status_passwords_mismatch), AuthMessageTone.ERROR)
            else ->
                statusText.showAuthMessage(getString(R.string.status_passwords_match), AuthMessageTone.SUCCESS)
        }
    }

    private fun createAccount(
        firstNameInput: EditText,
        lastNameInput: EditText,
        emailInput: EditText,
        passwordInput: EditText,
        confirmPasswordInput: EditText,
        signUpButton: Button,
        statusText: TextView
    ) {
        val firstName = firstNameInput.text.toString().trim()
        val lastName = lastNameInput.text.toString().trim()
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val confirmPassword = confirmPasswordInput.text.toString()

        if (firstName.isBlank() || lastName.isBlank()) {
            statusText.showAuthMessage(getString(R.string.status_name_required), AuthMessageTone.ERROR)
            return
        }

        if (!isValidInput(email, password)) {
            statusText.showAuthMessage(getString(R.string.status_invalid_input), AuthMessageTone.ERROR)
            return
        }

        if (password != confirmPassword) {
            statusText.showAuthMessage(getString(R.string.status_passwords_mismatch), AuthMessageTone.ERROR)
            return
        }

        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            statusText.showAuthMessage(getString(R.string.status_missing_config), AuthMessageTone.ERROR)
            return
        }

        signUpRequestInFlight = true
        statusText.showAuthMessage(getString(R.string.status_working), AuthMessageTone.INSTRUCTION)
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
                    signUpRequestInFlight = false
                    signUpButton.isEnabled = true
                    if (responseCode in 200..299) {
                        val root = JSONObject(responseBody)
                        val envelope = root.optJSONObject("data") ?: root

                        val user = resolveSignupUserObject(envelope)
                        val identities = user?.optJSONArray("identities")
                        if (user != null && identities != null && identities.length() == 0) {
                            statusText.showAuthMessage(
                                getString(R.string.status_signup_existing_account),
                                AuthMessageTone.ERROR
                            )
                            return@runOnUiThread
                        }

                        val (accessToken, refreshToken, expiresIn) = extractSessionFromSignupEnvelope(envelope)
                        if (accessToken.isNotBlank()) {
                            SessionManager.saveSession(this, accessToken, refreshToken, expiresIn)
                            val intent = Intent(this, HomeActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            startActivity(intent)
                            finish()
                            return@runOnUiThread
                        }

                        if (hasExplicitSignupError(root) || hasExplicitSignupError(envelope)) {
                            val err = cleanSignUpError(combinedAuthErrorText(responseBody), responseCode)
                            statusText.showAuthMessage(err, signUpMessageTone(err))
                            return@runOnUiThread
                        }

                        val loginIntent = Intent(this, LoginActivity::class.java).apply {
                            putExtra(LoginActivity.EXTRA_SIGNUP_SUCCESS_PENDING_CONFIRM, true)
                            putExtra(LoginActivity.EXTRA_SIGNUP_EMAIL, email)
                        }
                        startActivity(loginIntent)
                        finish()
                    } else {
                        val err = cleanSignUpError(combinedAuthErrorText(responseBody), responseCode)
                        statusText.showAuthMessage(err, signUpMessageTone(err))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    signUpRequestInFlight = false
                    signUpButton.isEnabled = true
                    statusText.showAuthMessage(getString(R.string.status_network_error), AuthMessageTone.ERROR)
                }
            }
        }
    }

    /**
     * GoTrue may return `{ "data": { "user", "session" } }` (Supabase JS shape) or a flat `{ "user", "session" }`.
     * Duplicate email with confirmations enabled often yields `data.user.identities == []`.
     */
    private fun resolveSignupUserObject(envelope: JSONObject): JSONObject? {
        envelope.optJSONObject("user")?.let { return it }
        if (envelope.has("identities") && envelope.has("email")) {
            return envelope
        }
        return null
    }

    private fun extractSessionFromSignupEnvelope(envelope: JSONObject): Triple<String, String, Long?> {
        val session = envelope.optJSONObject("session")
        if (session != null && !session.optString("access_token").isNullOrBlank()) {
            val exp = if (session.has("expires_in") && !session.isNull("expires_in")) {
                session.optLong("expires_in")
            } else {
                null
            }
            return Triple(
                session.optString("access_token"),
                session.optString("refresh_token"),
                exp
            )
        }
        val expRoot = if (envelope.has("expires_in") && !envelope.isNull("expires_in")) {
            envelope.optLong("expires_in")
        } else {
            null
        }
        return Triple(
            envelope.optString("access_token"),
            envelope.optString("refresh_token"),
            expRoot
        )
    }

    private fun hasExplicitSignupError(payload: JSONObject): Boolean {
        return payload.optString("msg").isNotBlank() ||
            payload.optString("error_description").isNotBlank() ||
            payload.optString("error").isNotBlank() ||
            payload.optString("error_code").isNotBlank()
    }

    private fun combinedAuthErrorText(responseBody: String): String {
        return try {
            val obj = JSONObject(responseBody)
            listOf(
                obj.optString("msg"),
                obj.optString("message"),
                obj.optString("error_description"),
                obj.optString("error"),
                obj.optString("hint"),
                obj.optString("error_code"),
                obj.optString("code")
            ).joinToString(" ")
                .trim()
                .ifBlank { "" }
        } catch (_: Exception) {
            responseBody.trim()
        }
    }

    private fun cleanSignUpError(raw: String, httpStatus: Int = 0): String {
        val msg = raw.lowercase()
        return when {
            msg.contains("already registered") ||
                msg.contains("already been registered") ||
                msg.contains("user already registered") ||
                msg.contains("email already") ||
                msg.contains("already exists") ||
                msg.contains("duplicate") ||
                msg.contains("user_already_exists") ||
                msg.contains("email_exists") -> {
                getString(R.string.status_signup_existing_account)
            }
            isConfirmationEmailSendFailure(msg, httpStatus) -> {
                getString(R.string.status_signup_email_not_deliverable)
            }
            msg.contains("rate limit") || msg.contains("too many requests") -> {
                getString(R.string.status_rate_limited)
            }
            msg.contains("network") || msg.contains("timeout") -> getString(R.string.status_network_error)
            msg.isBlank() || msg == "unknown error" -> getString(R.string.status_generic_error)
            else -> raw
        }
    }

    private fun isConfirmationEmailSendFailure(msg: String, httpStatus: Int): Boolean {
        if (msg.contains("confirmation email") || msg.contains("error sending confirmation")) return true
        if (msg.contains("send confirmation") || msg.contains("sending confirmation")) return true
        if (msg.contains("smtp") && (msg.contains("error") || msg.contains("fail"))) return true
        if (msg.contains("mailer") || msg.contains("email delivery") || msg.contains("mail delivery")) return true
        if (httpStatus == 500 && msg.contains("email")) return true
        if (msg.contains("unexpected failure") && (msg.contains("email") || msg.contains("confirmation"))) return true
        return false
    }

    private fun signUpMessageTone(message: String): AuthMessageTone {
        return if (message == getString(R.string.status_rate_limited)) {
            AuthMessageTone.INSTRUCTION
        } else {
            AuthMessageTone.ERROR
        }
    }

    private fun launchGoogleOAuth(statusText: TextView) {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            statusText.showAuthMessage(getString(R.string.status_missing_config), AuthMessageTone.ERROR)
            return
        }

        val redirectTo = Uri.encode(getString(R.string.oauth_redirect_url))
        val authUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/authorize?provider=google&redirect_to=$redirectTo&flow_type=implicit"
        statusText.showAuthMessage(getString(R.string.status_google_opening), AuthMessageTone.INSTRUCTION)

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
        } catch (_: Exception) {
            statusText.showAuthMessage(getString(R.string.status_google_failed), AuthMessageTone.ERROR)
        }
    }
}