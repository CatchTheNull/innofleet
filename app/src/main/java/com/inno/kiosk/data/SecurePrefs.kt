package com.inno.kiosk.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePrefs(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "inno_kiosk_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isConfigured(): Boolean =
        !getBaseUrl().isNullOrBlank() && !getAdminPassword().isNullOrBlank()

    fun saveConfig(baseUrl: String, adminPassword: String) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_ADMIN_PASSWORD, adminPassword)
            .apply()
    }

    fun getBaseUrl(): String? =
        prefs.getString(KEY_BASE_URL, null)

    fun getAdminPassword(): String? =
        prefs.getString(KEY_ADMIN_PASSWORD, null)

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ADMIN_PASSWORD = "admin_password"
    }
}