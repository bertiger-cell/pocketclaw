package com.llmhub.llmhub.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.localFileName
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

class LiteRTLMInferenceService(private val context: Context) : InferenceService {

    private val TAG = "LiteRTLMInference"
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModel: LLMModel? = null
    private var currentBackend: LlmInference.Backend? = null
    private var isVisionDisabled: Boolean = false
    private var isAudioDisabled: Boolean = false

    private var overrideMaxTokens: Int? = null
    private var overrideTopK: Int? = null
    private var overrideTopP: Float? = null
    private var overrideTemperature: Float? = null

    private val sessionResetTimes = mutableMapOf<String, Long>()

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
        return try {
            withContext(Dispatchers.IO) {
                unloadModelInternal()

                val modelFile = resolveModelFile(model)
                if (!modelFile.exists()) {
                    Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
                    return@withContext false
                }

                Log.i(TAG, "Loading model: ${model.name} from ${modelFile.absolutePath}")

                val backend = mapBackend(preferredBackend, model)
                Log.i(TAG, "Using backend: $backend")

                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    cacheDir = context.cacheDir.absolutePath
                )

                engine = Engine(engineConfig)
                engine!!.initialize()

                val samplerConfig = SamplerConfig(
                    topK = overrideTopK ?: 40,
                    topP = (overrideTopP ?: 0.95f).toDouble(),
                    temperature = (overrideTemperature ?: 0.8f).toDouble(),
                )

                conversation = engine!!.createConversation(
                    ConversationConfig(samplerConfig = samplerConfig)
                )

                currentModel = model
                currentBackend = preferredBackend
                isVisionDisabled = disableVision
                isAudioDisabled = disableAudio

                Log.i(TAG, "Model loaded successfully: ${model.name}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            unloadModelInternal()
            false
        }
    }

    override suspend fun unloadModel() {
        unloadModelInternal()
    }

    private fun unloadModelInternal() {
        try { conversation?.close() } catch (_: Exception) {}
        try { engine?.close() } catch (_: Exception) {}
        conversation = null
        engine = null
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
            var conv = conversation
            if (conv == null) {
                // Try to reinitialize
                Log.w(TAG, "Conversation was null, attempting recovery via loadModel")
                engine?.let { eng ->
                    try {
                        val samplerConfig = SamplerConfig(
                            topK = 40, topP = 0.95, temperature = 0.8,
                        )
                        conv = eng.createConversation(
                            ConversationConfig(samplerConfig = samplerConfig)
                        )
                        conversation = conv
                        Log.i(TAG, "Conversation reinitialized successfully")
                    } catch (reinitError: Exception) {
                        Log.e(TAG, "Failed to reinitialize conversation: ${reinitError.message}")
                        emit("❌ Modell nicht geladen. Bitte Modell neu laden.")
                        return@flow
                    }
                } ?: run {
                    emit("❌ Kein Modell geladen. Bitte zuerst ein Modell downloaden und laden.")
                    return@flow
                }
            }
            try {
                conv!!.sendMessageAsync(prompt).collect { message ->
                    val text = message.toString()
                    if (text.isNotBlank()) {
                        emit(text)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in generateResponseStream: ${e.message}", e)
                emit("

[Fehler: ${e.message}]")
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
        try {
            val oldConv = conversation
            conversation = null
            try { oldConv?.close() } catch (_: Exception) {}
            val currentEngine = engine
            if (currentEngine == null) {
                Log.w(TAG, "Cannot reset session: engine is null")
                return
            }
            val samplerConfig = SamplerConfig(
                topK = overrideTopK ?: 40,
                topP = (overrideTopP ?: 0.95f).toDouble(),
                temperature = (overrideTemperature ?: 0.8f).toDouble(),
            )
            conversation = currentEngine.createConversation(
                ConversationConfig(samplerConfig = samplerConfig)
            )
            Log.d(TAG, "Chat session reset for $chatId")
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting chat session: ${e.message}")
        }
    }

    override suspend fun onCleared() { unloadModelInternal() }
    override fun getCurrentlyLoadedModel(): LLMModel? = currentModel
    override fun getCurrentlyLoadedBackend(): LlmInference.Backend? = currentBackend
    override fun isVisionCurrentlyDisabled(): Boolean = isVisionDisabled
    override fun isAudioCurrentlyDisabled(): Boolean = isAudioDisabled
    override fun isGpuBackendEnabled(): Boolean = currentBackend == LlmInference.Backend.GPU
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

    private fun resolveModelFile(model: LLMModel): File {
        val modelsDir = File(context.filesDir, "models")
        val localName = model.localFileName()
        val primaryFile = File(modelsDir, localName)
        if (primaryFile.exists()) return primaryFile
        val modelDirName = model.name.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_.-]"), "")
        val modelDir = File(modelsDir, modelDirName)
        if (modelDir.exists()) {
            val found = modelDir.listFiles { _, name -> name.endsWith(".litertlm") }
            if (found?.isNotEmpty() == true) return found.first()
        }
        return primaryFile
    }

    private fun mapBackend(preferred: LlmInference.Backend?, model: LLMModel): Backend {
        return when {
            preferred == LlmInference.Backend.GPU -> Backend.GPU()
            preferred == LlmInference.Backend.CPU -> Backend.CPU()
            model.supportsGpu -> Backend.GPU()
            else -> Backend.CPU()
        }
    }
}
