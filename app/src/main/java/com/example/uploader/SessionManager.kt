package com.example.uploader

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionManager(context: Context) {

    var hasAskedBatteryOptimization: Boolean
        get() = prefs.getBoolean("asked_battery_opt", false)
        set(value) = prefs.edit { putBoolean("asked_battery_opt", value) }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_session_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var baseUrl: String?
        get() = prefs.getString("base_url", null)
        set(value) = prefs.edit { putString("base_url", value) }

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit { putString("access_token", value) }

    var tokenType: String?
        get() = prefs.getString("token_type", "bearer")
        set(value) = prefs.edit { putString("token_type", value) }

    fun isLoggedIn(): Boolean = !accessToken.isNullOrEmpty()

    fun authHeader(): String {
        val type = tokenType?.replaceFirstChar { it.uppercase() } ?: "Bearer"
        return "$type ${accessToken.orEmpty()}"
    }

    fun clear() {
        prefs.edit { remove("access_token").remove("token_type") }
    }
}
