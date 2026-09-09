package com.example.uploader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.uploader.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var session: SessionManager
    private val client = OkHttpClient.Builder().build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)

        // Pre-fill last-used base URL if present
        session.baseUrl?.let { binding.editBaseUrl.setText(it) }

        binding.btnLogin.setOnClickListener {
            val baseUrl = binding.editBaseUrl.text.toString().trim().trimEnd('/')
            val username = binding.editUsername.text.toString().trim()
            val password = binding.editPassword.text.toString()

            if (baseUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
                binding.txtLoginError.text = "Please fill in all fields"
                return@setOnClickListener
            }

            performLogin(baseUrl, username, password)
        }
    }

    private fun performLogin(baseUrl: String, username: String, password: String) {
        binding.btnLogin.isEnabled = false
        binding.loginProgress.visibility = android.widget.ProgressBar.VISIBLE
        binding.txtLoginError.text = ""

        lifecycleScope.launch {
            try {
                val (success, message) = withContext(Dispatchers.IO) {
                    doTokenRequest(baseUrl, username, password)
                }

                if (success) {
                    session.baseUrl = baseUrl
                    Toast.makeText(this@LoginActivity, "Logged in", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    binding.txtLoginError.text = message
                }
            } catch (e: Exception) {
                binding.txtLoginError.text = "Error: ${e.message}"
            } finally {
                binding.btnLogin.isEnabled = true
                binding.loginProgress.visibility = android.widget.ProgressBar.GONE
            }
        }
    }

    private fun doTokenRequest(
        baseUrl: String,
        username: String,
        password: String
    ): Pair<Boolean, String> {
        val formBody = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/login")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Pair(false, "Login failed (${response.code}): $bodyStr")
            }

            return try {
                val json = JSONObject(bodyStr)
                val token = json.getString("access_token")
                val tokenType =
                    if (json.has("token_type")) json.getString("token_type") else "bearer"

                session.accessToken = token
                session.tokenType = tokenType
                Pair(true, "")
            } catch (_: Exception) {
                Pair(false, "Unexpected response format from server: $bodyStr")
            }
        }
    }
}
