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
 * Premium cloud inference via the OpenCode Zen routing proxy gateway.
 *
 * Endpoint: configurable via [Preferences.openCodeZenEndpoint] (default:
 * `https://zen.opencode.example/v1/chat/completions`).
 * Auth: Bearer token from [Preferences.openCodeZenApiKey].
 * Streaming: Server-Sent Events with standard OpenAI `choices[0].delta.content` format.
 *
 * Adds enterprise-grade headers (`X-Zen-Routing: pocketclaw`, `X-Zen-Tier: premium`)
 * so the gateway can apply specialized routing policies for this client.
 *
 * Supports multimodal content: images are sent as `image_url` content parts,
 * text files are appended to the system prompt as labeled code blocks.
 *
 * Thread-safety: all network activity runs on [Dispatchers.IO] via [flowOn].
 */
object OpenCodeZenProvider : CloudInferenceProvider {

    private const val TAG = "OpenCodeZen"
    private const val CHAT_PATH = "/chat/completions"

    override val displayName: String = "OpenCode Zen"

    override fun isReady(): Boolean {
        val hasKey = Preferences.openCodeZenApiKey.isNotBlank()
        val hasEndpoint = Preferences.openCodeZenEndpoint.isNotBlank()
        return hasKey && hasEndpoint
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // -- Stream ----------------------------------------------------------------

    override fun generateStream(
        assembled: PromptAssembler.AssembledPrompt,
        attachments: List<FileAttachment>,
    ): Flow<String> = flow {
        val apiKey = Preferences.openCodeZenApiKey
        val endpoint = Preferences.openCodeZenEndpoint

        if (apiKey.isBlank()) {
            emit("Kein OpenCode Zen API Key konfiguriert. Bitte in Einstellungen eintragen.")
            return@flow
        }
        if (endpoint.isBlank()) {
            emit("Kein OpenCode Zen Gateway konfiguriert. Bitte in Einstellungen eintragen.")
            return@flow
        }

        val model = Preferences.openCodeZenSelectedModel
        val url = endpoint.trimEnd('/') + CHAT_PATH
        val body = buildRequestBody(assembled, model, attachments)
        Log.d(TAG, "Request url=$url model=$model, attachments=${attachments.size}")

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Zen-Routing", "pocketclaw")
            .addHeader("X-Zen-Tier", "premium")
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
            put("max_tokens", 2048)
        }
    }

    // -- Health Check ------------------------------------------------

    suspend fun checkGateway(): Boolean = withContext(Dispatchers.IO) {
        val endpoint = Preferences.openCodeZenEndpoint
        if (endpoint.isBlank()) return@withContext false
        try {
            val url = endpoint.trimEnd('/') + "/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${Preferences.openCodeZenApiKey}")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Gateway check failed: ${e.message}")
            false
        }
    }
}
