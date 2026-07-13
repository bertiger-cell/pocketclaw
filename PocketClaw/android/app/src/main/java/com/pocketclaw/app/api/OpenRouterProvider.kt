package com.pocketclaw.app.api

import android.util.Log
import com.pocketclaw.app.data.Preferences
import com.pocketclaw.claw.prompt.PromptAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Managed cloud inference via the OpenRouter API.
 *
 * Endpoint: `https://openrouter.ai/api/v1/chat/completions`
 * Auth: Bearer token from [Preferences.openRouterApiKey].
 * Streaming: Server-Sent Events with standard OpenAI `choices[0].delta.content` format.
 *
 * Supports multimodal content: images are sent as `image_url` content parts,
 * text files are appended to the system prompt as labeled code blocks.
 *
 * Thread-safety: all network activity runs on [Dispatchers.IO] via [flowOn].
 */
object OpenRouterProvider : CloudInferenceProvider {

    private const val TAG = "OpenRouter"
    private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"

    override val displayName: String = "OpenRouter"

    override fun isReady(): Boolean = Preferences.openRouterApiKey.isNotBlank()

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
        val apiKey = Preferences.openRouterApiKey
        if (apiKey.isBlank()) {
            emit("Kein OpenRouter API Key konfiguriert. Bitte in Einstellungen eintragen.")
            return@flow
        }

        val model = Preferences.openRouterSelectedModel
        val body = buildRequestBody(assembled, model, attachments)
        Log.d(TAG, "Request model=$model, attachments=${attachments.size}")

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://pocketclaw.app")
            .addHeader("X-Title", "PocketClaw")
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
}

