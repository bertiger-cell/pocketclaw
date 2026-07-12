package com.pocketclaw.app.ui.download

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelData
import com.llmhub.llmhub.data.ModelDownloader
import com.llmhub.llmhub.data.ModelRepository
import com.llmhub.llmhub.data.localFileName
import com.pocketclaw.app.PocketClawApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single Responsibility: Model download, file verification, and download-state management.
 * Extracted from MainViewModel (code-refactoring-refactor-clean skill).
 *
 * Owns: downloadMutex, _modelDownloads, _modelLoading, _lastError, _allLocalModels
 * Does NOT own: inferenceService, chat, tools, voice - those stay in MainViewModel.
 */
class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DownloadVM"
    }

    private val app = application as PocketClawApplication

    /** Ensures only one model download runs at a time (kotlin-coroutines-expert pattern) */
    private val downloadMutex = Mutex()

    // -- Download State ------------------------------------------------

    @Stable
    data class ModelDownloadState(
        val isDownloading: Boolean = false,
        val progress: Float = 0f,
        val downloaded: Boolean = false,
        val modelId: String = "",
    )

    private val _modelDownloads = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val modelDownloads: StateFlow<Map<String, ModelDownloadState>> = _modelDownloads.asStateFlow()

    private val _modelLoading = MutableStateFlow(false)
    val modelLoading: StateFlow<Boolean> = _modelLoading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _allLocalModels = MutableStateFlow<List<LLMModel>>(emptyList())
    val allLocalModels: StateFlow<List<LLMModel>> = _allLocalModels.asStateFlow()

    private val _availableModels = MutableStateFlow<List<LLMModel>>(emptyList())
    val availableModels: StateFlow<List<LLMModel>> = _availableModels.asStateFlow()

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _currentModelName = MutableStateFlow("")
    val currentModelName: StateFlow<String> = _currentModelName.asStateFlow()

    // -- Public API ----------------------------------------------------

    fun downloadModel(model: LLMModel, onDownloaded: suspend (LLMModel) -> Unit) {
        val modelId = model.name
        if (_modelDownloads.value.any { it.value.isDownloading }) {
            Log.w(TAG, "Download blocked: another download is already running")
            return
        }

        viewModelScope.launch {
            downloadMutex.withLock {
                _modelDownloads.update { it + (modelId to ModelDownloadState(
                    isDownloading = true, progress = 0f, modelId = modelId
                )) }
                _lastError.value = null
                try {
                    val downloader = ModelDownloader(
                        io.ktor.client.HttpClient(), app,
                        com.pocketclaw.app.BuildConfig.HF_TOKEN.ifBlank { null },
                    )
                    val downloadResult = withTimeoutOrNull(600_000L) {
                        downloader.downloadModel(model).collect { status ->
                            val pct = if (status.totalBytes > 0)
                                (status.downloadedBytes.toFloat() / status.totalBytes).coerceIn(0f, 1f) else 0f
                            _modelDownloads.update { it + (modelId to ModelDownloadState(
                                isDownloading = true, progress = pct, modelId = modelId
                            )) }
                        }
                    }
                    if (downloadResult == null) {
                        _lastError.value = "Download-Timeout fur ${model.name} (10 Min. Limit)"
                        _modelDownloads.update { it + (modelId to ModelDownloadState(
                            isDownloading = false, modelId = modelId
                        )) }
                        return@withLock
                    }

                    val modelsDir = java.io.File(app.filesDir, "models")
                    val targetFile = java.io.File(modelsDir, model.localFileName())
                    if (targetFile.exists() && targetFile.length() > 1_000_000) {
                        val validationError = validateDownloadedFile(targetFile, model.modelFormat)
                        if (validationError != null) {
                            Log.w(TAG, "Validation failed for ${model.name}: $validationError")
                            targetFile.delete()
                            _lastError.value = validationError
                            _modelDownloads.update { it + (modelId to ModelDownloadState(
                                isDownloading = false, modelId = modelId
                            )) }
                        } else {
                            _modelDownloads.update { it + (modelId to ModelDownloadState(
                                downloaded = true, modelId = modelId
                            )) }
                            onDownloaded(model)
                        }
                    } else {
                        val reason = if (targetFile.exists()) "Datei zu klein (${targetFile.length()} bytes)" else "Datei nicht gefunden"
                        targetFile.delete()
                        _lastError.value = "Download fehlgeschlagen: $reason"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed for ${model.name}: ${e.message}", e)
                    _lastError.value = e.message
                } finally {
                    _modelDownloads.update { current ->
                        current + (modelId to (current[modelId]?.copy(isDownloading = false)
                            ?: ModelDownloadState(modelId = modelId)))
                    }
                }
            }
        }
    }

    fun downloadDeviceModels(models: List<LLMModel>, onDownloaded: suspend (LLMModel) -> Unit) {
        viewModelScope.launch {
            val toDownload = models.filter { model ->
                val dlState = _modelDownloads.value[model.name]
                dlState?.downloaded != true
            }
            if (toDownload.isEmpty()) return@launch
            for (model in toDownload) {
                downloadModel(model, onDownloaded)
            }
        }
    }

    fun deleteLocalModel(model: LLMModel) {
        viewModelScope.launch {
            try {
                val modelsDir = java.io.File(app.filesDir, "models")
                val modelFile = java.io.File(modelsDir, model.localFileName())
                val deleted = if (modelFile.exists()) modelFile.delete() else false
                val modelDirName = model.name.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_.-]"), "")
                val modelDir = java.io.File(modelsDir, modelDirName)
                if (modelDir.exists() && modelDir.isDirectory) modelDir.deleteRecursively()
                if (deleted || modelDir.exists()) {
                    _modelDownloads.update { it + (model.name to ModelDownloadState(
                        downloaded = false, modelId = model.name
                    )) }
                    loadAvailableModels()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed for ${model.name}: ${e.message}", e)
            }
        }
    }

    fun loadAvailableModels() {
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    ModelRepository.getAvailableModels(app)
                }
                _availableModels.value = models
                val excludedFormats = setOf("embedding", "groq", "tflite", "qnn_npu", "mnn_cpu")
                val excludedCategories = setOf("embedding", "image_generation", "multimodal")
                val allLocal = ModelData.models.filter {
                    it.category !in excludedCategories && it.modelFormat !in excludedFormats
                }
                val enriched = allLocal.map { model ->
                    val downloaded = models.find { it.name == model.name }?.isDownloaded ?: false
                    model.copy(isDownloaded = downloaded)
                }
                _allLocalModels.value = enriched
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load models: ${e.message}", e)
            }
        }
    }

    fun downloadQwenModel(onDownloaded: suspend (LLMModel) -> Unit) {
        val modelDef = ModelData.models.find {
            it.modelFormat == "litertlm" && it.name.contains("Qwen3")
        }
        modelDef?.let { downloadModel(it, onDownloaded) }
    }

    // -- File Validation ------------------------------------------------

    private fun validateDownloadedFile(file: java.io.File, modelFormat: String): String? {
        try {
            val header = ByteArray(512)
            var bytesRead = 0
            java.io.FileInputStream(file).use { fis ->
                bytesRead = fis.read(header)
            }
            if (bytesRead < 4) return "Datei ist leer oder zu klein"
            val headerStr = String(header, charset("ISO-8859-1")).trimStart('\u0000')

            val lowerHeader = headerStr.lowercase()
            if (lowerHeader.startsWith("<!doctype html") || lowerHeader.startsWith("<html") ||
                lowerHeader.contains("<title>403") || lowerHeader.contains("<title>404") ||
                lowerHeader.contains("access denied") || lowerHeader.contains("forbidden")) {
                return when {
                    lowerHeader.contains("403") || lowerHeader.contains("forbidden") || lowerHeader.contains("access denied") ->
                        "Token ungultig oder Modell erfordert HuggingFace-Lizenz. Prufe HF_TOKEN."
                    lowerHeader.contains("404") || lowerHeader.contains("not found") ->
                        "Modell nicht gefunden (HTTP 404). URL konnnte veraltet sein."
                    else ->
                        "Server liefert HTML statt Modell-Datei. Morchlicherweise Token erforderlich."
                }
            }

            when (modelFormat.lowercase()) {
                "gguf" -> {
                    if (bytesRead >= 4) {
                        val magic = String(header.copyOfRange(0, 4))
                        if (magic != "GGUF") return "Datei ist kein gultiges GGUF-Format (Magic: $magic)"
                    }
                }
                "task" -> {
                    if (bytesRead >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
                        // Valid ZIP header
                    } else if (file.length() < 10_000_000) {
                        return "MediaPipe .task Datei ist zu klein (${file.length() / 1_000_000} MB)"
                    }
                }
                "litertlm" -> {
                    if (file.length() < 10_000_000) {
                        return "LiteRT-LM Datei ist zu klein (${file.length() / 1_000_000} MB)"
                    }
                }
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "File validation error: ${e.message}")
            return "Validierungsfehler: ${e.message}"
        }
    }
}
