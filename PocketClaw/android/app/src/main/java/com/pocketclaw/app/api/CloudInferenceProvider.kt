package com.pocketclaw.app.api

import com.pocketclaw.claw.prompt.PromptAssembler
import kotlinx.coroutines.flow.Flow

/**
 * A file or image attachment to include in a chat completion request.
 *
 * @property mimeType MIME type of the content (e.g. "image/jpeg", "text/plain").
 *   Image types are sent as multimodal `image_url` content parts.
 *   Text types are appended to the system prompt as a labeled code block.
 * @property base64Data Base64-encoded content of the file.
 * @property fileName Original filename for display context (e.g. "screenshot.jpg").
 */
data class FileAttachment(
    val mimeType: String,
    val base64Data: String,
    val fileName: String,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isText: Boolean get() = !isImage
}

/**
 * Unified interface for cloud-based LLM inference backends.
 *
 * Every implementation must use the OpenAI Chat Completions wire format
 * (POST /v1/chat/completions with SSE streaming). Concrete providers:
 *
 * - [OpenRouterProvider] — managed cloud API (openrouter.ai)
 * - [OllamaProvider] — self-hosted / local HTTP server (localhost:11434)
 * - [OpenCodeZenProvider] — enterprise routing proxy gateway
 *
 * Callers interact exclusively through this abstraction. They never import
 * concrete provider types except for provider-specific operations such as
 * [OllamaProvider.checkConnection] or [OllamaProvider.fetchAvailableModels].
 */
interface CloudInferenceProvider {

    /**
     * Human-readable name shown in the UI and in error messages
     * (e.g. "OpenRouter", "Ollama").
     */
    val displayName: String

    /**
     * Whether this provider is fully configured and ready to serve requests.
     * For API-key-based providers this checks for a non-blank key.
     * For server-based providers this checks that a host URL is set.
     *
     * This is a function (not a property) so that implementations can
     * perform lightweight checks (e.g. SharedPreferences reads) without
     * surprising side effects on every access.
     */
    fun isReady(): Boolean

    /**
     * Stream a chat completion response using the OpenAI-compatible SSE format.
     *
     * The returned [Flow] emits one [String] chunk per SSE event. The
     * implementation must:
     * 1. Run all network I/O on `Dispatchers.IO`.
     * 2. Parse `choices[0].delta.content` from each `data:` line.
     * 3. Emit an empty string or skip the event when `delta.content` is
     *    absent or blank (common for role-only first chunks).
     * 4. Terminate the flow (not throw) when the stream ends with `[DONE]`.
     * 5. Wrap every network call in a try-catch and emit a user-readable
     *    error message before closing the flow — never crash the collector.
     *
     * @param assembled The assembled prompt with system instructions, chat
     *   history, and user message.
     * @param attachments Optional file/image attachments. Images are sent as
     *   multimodal `image_url` content parts. Text files are appended as
     *   labeled code blocks in the system prompt.
     * @return A cold [Flow] of text chunks as they arrive from the API.
     */
    fun generateStream(
        assembled: PromptAssembler.AssembledPrompt,
        attachments: List<FileAttachment> = emptyList(),
    ): Flow<String>
}
