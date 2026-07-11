package com.llmhub.llmhub.inference

import android.content.Context
import android.graphics.Bitmap
import com.llmhub.llmhub.data.LLMModel
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Unified Inference Service that routes requests to the appropriate backend Service
 * (MediaPipe or ONNX) based on the model format.
 */
class UnifiedInferenceService(private val context: Context) : InferenceService {

    private val mediaPipeService = MediaPipeInferenceService(context)
    private val onnxService = OnnxInferenceService(context)
    private val nexaService = NexaInferenceService(context)
    private val liteRTLMService = LiteRTLMInferenceService(context)

    /** Whether the Nexa backend (for GGUF models) is usable on this device. */
    fun isNexaAvailable(): Boolean =
        (nexaService as? com.llmhub.llmhub.inference.NexaInferenceService)?.isAvailable() == true
    
    private var currentService: InferenceService = mediaPipeService
    private var currentModel: LLMModel? = null

    override suspend fun loadModel(model: LLMModel, preferredBackend: LlmInference.Backend?, deviceId: String?): Boolean {
        // Determine which service to use based on model format
        if (model.modelFormat == "gguf" && !isNexaAvailable()) {
            android.util.Log.w("UnifiedInferenceService", "Nexa SDK unavailable - cannot load GGUF model. Use a MediaPipe (.task) or LiteRT-LM (.litertlm) model instead.")
            throw AllBackendsFailedException("GGUF backend unavailable on this device. Please use a LiteRT-LM (.litertlm) or MediaPipe (.task) model.")
        }
        val targetService = when (model.modelFormat) {
            "onnx" -> onnxService
            "gguf" -> nexaService
            "litertlm" -> liteRTLMService
            else -> mediaPipeService
        }

        // Same service and same model already loaded with same backend: skip reload (honor user's CPU/GPU choice on next load)
        if (currentService == targetService) {
            val loaded = currentService.getCurrentlyLoadedModel()
            val currentBackend = currentService.getCurrentlyLoadedBackend()
            if (loaded?.name == model.name && (preferredBackend == null || preferredBackend == currentBackend)) {
                currentModel = model
                return true
            }
        }

        // If switching services, unload the old one
        if (currentService != targetService && currentModel != null) {
            currentService.unloadModel()
        }

        currentService = targetService
        currentModel = model
        
        try {
            val success = currentService.loadModel(model, preferredBackend, deviceId)
            if (!success) {
                currentModel = null
                throw AllBackendsFailedException("Backend ${currentService.javaClass.simpleName} failed to load model '${model.name}'")
            }
            return true
        } catch (e: AllBackendsFailedException) {
            currentModel = null
            throw e
        } catch (e: Exception) {
            android.util.Log.e("UnifiedInferenceService", "Service ${currentService.javaClass.simpleName} failed to load model '${model.name}'", e)
            currentModel = null
            throw AllBackendsFailedException("Failed to load model '${model.name}': ${e.message}")
        }
    }

    override suspend fun loadModel(
        model: LLMModel, 
        preferredBackend: LlmInference.Backend?, 
        disableVision: Boolean, 
        disableAudio: Boolean,
        deviceId: String?
    ): Boolean {
        if (model.modelFormat == "gguf" && !isNexaAvailable()) {
            android.util.Log.w("UnifiedInferenceService", "Nexa SDK unavailable - cannot load GGUF model. Use a MediaPipe (.task) or LiteRT-LM (.litertlm) model instead.")
            throw AllBackendsFailedException("GGUF backend unavailable on this device. Please use a LiteRT-LM (.litertlm) or MediaPipe (.task) model.")
        }
        val targetService = when (model.modelFormat) {
            "onnx" -> onnxService
            "gguf" -> nexaService
            "litertlm" -> liteRTLMService
            else -> mediaPipeService
        }

        // Same service and same model already loaded with same backend: skip reload (honor user's CPU/GPU choice on next load)
        if (currentService == targetService) {
            val loaded = currentService.getCurrentlyLoadedModel()
            val currentBackend = currentService.getCurrentlyLoadedBackend()
            if (loaded?.name == model.name && (preferredBackend == null || preferredBackend == currentBackend)) {
                currentModel = model
                return true
            }
        }

        // If switching services, unload the old one
        if (currentService != targetService && currentModel != null) {
            currentService.unloadModel()
        }

        currentService = targetService
        currentModel = model
        
        try {
            val success = currentService.loadModel(model, preferredBackend, disableVision, disableAudio, deviceId)
            if (!success) {
                currentModel = null
                throw AllBackendsFailedException("Backend ${currentService.javaClass.simpleName} failed to load model '${model.name}'")
            }
            return true
        } catch (e: AllBackendsFailedException) {
            currentModel = null
            throw e
        } catch (e: Exception) {
            android.util.Log.e("UnifiedInferenceService", "Service ${currentService.javaClass.simpleName} failed to load model '${model.name}'", e)
            currentModel = null
            throw AllBackendsFailedException("Failed to load model '${model.name}': ${e.message}")
        }
    }

    override suspend fun unloadModel() {
        currentService.unloadModel()
        currentModel = null
    }

    override suspend fun generateResponse(prompt: String, model: LLMModel): String {
        return currentService.generateResponse(prompt, model)
    }

    override suspend fun generateResponseStream(prompt: String, model: LLMModel): Flow<String> {
        return currentService.generateResponseStream(prompt, model)
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
        return currentService.generateResponseStreamWithSession(prompt, model, chatId, images, audioData, webSearchEnabled, imagePaths)
    }

    override suspend fun resetChatSession(chatId: String) {
        currentService.resetChatSession(chatId)
    }

    override suspend fun onCleared() {
        mediaPipeService.onCleared()
        onnxService.onCleared()
        if (isNexaAvailable()) {
            nexaService.onCleared()
        }
    }

    override fun getCurrentlyLoadedModel(): LLMModel? {
        return currentService.getCurrentlyLoadedModel()
    }

    override fun getCurrentlyLoadedBackend(): LlmInference.Backend? {
        return currentService.getCurrentlyLoadedBackend()
    }

    override fun getMemoryWarningForImages(images: List<Bitmap>): String? {
        return currentService.getMemoryWarningForImages(images)
    }

    override fun wasSessionRecentlyReset(chatId: String): Boolean {
        return currentService.wasSessionRecentlyReset(chatId)
    }

    override fun setGenerationParameters(maxTokens: Int?, topK: Int?, topP: Float?, temperature: Float?, nGpuLayers: Int?, enableThinking: Boolean?) {
        mediaPipeService.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking)
        onnxService.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking)
        liteRTLMService.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking)
        if (isNexaAvailable()) {
            nexaService.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking)
        }
    }

    override fun isVisionCurrentlyDisabled(): Boolean {
        return currentService.isVisionCurrentlyDisabled()
    }

    override fun isAudioCurrentlyDisabled(): Boolean {
        return currentService.isAudioCurrentlyDisabled()
    }

    override fun isGpuBackendEnabled(): Boolean {
        return currentService.isGpuBackendEnabled()
    }

    override fun getEffectiveMaxTokens(model: LLMModel): Int {
        return when (model.modelFormat) {
            "onnx" -> onnxService.getEffectiveMaxTokens(model)
            "gguf" -> if (isNexaAvailable()) nexaService.getEffectiveMaxTokens(model) else mediaPipeService.getEffectiveMaxTokens(model)
            "litertlm" -> liteRTLMService.getEffectiveMaxTokens(model)
            else -> mediaPipeService.getEffectiveMaxTokens(model)
        }
    }
}
