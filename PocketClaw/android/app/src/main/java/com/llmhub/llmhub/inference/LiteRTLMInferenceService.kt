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
import com.google.ai.edge.litertlm.Contents
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Inference service backed by Google's LiteRT-LM runtime.
 * Supports `.litertlm` format models (Qwen3, Gemma3/4, SmolVLM2, etc.)
 */
class LiteRTLMInferenceService(private val context: Context) : InferenceService {

    private val TAG = "LiteRTLMInference"

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModel: LLMModel? = null
    private var currentBackend: LlmInference.Backend? = null
    private var isVisionDisabled: Boolean = false
    private var isAudioDisabled: Boolean = false

    // Generation parameter overrides
    private var overrideMaxTokens: Int? = null
    private var overrideTopK: Int? = null
    private var overrideTopP: Float? = null
    private var overrideTemperature: Float? = null

    // Session reset tracking
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
                // Unload previous model if any
                unloadModelInternal()

                // Resolve model file
                val modelFile = resolveModelFile(model)
                if (!modelFile.exists()) {
                    Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
                    return@withContext false
                }

                Log.i(TAG, "Loading model: ${model.name} from ${modelFile.absolutePath}")

                // Map MediaPipe backend to LiteRT-LM backend
                val backend = mapBackend(preferredBackend, model)
                Log.i(TAG, "Using backend: $backend")

                // Create engine
                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    cacheDir = context.cacheDir.absolutePath
                )

                engine = Engine(engineConfig)
                engine!!.initialize()

                // Create conversation with generation parameters
                val samplerConfig = SamplerConfig(
                    topK = overrideTopK ?: 40,
                    topP = (overrideTopP ?: 0.95f).toDouble(),
                    temperature = (overrideTemperature ?: 0.8f).toDouble(),
                )

                val convConfig = ConversationConfig(
                    samplerConfig = samplerConfig,
                )

                conversation = engine!!.createConversation(convConfig)

                currentModel = model
                currentBackend = preferredBackend
                isVisionDisabled = disableVision
                isAudioDisabled = disableAudio

                Log.i(TAG, "✓ Model loaded successfully: ${model.name}")
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
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation: ${e.message}")
        }
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing engine: ${e.message}")
        }
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
            val conv = conversation ?: throw IllegalStateException("No conversation loaded")
            try {
                conv.sendMessageAsync(prompt).collect { message ->
                    emit(message.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in generateResponseStream: ${e.message}", e)
                throw e
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
        // For now, use the basic prompt (vision/audio support can be added later)
        // LiteRT-LM supports multimodal via Message.user(content) with images
        return generateResponseStream(prompt, model)
    }

    override suspend fun resetChatSession(chatId: String) {
        sessionResetTimes[chatId] = System.currentTimeMillis()
        // LiteRT-LM conversations are stateless per conversation object
        // Create a new conversation to reset state
        try {
            conversation?.close()
            conversation = null

            // Recreate conversation with same engine
            val currentEngine = engine ?: return
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

    override suspend fun onCleared() {
        unloadModelInternal()
    }

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

    /**
     * Resolve the model file path for .litertlm models.
     */
    private fun resolveModelFile(model: LLMModel): File {
        val modelsDir = File(context.filesDir, "models")
        val localName = model.localFileName()

        // Check files directory
        val primaryFile = File(modelsDir, localName)
        if (primaryFile.exists()) return primaryFile

        // Check if it's in a subdirectory
        val modelDirName = model.name.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_.-]"), "")
        val modelDir = File(modelsDir, modelDirName)
        if (modelDir.exists()) {
            val found = modelDir.listFiles { _, name -> name.endsWith(".litertlm") }
            if (found?.isNotEmpty() == true) return found.first()
        }

        // Fallback: check filesDir/models/
        return primaryFile
    }

    /**
     * Map MediaPipe Backend type to LiteRT-LM Backend.
     */
    private fun mapBackend(preferred: LlmInference.Backend?, model: LLMModel): Backend {
        return when {
            preferred == LlmInference.Backend.GPU -> Backend.GPU()
            preferred == LlmInference.Backend.CPU -> Backend.CPU()
            model.supportsGpu -> Backend.GPU()
            else -> Backend.CPU()
        }
    }
}
