package com.pocketclaw.app.api

import android.util.Log
import com.pocketclaw.app.data.Preferences
import com.pocketclaw.claw.prompt.PromptAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Self-hosted / local inference via an Ollama server.
 *
 * Endpoint: `{Preferences.ollamaHost}/v1/chat/completions` (OpenAI-compatible).
 * Registry: `{Preferences.ollamaHost}/api/tags` for model discovery.
 * Auth: none required (local server).
 *
 * Supports multimodal content: images are sent as `image_url` content parts,
 * text files are appended to the system prompt as labeled code blocks.
 *
 * Thread-safety: all network activity runs on [Dispatchers.IO] via [flowOn].
 */
object OllamaProvider : CloudInferenceProvider {

    private const val TAG = "Ollama"

    override val displayName: String = "Ollama"

    override fun isReady(): Boolean = Preferences.ollamaHost.isNotBlank()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // -- Stream ----------------------------------------------------------------

    override fun generateStream(
        assembled: PromptAssembler.AssembledPrompt,
        attachments: List<FileAttachment>,
    ): Flow<String> = flow {
        val host = Preferences.ollamaHost
        if (host.isBlank()) {
            emit("Kein Ollama-Host konfiguriert. Bitte in Einstellungen eintragen.")
            return@flow
        }

        val model = Preferences.ollamaSelectedModel
        if (model.isBlank()) {
            emit("Kein Ollama-Modell ausgewählt. Bitte in Einstellungen ein Modell wählen.")
            return@flow
        }

        val url = host.trimEnd('/') + "/v1/chat/completions"
        val body = buildRequestBody(assembled, model, attachments)
        Log.d(TAG, "Request url=$url model=$model, attachments=${attachments.size}")

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        StreamingSSE.executeAndStream(request, client, TAG).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    // -- Request Body ------------------------------------------------

    private fun buildRequestBody(
        assembled: PromptAssembler.AssembledPrompt,
        model: String,
        attachments: List<FileAttachment>,
    ): JSONObject {
        val messages = JSONArray()

        // System prompt — append text attachments as labeled code blocks
        val systemContent = buildString {
            append(assembled.systemPrompt)
            for (att in attachments.filter { it.isText }) {
                append("\n\n--- File: ${att.fileName} (${att.mimeType}) ---\n")
                append("```\n${att.base64Data}\n```")
            }
        }
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemContent)
        })

        for (turn in assembled.chatHistory) {
            messages.put(JSONObject().apply {
                put("role", turn.role)
                put("content", turn.content)
            })
        }

        // User message — may contain image attachments as multimodal content parts
        val imageAttachments = attachments.filter { it.isImage }
        if (imageAttachments.isNotEmpty()) {
            val contentArray = JSONArray()
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", assembled.userMessage)
            })
            for (img in imageAttachments) {
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:${img.mimeType};base64,${img.base64Data}")
                    })
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        } else {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", assembled.userMessage)
            })
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", true)
            put("temperature", 0.7)
        }
    }

    // -- Health Check ------------------------------------------------

    suspend fun checkConnection(): Boolean = withContext(Dispatchers.IO) {
        val host = Preferences.ollamaHost
        if (host.isBlank()) return@withContext false
        try {
            val request = Request.Builder()
                .url(host.trimEnd('/') + "/api/tags")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Connection check failed: ${e.message}")
            false
        }
    }

    suspend fun fetchAvailableModels(): List<String> = withContext(Dispatchers.IO) {
        val host = Preferences.ollamaHost
        if (host.isBlank()) return@withContext emptyList()
        try {
            val request = Request.Builder()
                .url(host.trimEnd('/') + "/api/tags")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val models = json.optJSONArray("models") ?: return@withContext emptyList()
            (0 until models.length()).mapNotNull { i ->
                models.getJSONObject(i).optString("name")
            }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch models: ${e.message}")
            emptyList()
        }
    }
}

/**
 * Ollama Cloud – hosted inference via ollama.com.
 *
 * Routes to `https://ollama.com/v1/chat/completions` (OpenAI-compatible).
 * Auth: Bearer token from [Preferences.ollamaApiKey].
 *
 * Differs from local [OllamaProvider]: uses cloud URL + Authorization header.
 */
object OllamaCloudProvider : CloudInferenceProvider {

    private const val TAG = "OllamaCloud"
    private const val BASE_URL = "https://ollama.com/api/v1/chat/completions"

    override val displayName: String = "Ollama Cloud"

    override fun isReady(): Boolean = Preferences.ollamaApiKey.isNotBlank()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun generateStream(
        assembled: PromptAssembler.AssembledPrompt,
        attachments: List<FileAttachment>,
    ): Flow<String> = flow {
        val apiKey = Preferences.ollamaApiKey
        if (apiKey.isBlank()) {
            emit("Kein Ollama Cloud API Key konfiguriert. Bitte in Einstellungen eintragen.")
            return@flow
        }

        val model = Preferences.ollamaSelectedModel
        if (model.isBlank()) {
            emit("Kein Ollama Cloud Modell ausgewählt. Bitte in Einstellungen ein Modell wählen.")
            return@flow
        }

        val body = buildRequestBody(assembled, model, attachments)
        Log.d(TAG, "Request model=$model, attachments=${attachments.size}")

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        StreamingSSE.executeAndStream(request, client, TAG).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(
        assembled: PromptAssembler.AssembledPrompt,
        model: String,
        attachments: List<FileAttachment>,
    ): JSONObject {
        val messages = JSONArray()

        val systemContent = buildString {
            append(assembled.systemPrompt)
            for (att in attachments.filter { it.isText }) {
                append("\n\n--- File: ${att.fileName} (${att.mimeType}) ---\n")
                append("```\n${att.base64Data}\n```")
            }
        }
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemContent)
        })

        for (turn in assembled.chatHistory) {
            messages.put(JSONObject().apply {
                put("role", turn.role)
                put("content", turn.content)
            })
        }

        val imageAttachments = attachments.filter { it.isImage }
        if (imageAttachments.isNotEmpty()) {
            val contentArray = JSONArray()
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", assembled.userMessage)
            })
            for (img in imageAttachments) {
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:${img.mimeType};base64,${img.base64Data}")
                    })
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        } else {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", assembled.userMessage)
            })
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", true)
            put("temperature", 0.7)
        }
    }
}
