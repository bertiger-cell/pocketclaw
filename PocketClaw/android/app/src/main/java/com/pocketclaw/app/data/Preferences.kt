package com.pocketclaw.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

object Preferences {
    private const val PREF_NAME = "pocketclaw_prefs"
    private lateinit var prefs: SharedPreferences



    /** Compose-observable theme state — changes trigger recomposition in PocketClawTheme */
    var themeModeState = mutableStateOf("dark")
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        themeModeState.value = prefs.getString("theme_mode", "dark") ?: "dark"
        // Use BuildConfig key as fallback if user hasn't set one in Settings
        if (dashScopeApiKey.isBlank()) {
            val buildConfigKey = try {
                val clazz = Class.forName("com.pocketclaw.app.BuildConfig")
                clazz.getField("DASHSCOPE_API_KEY").get(null) as? String ?: ""
            } catch (_: Exception) { "" }
            if (buildConfigKey.isNotBlank()) {
                dashScopeApiKey = buildConfigKey
            }
        }
        if (llmMode == "local" && dashScopeApiKey.isNotBlank()) {
            val existing = prefs.getString("llm_mode", null)
            if (existing == null) {
                llmMode = "api"
            }
        }
    }

    var llmMode: String
        get() = if (::prefs.isInitialized) prefs.getString("llm_mode", "api") ?: "api" else "api"
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("llm_mode", value).apply() }

    var sttEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("stt_enabled", true) else true
        set(value) { if (::prefs.isInitialized) prefs.edit().putBoolean("stt_enabled", value).apply() }

    var ttsEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("tts_enabled", true) else true
        set(value) { if (::prefs.isInitialized) prefs.edit().putBoolean("tts_enabled", value).apply() }

    var themeMode: String
        get() = themeModeState.value
        set(value) {
            themeModeState.value = value
            if (::prefs.isInitialized) prefs.edit().putString("theme_mode", value).apply()
        }

    var apiKey: String
        get() = if (::prefs.isInitialized) prefs.getString("api_key", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("api_key", value).apply() }

    var dashScopeApiKey: String
        get() = if (::prefs.isInitialized) prefs.getString("dashscope_api_key", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("dashscope_api_key", value).apply() }

    var telegramToken: String
        get() = if (::prefs.isInitialized) prefs.getString("telegram_token", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("telegram_token", value).apply() }

    var discordToken: String
        get() = if (::prefs.isInitialized) prefs.getString("discord_token", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("discord_token", value).apply() }

    var groqApiKey: String
        get() = if (::prefs.isInitialized) prefs.getString("groq_api_key", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("groq_api_key", value).apply() }

    var groqSelectedModel: String
        get() = if (::prefs.isInitialized) prefs.getString("groq_selected_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile" else "llama-3.3-70b-versatile"
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("groq_selected_model", value).apply() }
}
