package com.llmhub.llmhub.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.pocketclaw.app.data.Preferences
import com.llmhub.llmhub.data.LLMModel
import com.pocketclaw.claw.tools.ToolRegistry
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Inference service backed by Groq Cloud API (OpenAI-compatible).
 * Uses qwen/qwen3-1.7b model on Groq's free tier.
 */
class GroqInferenceService(private val context: Context) : InferenceService {

    private val TAG = "GroqInference"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentModel: LLMModel? = null
    private var currentBackend: LlmInference.Backend? = null
    private var isVisionDisabled: Boolean = false
    private var isAudioDisabled: Boolean = false
    private var apiKey: String = ""

    private var overrideMaxTokens: Int? = null
    private var overrideTopK: Int? = null
    private var overrideTopP: Float? = null
    private var overrideTemperature: Float? = null

    private val sessionResetTimes = mutableMapOf<String, Long>()

    init {
        // Auto-load API key from Preferences
        val savedKey = Preferences.groqApiKey
        if (savedKey.isNotBlank()) {
            apiKey = savedKey.trim()
            Log.i(TAG, "Groq API key loaded from Preferences")
        }
    }

    fun setApiKey(key: String) {
        apiKey = key.trim()
        Preferences.groqApiKey = key
        Log.i(TAG, "API key set and saved")
    }

    fun getApiKey(): String = apiKey

    override suspend fun loadModel(model: LLMModel, preferredBackend: LlmInference.Backend?, deviceId: String?): Boolean {
        return loadModel(model, preferredBackend, false, false, deviceId)
    }

    override suspend fun loadModel(
        model: LLMModel,
        preferredBackend: LlmInference.Backend?,
        disableVision: Boolean,
        disableAudio: Boolean,
        deviceId: String?
    ): Boolean {
        if (apiKey.isBlank()) {
            Log.e(TAG, "No Groq API key set")
            return false
        }
        currentModel = model
        currentBackend = preferredBackend
        isVisionDisabled = disableVision
        isAudioDisabled = disableAudio
        Log.i(TAG, "Groq model ready: ${model.name} (API key present)")
        return true
    }

    override suspend fun unloadModel() {
        currentModel = null
        currentBackend = null
    }

    override suspend fun generateResponse(prompt: String, model: LLMModel): String {
        val sb = StringBuilder()
        generateResponseStream(prompt, model).collect { sb.append(it) }
        return sb.toString()
    }

    override suspend fun generateResponseStream(prompt: String, model: LLMModel): Flow<String> {
        return flow {
            val requestBody = buildRequestBody(prompt)
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Groq API error ${response.code}: $errorBody")
                emit("Fehler: Groq API ${response.code} - $errorBody")
                return@flow
            }

            val body = response.body?.string() ?: ""
            try {
                val json = JSONObject(body)
                val choices = json.getJSONArray("choices")
                if (choices.length() > 0) {
                    val message = choices.getJSONObject(0).getJSONObject("message")

                    // Emit text content if present
                    val content = message.optString("content", "")
                    if (content.isNotBlank()) {
                        emit(content)
                    }

                    // Check for OpenAI tool_calls and convert to text markers
                    if (message.has("tool_calls")) {
                        val toolCalls = message.getJSONArray("tool_calls")
                        for (i in 0 until toolCalls.length()) {
                            val tc = toolCalls.getJSONObject(i)
                            val fn = tc.getJSONObject("function")
                            val name = fn.getString("name")
                            val args = fn.optString("arguments", "")
                            // Convert to text marker format for ToolParser compatibility
                            emit("\n[T:${name}:${args}]\n")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Groq response: ${e.message}", e)
                emit("Fehler beim Parsen der Antwort")
            }
        }
    }

    override suspend fun generateResponseStreamWithSession(
        prompt: String,
        model: LLMModel,
        chatId: String,
        images: List<Bitmap>,
        audioData: ByteArray?,
        webSearchEnabled: Boolean,
        imagePaths: List<String>
    ): Flow<String> {
        return generateResponseStream(prompt, model)
    }

    override suspend fun resetChatSession(chatId: String) {
        sessionResetTimes[chatId] = System.currentTimeMillis()
    }

    override suspend fun onCleared() {
        currentModel = null
    }

    override fun getCurrentlyLoadedModel(): LLMModel? = currentModel
    override fun getCurrentlyLoadedBackend(): LlmInference.Backend? = currentBackend
    override fun isVisionCurrentlyDisabled(): Boolean = isVisionDisabled
    override fun isAudioCurrentlyDisabled(): Boolean = isAudioDisabled
    override fun isGpuBackendEnabled(): Boolean = false
    override fun wasSessionRecentlyReset(chatId: String): Boolean {
        val resetTime = sessionResetTimes[chatId] ?: return false
        return System.currentTimeMillis() - resetTime < 5000
    }

    override fun setGenerationParameters(maxTokens: Int?, topK: Int?, topP: Float?, temperature: Float?, nGpuLayers: Int?, enableThinking: Boolean?) {
        overrideMaxTokens = maxTokens
        overrideTopK = topK
        overrideTopP = topP
        overrideTemperature = temperature
    }

    override fun getEffectiveMaxTokens(model: LLMModel): Int {
        return overrideMaxTokens?.coerceAtMost(model.contextWindowSize) ?: model.contextWindowSize
    }

    override fun getMemoryWarningForImages(images: List<Bitmap>): String? = null

    private fun buildRequestBody(prompt: String): JSONObject {
        val messages = JSONArray().apply {
            // System message with tool instructions
            put(JSONObject().apply {
                put("role", "system")
                val toolPrompt = ToolRegistry.buildToolListPrompt()
                put("content", "Du bist PocketClaw, ein hilfreicher KI-Assistent.\nNutze Werkzeuge wenn nötig.\n\n$toolPrompt\n\nWichtig: Du KANNST Werkzeuge verwenden wenn sie helfen. Wenn kein Werkzeug benötigt wird, antworte einfach normal.\nAntworte auf Deutsch, kurz und präzise.")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", Preferences.groqSelectedModel)
            put("messages", messages)
            put("max_tokens", (currentModel?.let { getEffectiveMaxTokens(it) } ?: 4096))
            put("temperature", (overrideTemperature ?: 0.7).toDouble())
            if (overrideTopP != null) put("top_p", overrideTopP!!.toDouble())
        }

        // Add OpenAI-compatible tools from ToolRegistry
        val tools = ToolRegistry.all()
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                val toolObj = JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tool.id)
                        put("description", "${tool.name}: ${tool.description}")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("args", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Argument für ${tool.id}: ${tool.paramHint}")
                                })
                            })
                            put("required", JSONArray())
                        })
                    })
                }
                toolsArray.put(toolObj)
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        return body
    }
}
