package com.pocketclaw.app.api

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Request
import org.json.JSONObject

/**
 * Shared SSE (Server-Sent Events) streaming execution for OpenAI-compatible APIs.
 *
 * Handles the common pattern of:
 * 1. Executing an OkHttp request
 * 2. Reading the response body as a line-buffered stream
 * 3. Parsing `data: ` lines for `choices[0].delta.content`
 * 4. Emitting parsed content chunks via [Flow]
 * 5. Defensive error handling with user-readable messages
 *
 * This is a pure-network utility — no ViewModel or state logic.
 */
object StreamingSSE {

    /**
     * Execute [request], parse the SSE stream, and emit content chunks.
     *
     * @param request A fully-formed OkHttp [Request] (caller must set URL, auth, body).
     * @param client The [okhttp3.OkHttpClient] to use (caller manages timeouts).
     * @param tag Log tag for the calling provider (e.g. "OpenRouter").
     * @return A cold [Flow] of text chunks. Never throws — errors are emitted as final items.
     */
    fun executeAndStream(
        request: Request,
        client: okhttp3.OkHttpClient,
        tag: String,
    ): Flow<String> = flow {
        var response: okhttp3.Response? = null
        try {
            response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unbekannter Fehler"
                Log.e(tag, "API error ${response.code}: $errorBody")
                emit("API Fehler ${response.code}: ${extractErrorMessage(errorBody)}")
                return@flow
            }

            val reader = response.body?.byteStream()?.bufferedReader()
            if (reader == null) {
                emit("Kein Antwort-Body von Server.")
                return@flow
            }

            reader.use { r ->
                for (line in r.lineSequence()) {
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    val content = parseDeltaContent(data)
                    if (content != null) {
                        emit(content)
                    }
                }
            }

            Log.d(tag, "Stream completed")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(tag, "Timeout: ${e.message}")
            emit("Zeitüberschreitung. Versuch es erneut.")
        } catch (e: java.net.UnknownHostException) {
            Log.e(tag, "DNS failed: ${e.message}")
            emit("Keine Internetverbindung.")
        } catch (e: java.net.ConnectException) {
            Log.e(tag, "Connection refused: ${e.message}")
            emit("Verbindung verweigert. Überprüfe die Server-Konfiguration.")
        } catch (e: Exception) {
            Log.e(tag, "Stream failed: ${e.message}", e)
            emit("Verbindungsfehler: ${e.message}")
        } finally {
            response?.close()
        }
    }

    /**
     * Safely extracts `choices[0].delta.content` from a raw SSE data line.
     * Returns `null` when the line is unparseable or content is missing/blank.
     */
    fun parseDeltaContent(data: String): String? {
        return try {
            val chunk = JSONObject(data)
            val choices = chunk.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null
            val content = delta.optString("content", "")
            content.ifBlank { null }
        } catch (e: Exception) {
            Log.w("SSE", "Failed to parse chunk: ${data.take(120)}")
            null
        }
    }

    private fun extractErrorMessage(raw: String): String {
        return try {
            val obj = JSONObject(raw)
            obj.optJSONObject("error")?.optString("message") ?: raw.take(200)
        } catch (_: Exception) {
            raw.take(200)
        }
    }
}
