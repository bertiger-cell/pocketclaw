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

        // Legacy migration: Groq → OpenRouter
        if (groqApiKey.isNotBlank() && openRouterApiKey.isBlank()) {
            openRouterApiKey = groqApiKey
        }
        if (llmMode == "groq") {
            llmMode = "openrouter"
        }

        // Use BuildConfig key as fallback if user hasn't set one in Settings
        if (openRouterApiKey.isBlank()) {
            val buildConfigKey = try {
                val clazz = Class.forName("com.pocketclaw.app.BuildConfig")
                clazz.getField("OPENROUTER_API_KEY").get(null) as? String ?: ""
            } catch (_: Exception) { "" }
            if (buildConfigKey.isNotBlank()) {
                openRouterApiKey = buildConfigKey
            }
        }
        if (llmMode == "local" && openRouterApiKey.isNotBlank()) {
            val existing = prefs.getString("llm_mode", null)
            if (existing == null) {
                llmMode = "openrouter"
            }
        }
    }

    // -- LLM Mode ------------------------------------------------

    var llmMode: String
        get() = if (::prefs.isInitialized) {
            val mode = prefs.getString("llm_mode", "openrouter") ?: "openrouter"
            // Normalize legacy mode names
            when (mode) {
                "groq" -> "openrouter"
                "api" -> "openrouter"
                else -> mode
            }
        } else "openrouter"
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("llm_mode", value).apply() }

    // -- OpenRouter ------------------------------------------------

    var openRouterApiKey: String
        get() = if (::prefs.isInitialized) prefs.getString("openrouter_api_key", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("openrouter_api_key", value).apply() }

    var openRouterSelectedModel: String
        get() = if (::prefs.isInitialized) prefs.getString("openrouter_selected_model", "meta-llama/llama-3.3-70b-instruct:free") ?: "meta-llama/llama-3.3-70b-instruct:free" else "meta-llama/llama-3.3-70b-instruct:free"
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("openrouter_selected_model", value).apply() }

    // -- Ollama ------------------------------------------------

    var ollamaHost: String
        get() = if (::prefs.isInitialized) prefs.getString("ollama_host", "http://localhost:11434") ?: "http://localhost:11434" else "http://localhost:11434"
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("ollama_host", value).apply() }

    var ollamaSelectedModel: String
        get() = if (::prefs.isInitialized) prefs.getString("ollama_selected_model", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("ollama_selected_model", value).apply() }

    // -- OpenCode Zen ------------------------------------------------

    var openCodeZenApiKey: String
        get() = if (::prefs.isInitialized) prefs.getString("opencodezen_api_key", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("opencodezen_api_key", value).apply() }

    var openCodeZenEndpoint: String
        get() = if (::prefs.isInitialized) prefs.getString("opencodezen_endpoint", "https://zen.opencode.example/v1") ?: "https://zen.opencode.example/v1" else "https://zen.opencode.example/v1"
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("opencodezen_endpoint", value).apply() }

    var openCodeZenSelectedModel: String
        get() = if (::prefs.isInitialized) prefs.getString("opencodezen_selected_model", "zen-coder-v1") ?: "zen-coder-v1" else "zen-coder-v1"
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("opencodezen_selected_model", value).apply() }

    // -- Agent Personalization ------------------------------------------------

    var userPreferredName: String
        get() = if (::prefs.isInitialized) prefs.getString("user_preferred_name", "User") ?: "User" else "User"
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("user_preferred_name", value).apply() }

    var userDataContext: String
        get() = if (::prefs.isInitialized) prefs.getString("user_data_context", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("user_data_context", value).apply() }

    var agentResponseStyle: String
        get() = if (::prefs.isInitialized) prefs.getString("agent_response_style", "Standard") ?: "Standard" else "Standard"
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("agent_response_style", value).apply() }

    var agentCustomBehaviorInstructions: String
        get() = if (::prefs.isInitialized) prefs.getString("agent_custom_instructions", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("agent_custom_instructions", value).apply() }

    // -- Theme ------------------------------------------------

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

    // -- Legacy Keys (kept for backward compatibility, will be deprecated) ------------------------------------------------

    @Deprecated("Use openRouterApiKey instead", replaceWith = ReplaceWith("openRouterApiKey"))
    var apiKey: String
        get() = if (::prefs.isInitialized) prefs.getString("api_key", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("api_key", value).apply() }

    @Deprecated("Use openRouterApiKey instead", replaceWith = ReplaceWith("openRouterApiKey"))
    var groqApiKey: String
        get() = openRouterApiKey
        set(value) { openRouterApiKey = value }

    @Deprecated("Use openRouterSelectedModel instead", replaceWith = ReplaceWith("openRouterSelectedModel"))
    var groqSelectedModel: String
        get() = openRouterSelectedModel
        set(value) { openRouterSelectedModel = value }

    @Deprecated("Use openRouterApiKey instead", replaceWith = ReplaceWith("openRouterApiKey"))
    var dashScopeApiKey: String
        get() = openRouterApiKey
        set(value) { openRouterApiKey = value }

    // -- Messaging Tokens ------------------------------------------------

    var telegramToken: String
        get() = if (::prefs.isInitialized) prefs.getString("telegram_token", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("telegram_token", value).apply() }

    var discordToken: String
        get() = if (::prefs.isInitialized) prefs.getString("discord_token", "") ?: "" else ""
        set(value) { if (::prefs.isInitialized) prefs.edit().putString("discord_token", value).apply() }
}
