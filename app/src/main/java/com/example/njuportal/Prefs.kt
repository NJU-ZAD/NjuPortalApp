package com.example.njuportal

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object Prefs {

    private const val PREFS_NAME = "nju_portal_secure_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    // 统一获取加密 SharedPreferences
    private fun getSecurePrefs(context: Context): EncryptedSharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    fun saveCredentials(context: Context, username: String, password: String) {
        val sp = getSecurePrefs(context)
        sp.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun loadCredentials(context: Context): Pair<String?, String?> {
        val sp = getSecurePrefs(context)
        val u = sp.getString(KEY_USERNAME, null)
        val p = sp.getString(KEY_PASSWORD, null)
        return u to p
    }

    fun clearCredentials(context: Context) {
        val sp = getSecurePrefs(context)
        sp.edit().clear().apply()
    }
}
