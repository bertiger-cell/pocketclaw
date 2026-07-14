package com.pocketclaw.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelRepository
import com.llmhub.llmhub.inference.InferenceService
import com.pocketclaw.app.PocketClawApplication
import com.pocketclaw.app.ui.chat.ChatMessage
import com.pocketclaw.app.ui.chat.ChatViewModel
import com.pocketclaw.app.ui.download.DownloadViewModel
import com.pocketclaw.app.ui.memory.BondViewModel
import com.pocketclaw.app.ui.settings.SettingsViewModel
import com.pocketclaw.app.data.WorkspaceProject
import com.pocketclaw.app.ui.voice.VoiceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri

/**
 * Minimal orchestrator – wires sub-ViewModels together and owns only
 * cross-cutting model loading/switching logic that requires coordination
 * between DownloadVM, ChatVM, and SettingsVM.
 *
 * All UI-facing properties and functions are thin delegations.
 * No business logic lives here beyond orchestration.
 *
 * Architecture:
 *   PocketClawApplication
 *     ├── downloadViewModel  → model download & file verification
 *     ├── chatViewModel      → messages, generation pipeline, tool-call loop
 *     ├── voiceViewModel     → STT, TTS, recording
 *     ├── bondViewModel      → AI memory, growth state
 *     ├── settingsViewModel  → LLM mode, OpenRouter, Ollama, tasks, skills, audit
 *     └── MainViewModel (this) → orchestrator, model loading, backend-release wiring
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainVM"
    }

    private val app = application as PocketClawApplication
    private val downloadVM: DownloadViewModel = app.downloadViewModel
    private val chatVM: ChatViewModel = app.chatViewModel
    private val voiceVM: VoiceViewModel = app.voiceViewModel
    private val bondVM: BondViewModel = app.bondViewModel
    private val settingsVM: SettingsViewModel = app.settingsViewModel
    private val inferenceService: InferenceService = app.inferenceService

    private var currentModel: LLMModel? = null

    // =====================================================================
    // WIRING – connect cross-VM callbacks once at construction
    // =====================================================================

    init {
        // Voice → Chat: inject transcribed text into input
        voiceVM.onSpeechResult = { text ->
            chatVM.updateInputText((chatVM.inputText.value + " " + text).trim())
        }

        // Settings → Chat: inject system messages
        settingsVM.onSystemMessage = { text ->
            chatVM.addSystemMessage(text)
        }

        // Settings → Model Loading: trigger loadFirstAvailableModel
        settingsVM.onLoadFirstAvailableModel = {
            loadFirstAvailableModel()
        }

        // Settings → Chat: clear active workspace when deleted
        settingsVM.onWorkspaceDeleted = { project ->
            if (chatVM.activeWorkspace.value?.id == project.id) {
                chatVM.setActiveWorkspace(null)
            }
        }

        // Initial setup
        downloadVM.loadAvailableModels()
        settingsVM.initialize()
    }

    // =====================================================================
    // 1. CHAT DELEGATION
    // =====================================================================

    val messages: StateFlow<List<ChatMessage>> get() = chatVM.messages
    val inputText: StateFlow<String> get() = chatVM.inputText
    val isProcessing: StateFlow<Boolean> get() = chatVM.isProcessing
    val pendingConfirm: StateFlow<String?> get() = chatVM.pendingConfirm

    fun updateInputText(text: String) = chatVM.updateInputText(text)
    fun sendTextMessage(text: String) = chatVM.sendTextMessage(text)
    fun newTopic() = chatVM.newTopic()
    fun deleteMessage(message: ChatMessage) = chatVM.deleteMessage(message)

    val pendingAttachments: StateFlow<List<Uri>> get() = chatVM.pendingAttachments
    fun attachFile(uri: Uri) = chatVM.attachFile(uri)
    fun removeAttachment(uri: Uri) = chatVM.removeAttachment(uri)

    // =====================================================================
    // 2. MODEL DOWNLOAD DELEGATION
    // =====================================================================

    val isModelLoaded get() = downloadVM.isModelLoaded
    val currentModelName get() = downloadVM.currentModelName
    val availableModels get() = downloadVM.availableModels
    val allLocalModels get() = downloadVM.allLocalModels
    val modelDownloads get() = downloadVM.modelDownloads
    val modelLoading get() = downloadVM.modelLoading
    val lastError get() = downloadVM.lastError

    // =====================================================================
    // 3. VOICE DELEGATION
    // =====================================================================

    val isRecording: StateFlow<Boolean> get() = voiceVM.isRecording

    fun startRecording() = voiceVM.startRecording()
    fun stopRecording() = voiceVM.stopRecording()
    fun speakMessage(text: String) = voiceVM.speakMessage(text)

    // =====================================================================
    // 4. BOND DELEGATION
    // =====================================================================

    val memories get() = bondVM.memories
    val growthState get() = bondVM.growthState
    val growthStage get() = bondVM.growthStage
    val interactionCount get() = bondVM.interactionCount

    fun deleteMemory(memory: com.pocketclaw.claw.bond.BondMemory) = bondVM.deleteMemory(memory)
    fun upsertMemory(memory: com.pocketclaw.claw.bond.BondMemory) = bondVM.upsertMemory(memory)
    fun compressMemories() = bondVM.compressMemories()
    val isRecordingActive get() = bondVM.isRecordingActive

    // =====================================================================
    // 5. SETTINGS DELEGATION
    // =====================================================================

    val llmMode get() = settingsVM.llmMode
    val llmReady get() = settingsVM.llmReady
    val selectedOpenRouterModel get() = settingsVM.selectedOpenRouterModel
    val ollamaHost get() = settingsVM.ollamaHost
    val ollamaModels get() = settingsVM.ollamaModels
    val ollamaConnected get() = settingsVM.ollamaConnected
    val selectedOpenCodeZenModel get() = settingsVM.selectedOpenCodeZenModel
    val openCodeZenEndpoint get() = settingsVM.openCodeZenEndpoint
    val openCodeZenConnected get() = settingsVM.openCodeZenConnected
    val qwenDownloaded get() = settingsVM.qwenDownloaded
    val qwenDownloading get() = settingsVM.qwenDownloading
    val qwenProgress get() = settingsVM.qwenProgress
    val customSkills get() = settingsVM.customSkills
    val scheduledTasks get() = settingsVM.scheduledTasks
    val skillUsage get() = settingsVM.skillUsage
    val totalTokensUsed get() = settingsVM.totalTokensUsed
    val sessionTokensUsed get() = settingsVM.sessionTokensUsed
    val auditEntries get() = settingsVM.auditEntries
    val selectedDeviceProfile get() = settingsVM.selectedDeviceProfile
    val filteredModels get() = settingsVM.filteredModels

    fun switchLlmMode(mode: String) = settingsVM.switchLlmMode(mode)
    fun setOpenRouterApiKey(key: String) = settingsVM.setOpenRouterApiKey(key)
    fun setOpenRouterModel(modelId: String) = settingsVM.setOpenRouterModel(modelId)
    fun setOllamaHost(host: String) = settingsVM.setOllamaHost(host)
    fun setOllamaModel(modelId: String) = settingsVM.setOllamaModel(modelId)
    fun refreshOllamaModels() = settingsVM.refreshOllamaModels()
    fun setOpenCodeZenApiKey(key: String) = settingsVM.setOpenCodeZenApiKey(key)
    fun setOpenCodeZenEndpoint(endpoint: String) = settingsVM.setOpenCodeZenEndpoint(endpoint)
    fun setOpenCodeZenModel(modelId: String) = settingsVM.setOpenCodeZenModel(modelId)
    fun refreshOpenCodeZenGateway() = settingsVM.refreshOpenCodeZenGateway()
    // -- Personalization delegations --
    val userPreferredName get() = settingsVM.userPreferredName
    val userDataContext get() = settingsVM.userDataContext
    val agentResponseStyle get() = settingsVM.agentResponseStyle
    val agentCustomBehaviorInstructions get() = settingsVM.agentCustomBehaviorInstructions
    fun setUserPreferredName(name: String) = settingsVM.setUserPreferredName(name)
    fun setUserDataContext(ctx: String) = settingsVM.setUserDataContext(ctx)
    fun setAgentResponseStyle(style: String) = settingsVM.setAgentResponseStyle(style)
    fun setAgentCustomBehaviorInstructions(instr: String) = settingsVM.setAgentCustomBehaviorInstructions(instr)
    // -- Workspace delegations --
    val workspaceProjects get() = settingsVM.workspaceProjects
    val workspaceError get() = settingsVM.workspaceError
    fun clearWorkspaceError() = settingsVM.clearWorkspaceError()
    fun createWorkspace(name: String, instr: String = "") = settingsVM.createWorkspace(name, instr)
    fun deleteWorkspace(project: WorkspaceProject) = settingsVM.deleteWorkspace(project)
    fun updateWorkspace(project: WorkspaceProject) = settingsVM.updateWorkspace(project)
    val activeWorkspace get() = chatVM.activeWorkspace
    fun setActiveWorkspace(project: WorkspaceProject?) = chatVM.setActiveWorkspace(project)

    fun installSkill(skill: com.pocketclaw.claw.skills.CustomSkill) = settingsVM.installSkill(skill)
    fun deleteSkill(skill: com.pocketclaw.claw.skills.CustomSkill) = settingsVM.deleteSkill(skill)
    fun toggleSkill(skill: com.pocketclaw.claw.skills.CustomSkill) = settingsVM.toggleSkill(skill)
    fun createTask(name: String, hour: Int, minute: Int, repeating: Boolean) = settingsVM.createTask(name, hour, minute, repeating)
    fun deleteTask(task: com.pocketclaw.app.data.ScheduledTask) = settingsVM.deleteTask(task)
    fun selectDeviceProfile(profile: com.pocketclaw.app.ui.settings.DeviceProfile) = settingsVM.selectDeviceProfile(profile)
    fun toggleTask(task: com.pocketclaw.app.data.ScheduledTask) = settingsVM.toggleTask(task)

    // =====================================================================
    // 6. MODEL LOADING / SWITCHING (cross-cutting orchestration)
    // =====================================================================

    fun selectModel(model: LLMModel) {
        viewModelScope.launch {
            try {
                // Release previous backend's native memory before loading new model
                chatVM.releaseCurrentBackend()
                val success = inferenceService.loadModel(model)
                if (success) {
                    applyModelLoaded(model)
                    settingsVM.switchLlmMode("local")
                    Log.d(TAG, "Switched to model: ${model.name}")
                } else {
                    Log.w(TAG, "Failed to load model: ${model.name}")
                    chatVM.addSystemMessage("Fehler: Modell ${model.name} konnte nicht geladen werden.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model switch failed: ${e.message}", e)
                chatVM.addSystemMessage("Fehler beim Wechseln: ${e.message}")
            }
        }
    }

    fun loadLocalModel(model: LLMModel) {
        viewModelScope.launch {
            downloadVM.setModelLoading(true)
            downloadVM.setLastError(null)
            // Release previous backend's native memory before loading new model
            chatVM.releaseCurrentBackend()
            var gpuFailed = false
            try {
                val success = inferenceService.loadModel(model, com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend.GPU)
                if (success) {
                    applyModelLoaded(model)
                    return@launch
                } else {
                    gpuFailed = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "GPU load failed for ${model.name}: ${e.message}")
                gpuFailed = true
            }
            if (gpuFailed) {
                try {
                    val cpuSuccess = inferenceService.loadModel(model, com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend.CPU)
                    if (cpuSuccess) {
                        applyModelLoaded(model, suffix = " (CPU-Modus)")
                        return@launch
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "CPU load also failed for ${model.name}: ${e2.message}")
                }
            }
            val errorMsg = "❌ ${model.name} konnte nicht geladen werden. " +
                "Das Modell (${model.sizeBytes / 1_000_000} MB, ${model.modelFormat.uppercase()}) ist möglicherweise nicht kompatibel mit deinem Gerät. " +
                "Versuche ein anderes Modell oder nutze OpenRouter/Ollama."
            downloadVM.setLastError(errorMsg)
            chatVM.addSystemMessage(errorMsg)
            downloadVM.setModelLoading(false)
        }
    }

    private suspend fun applyModelLoaded(model: LLMModel, suffix: String = "") {
        currentModel = model
        chatVM.setCurrentModel(model)
        downloadVM.setCurrentModelName(model.name)
        downloadVM.setModelLoaded(true)
        settingsVM.switchLlmMode("local")
        loadAvailableModels()
        chatVM.addSystemMessage("✅ ${model.name} geladen${suffix}! Modell ist bereit.")
    }

    private fun loadAvailableModels() {
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    ModelRepository.getAvailableModels(app)
                }
                downloadVM.updateAvailableModels(models)
                val excludedFormats = setOf("embedding", "groq", "tflite", "qnn_npu", "mnn_cpu")
                val excludedCategories = setOf("embedding", "image_generation", "multimodal")
                val allLocal = com.llmhub.llmhub.data.ModelData.models.filter {
                    it.category !in excludedCategories && it.modelFormat !in excludedFormats
                }
                val enriched = allLocal.map { model ->
                    val downloaded = models.find { it.name == model.name }?.isDownloaded ?: false
                    model.copy(isDownloaded = downloaded)
                }
                downloadVM.updateAllLocalModels(enriched)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load available models: ${e.message}", e)
            }
        }
    }

    private fun loadFirstAvailableModel() {
        viewModelScope.launch {
            try {
                // OpenRouter/Ollama are initialized in SettingsViewModel; no Groq service needed

                val models = withContext(Dispatchers.IO) {
                    ModelRepository.getAvailableModels(app)
                }
                val downloaded = models.filter { it.isDownloaded }
                if (downloaded.isNotEmpty()) {
                    val model = downloaded.first()
                    currentModel = model
                    chatVM.setCurrentModel(model)
                    downloadVM.setCurrentModelName(model.name)
                    Log.d(TAG, "Loading model: ${model.name}")
                    val ok = inferenceService.loadModel(model)
                    downloadVM.setModelLoaded(ok)
                    if (ok) Log.d(TAG, "Model loaded: ${model.name}")
                    else Log.w(TAG, "Failed to load model: ${model.name}")
                } else {
                    Log.w(TAG, "No downloaded models found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model loading failed: ${e.message}", e)
            }
        }
    }

    fun deleteLocalModel(model: LLMModel) {
        downloadVM.deleteLocalModel(model)
        if (currentModel?.name == model.name) {
            currentModel = null
            chatVM.setCurrentModel(null)
        }
    }

    fun downloadModel(model: LLMModel) {
        downloadVM.downloadModel(model) { loadFirstAvailableModel() }
    }

    fun downloadQwenModel() {
        downloadVM.downloadQwenModel { loadFirstAvailableModel() }
    }

    fun downloadDeviceModels(models: List<LLMModel>) {
        downloadVM.downloadDeviceModels(models) { loadFirstAvailableModel() }
    }
}
