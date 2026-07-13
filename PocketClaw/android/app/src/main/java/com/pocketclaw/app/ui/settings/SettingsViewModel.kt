package com.pocketclaw.app.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.inference.InferenceService
import com.pocketclaw.app.PocketClawApplication
import com.pocketclaw.app.api.OllamaProvider
import com.pocketclaw.app.api.OpenCodeZenProvider
import com.pocketclaw.app.api.OpenRouterProvider
import com.pocketclaw.app.data.Preferences
import com.pocketclaw.app.data.WorkspaceProject
import com.pocketclaw.app.data.ScheduledTask
import com.pocketclaw.claw.security.AuditLog
import com.pocketclaw.claw.skills.CustomSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single Responsibility: App settings – LLM mode switching, cloud API config (OpenRouter/Ollama),
 * Skills & Tasks CRUD, token tracking, and audit log.
 * Extracted from MainViewModel (SOLID refactoring step 3 – code-refactoring-refactor-clean).
 *
 * Owns: llmMode, llmReady, selectedOpenRouterModel, ollamaModel state,
 *       customSkills, scheduledTasks, skillUsage, token counters, auditEntries,
 *       and all settings mutations (switchLlmMode, setOpenRouterApiKey, CRUD methods).
 *
 * Reads DownloadVM state (isModelLoaded) for the llmReady combinator.
 * Communicates with ChatViewModel via addSystemMessage callback for install feedback.
 * Communicates with DownloadVM for model name updates during mode switches.
 */
enum class DeviceProfile(val label: String) {
    NONE(""),
    GALAXY_NOTE_20("Galaxy Note 20"),
    XIAOMI_13("Xiaomi 13"),
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsVM"
        private const val WORKSPACE_TAG = "PocketClaw:Workspace"
    }

    private val app = application as PocketClawApplication
    private val downloadVM = app.downloadViewModel
    private val inferenceService: InferenceService = app.inferenceService

    /** Callback to inject system messages into the chat. */
    var onSystemMessage: ((String) -> Unit)? = null

    /** Callback to trigger model loading when switching to local mode. */
    var onLoadFirstAvailableModel: (() -> Unit)? = null

    /** Callback invoked after a workspace is deleted; wires to ChatVM for active cleanup. */
    var onWorkspaceDeleted: ((WorkspaceProject) -> Unit)? = null

    // -- LLM Mode ------------------------------------------------

    private val _llmMode = MutableStateFlow(Preferences.llmMode)
    val llmMode: StateFlow<String> = _llmMode.asStateFlow()

    /**
     * Whether the currently selected LLM mode is configured and ready to serve.
     *
     * - "openrouter": Requires a non-blank API key.
     * - "ollama": Requires a reachable Ollama server.
     * - "local": Requires a loaded on-device model.
     */
    val llmReady: StateFlow<Boolean> = combine(downloadVM.isModelLoaded, _llmMode) { loaded, mode ->
        when (mode) {
            "openrouter"   -> OpenRouterProvider.isReady()
            "ollama"       -> OllamaProvider.isReady()
            "opencode_zen" -> OpenCodeZenProvider.isReady()
            else           -> loaded
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // -- OpenRouter Config ------------------------------------------------

    private val _selectedOpenRouterModel = MutableStateFlow(Preferences.openRouterSelectedModel)
    val selectedOpenRouterModel: StateFlow<String> = _selectedOpenRouterModel.asStateFlow()

    // -- Ollama Config ------------------------------------------------

    private val _ollamaHost = MutableStateFlow(Preferences.ollamaHost)
    val ollamaHost: StateFlow<String> = _ollamaHost.asStateFlow()

    private val _ollamaModels = MutableStateFlow<List<String>>(emptyList())
    val ollamaModels: StateFlow<List<String>> = _ollamaModels.asStateFlow()

    private val _ollamaConnected = MutableStateFlow(false)
    val ollamaConnected: StateFlow<Boolean> = _ollamaConnected.asStateFlow()

    // -- OpenCode Zen Config

    private val _selectedOpenCodeZenModel = MutableStateFlow(Preferences.openCodeZenSelectedModel)
    val selectedOpenCodeZenModel: StateFlow<String> = _selectedOpenCodeZenModel.asStateFlow()

    private val _openCodeZenEndpoint = MutableStateFlow(Preferences.openCodeZenEndpoint)
    val openCodeZenEndpoint: StateFlow<String> = _openCodeZenEndpoint.asStateFlow()

    private val _openCodeZenConnected = MutableStateFlow(false)
    val openCodeZenConnected: StateFlow<Boolean> = _openCodeZenConnected.asStateFlow()

    // -- Agent Personalization ------------------------------------------------

    private val _userPreferredName = MutableStateFlow(Preferences.userPreferredName)
    val userPreferredName: StateFlow<String> = _userPreferredName.asStateFlow()

    private val _userDataContext = MutableStateFlow(Preferences.userDataContext)
    val userDataContext: StateFlow<String> = _userDataContext.asStateFlow()

    private val _agentResponseStyle = MutableStateFlow(Preferences.agentResponseStyle)
    val agentResponseStyle: StateFlow<String> = _agentResponseStyle.asStateFlow()

    private val _agentCustomBehaviorInstructions = MutableStateFlow(Preferences.agentCustomBehaviorInstructions)
    val agentCustomBehaviorInstructions: StateFlow<String> = _agentCustomBehaviorInstructions.asStateFlow()

    // -- Workspace Projects ------------------------------------------------

    val workspaceProjects: StateFlow<List<WorkspaceProject>> = app.database.workspaceDao().allProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // -- Qwen Quick-Download State ------------------------------------------------

    private val _qwenDownloaded = MutableStateFlow(false)
    val qwenDownloaded: StateFlow<Boolean> = _qwenDownloaded.asStateFlow()
    private val _qwenDownloading = MutableStateFlow(false)
    val qwenDownloading: StateFlow<Boolean> = _qwenDownloading.asStateFlow()
    private val _qwenProgress = MutableStateFlow(0f)
    val qwenProgress: StateFlow<Float> = _qwenProgress.asStateFlow()

    // -- Skills ------------------------------------------------

    val customSkills: StateFlow<List<CustomSkill>> = app.database.customSkillDao().allSkills()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val skillUsage: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())

    // -- Tasks ------------------------------------------------

    val scheduledTasks: StateFlow<List<ScheduledTask>> = app.database.scheduledTaskDao().allTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // -- Analytics & Audit ------------------------------------------------

    private val _totalTokensUsed = MutableStateFlow(0)
    val totalTokensUsed: StateFlow<Int> = _totalTokensUsed.asStateFlow()
    private val _sessionTokensUsed = MutableStateFlow(0)
    val sessionTokensUsed: StateFlow<Int> = _sessionTokensUsed.asStateFlow()

    val auditEntries: List<AuditLog.Entry> get() = app.auditLog.recent()

    // -- Device Profile & Filtered Models (UNCHANGED – do not touch) ------------------------------------------------

    private val _selectedDeviceProfile = MutableStateFlow(DeviceProfile.NONE)
    val selectedDeviceProfile: StateFlow<DeviceProfile> = _selectedDeviceProfile.asStateFlow()

    fun selectDeviceProfile(profile: DeviceProfile) {
        _selectedDeviceProfile.value = if (_selectedDeviceProfile.value == profile) DeviceProfile.NONE else profile
        Log.d(TAG, "Device profile: ${_selectedDeviceProfile.value}")
    }

    val filteredModels: StateFlow<List<com.llmhub.llmhub.data.LLMModel>> = combine(
        downloadVM.allLocalModels,
        _selectedDeviceProfile,
    ) { models, profile ->
        when (profile) {
            DeviceProfile.NONE -> emptyList()
            DeviceProfile.GALAXY_NOTE_20 -> models.filter { m ->
                m.modelFormat.lowercase() == "task" &&
                    !m.modelFormat.lowercase().contains("litertlm")
            }.sortedByDescending { it.sizeBytes }
            DeviceProfile.XIAOMI_13 -> models.filter { m ->
                val fmt = m.modelFormat.lowercase()
                fmt == "litertlm" || fmt == "gguf" || fmt == "qnn_npu"
            }.sortedByDescending { it.sizeBytes }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // -- LLM Mode Switching ------------------------------------------------

    fun switchLlmMode(mode: String) {
        Preferences.llmMode = mode
        _llmMode.value = mode
        downloadVM.setLastError(null)
        when (mode) {
            "local" -> {
                downloadVM.setCurrentModelName(downloadVM.currentModelName.value.ifBlank { "No model" })
                if (!downloadVM.isModelLoaded.value) {
                    onLoadFirstAvailableModel?.invoke()
                }
            }
            "openrouter" -> {
                downloadVM.setCurrentModelName("OpenRouter: ${Preferences.openRouterSelectedModel}")
            }
            "ollama" -> {
                downloadVM.setCurrentModelName("Ollama: ${Preferences.ollamaSelectedModel.ifBlank { "Kein Modell" }}")
                refreshOllamaModels()
            }
            "opencode_zen" -> {
                downloadVM.setCurrentModelName("OpenCode Zen: ${Preferences.openCodeZenSelectedModel}")
                refreshOpenCodeZenGateway()
            }
        }
        Log.d(TAG, "Switched LLM mode to: $mode")
    }

    // -- OpenRouter Config ------------------------------------------------

    fun setOpenRouterApiKey(key: String) {
        Preferences.openRouterApiKey = key
        Log.d(TAG, "OpenRouter API key saved")
    }

    fun setOpenRouterModel(modelId: String) {
        _selectedOpenRouterModel.value = modelId
        Preferences.openRouterSelectedModel = modelId
        Log.d(TAG, "OpenRouter model set to: $modelId")
    }

    // -- Ollama Config ------------------------------------------------

    fun setOllamaHost(host: String) {
        Preferences.ollamaHost = host
        _ollamaHost.value = host
        Log.d(TAG, "Ollama host set to: $host")
        refreshOllamaModels()
    }

    fun setOllamaModel(model: String) {
        Preferences.ollamaSelectedModel = model
        Log.d(TAG, "Ollama model set to: $model")
    }

    fun refreshOllamaModels() {
        viewModelScope.launch {
            try {
                val connected = OllamaProvider.checkConnection()
                _ollamaConnected.value = connected
                if (connected) {
                    val models = OllamaProvider.fetchAvailableModels()
                    _ollamaModels.value = models
                    Log.d(TAG, "Ollama models: ${models.size}")
                } else {
                    _ollamaModels.value = emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ollama connection failed: ${e.message}")
                _ollamaConnected.value = false
                _ollamaModels.value = emptyList()
            }
        }
    }

    // -- OpenCode Zen Config ------------------------------------------------

    fun setOpenCodeZenApiKey(key: String) {
        Preferences.openCodeZenApiKey = key
        Log.d(TAG, "OpenCode Zen API key saved")
    }

    fun setOpenCodeZenEndpoint(endpoint: String) {
        Preferences.openCodeZenEndpoint = endpoint
        _openCodeZenEndpoint.value = endpoint
        Log.d(TAG, "OpenCode Zen endpoint set to: $endpoint")
    }

    fun setOpenCodeZenModel(modelId: String) {
        _selectedOpenCodeZenModel.value = modelId
        Preferences.openCodeZenSelectedModel = modelId
        Log.d(TAG, "OpenCode Zen model set to: $modelId")
    }

    fun refreshOpenCodeZenGateway() {
        viewModelScope.launch {
            try {
                val connected = OpenCodeZenProvider.checkGateway()
                _openCodeZenConnected.value = connected
                Log.d(TAG, "OpenCode Zen gateway: connected=$connected")
            } catch (e: Exception) {
                Log.e(TAG, "OpenCode Zen gateway check failed: ${e.message}")
                _openCodeZenConnected.value = false
            }
        }
    }

    // -- Personalization Mutators ------------------------------------------------

    fun setUserPreferredName(name: String) {
        Preferences.userPreferredName = name
        _userPreferredName.value = name
        Log.d(TAG, "User preferred name set to: $name")
    }

    fun setUserDataContext(context: String) {
        Preferences.userDataContext = context
        _userDataContext.value = context
        Log.d(TAG, "User data context updated")
    }

    fun setAgentResponseStyle(style: String) {
        Preferences.agentResponseStyle = style
        _agentResponseStyle.value = style
        Log.d(TAG, "Agent response style set to: $style")
    }

    fun setAgentCustomBehaviorInstructions(instructions: String) {
        Preferences.agentCustomBehaviorInstructions = instructions
        _agentCustomBehaviorInstructions.value = instructions
        Log.d(TAG, "Agent custom instructions updated")
    }

    // -- Workspace Error State ------------------------------------------------

    private val _workspaceError = MutableStateFlow<String?>(null)
    val workspaceError: StateFlow<String?> = _workspaceError.asStateFlow()

    fun clearWorkspaceError() { _workspaceError.value = null }

    // -- Workspace CRUD ------------------------------------------------

    fun createWorkspace(name: String, instructions: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val id = "ws_${System.currentTimeMillis()}"
            val baseDir = app.getExternalFilesDir(null) ?: app.filesDir
            val workspacesDir = File(baseDir, "PocketClaw/Workspaces/${name.sanitizeForFolder()}")
            if (!workspacesDir.exists()) {
                val success = workspacesDir.mkdirs()
                if (!success) {
                    Log.e(WORKSPACE_TAG, "Failed to create workspace dir: ${workspacesDir.absolutePath}")
                    _workspaceError.value = "Speicherplatz oder Berechtigung fehlt – Workspace konnte nicht erstellt werden."
                    return@launch
                }
            }
            Log.d(WORKSPACE_TAG, "Workspace dir created: ${workspacesDir.absolutePath}")
            val project = WorkspaceProject(
                id = id,
                name = name,
                localFolderPath = workspacesDir.absolutePath,
                projectInstructions = instructions,
            )
            app.database.workspaceDao().upsert(project)
            Log.d(WORKSPACE_TAG, "Workspace registered: name=$name, id=$id, path=${workspacesDir.absolutePath}")
        }
    }

    fun deleteWorkspace(project: WorkspaceProject) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Delete Room entry
            app.database.workspaceDao().delete(project.id)
            Log.d(WORKSPACE_TAG, "Room entry deleted: id=${project.id}, name=${project.name}")

            // 2. Path traversal guard: resolve to canonical path and verify containment
            if (project.localFolderPath.isBlank()) return@launch
            try {
                val targetDir = File(project.localFolderPath).canonicalFile
                val workspacesRoot = File(
                    (app.getExternalFilesDir(null) ?: app.filesDir),
                    "PocketClaw/Workspaces"
                ).canonicalFile

                if (!targetDir.path.startsWith(workspacesRoot.path)) {
                    Log.e(WORKSPACE_TAG, "Path traversal blocked: ${project.localFolderPath} escapes workspace root")
                    _workspaceError.value = "Sicherheitsfehler – ungültiger Workspace-Pfad."
                    return@launch
                }

                if (targetDir.exists()) {
                    val deleted = targetDir.deleteRecursively()
                    if (deleted) {
                        Log.d(WORKSPACE_TAG, "Physical folder deleted: ${targetDir.absolutePath}")
                    } else {
                        Log.e(WORKSPACE_TAG, "deleteRecursively() returned false for: ${targetDir.absolutePath}")
                        _workspaceError.value = "Dateien konnten nicht vollständig gelöscht werden."
                    }
                } else {
                    Log.d(WORKSPACE_TAG, "Folder already absent: ${targetDir.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(WORKSPACE_TAG, "Physical deletion failed: ${e.message}", e)
                _workspaceError.value = "Fehler beim Löschen der Workspace-Dateien: ${e.message}"
            }

            // 3. Notify MainVM to clear active workspace if needed
            onWorkspaceDeleted?.invoke(project)
        }
    }

    fun updateWorkspace(project: WorkspaceProject) {
        viewModelScope.launch(Dispatchers.IO) { app.database.workspaceDao().upsert(project) }
    }

    // -- Skills CRUD ------------------------------------------------

    fun installSkill(skill: CustomSkill) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.database.customSkillDao().upsert(skill)
                }
                onSystemMessage?.invoke("Skill installed: ${skill.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Skill install failed: ${e.message}", e)
            }
        }
    }

    fun deleteSkill(skill: CustomSkill) {
        viewModelScope.launch(Dispatchers.IO) { app.database.customSkillDao().delete(skill.id) }
    }

    fun toggleSkill(skill: CustomSkill) {
        viewModelScope.launch(Dispatchers.IO) {
            app.database.customSkillDao().setEnabled(skill.id, !skill.enabled)
        }
    }

    // -- Tasks CRUD ------------------------------------------------

    fun createTask(name: String, hour: Int, minute: Int, repeating: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val task = ScheduledTask(
                name = name, action = "remind", hour = hour, minute = minute,
                enabled = true, repeating = repeating,
            )
            app.database.scheduledTaskDao().upsert(task)
        }
    }

    fun deleteTask(task: ScheduledTask) {
        viewModelScope.launch(Dispatchers.IO) { app.database.scheduledTaskDao().delete(task.id) }
    }

    fun toggleTask(task: ScheduledTask) {
        viewModelScope.launch(Dispatchers.IO) {
            app.database.scheduledTaskDao().setEnabled(task.id, !task.enabled)
        }
    }

    // -- Initialization ------------------------------------------------

    fun initialize() {
        _selectedOpenRouterModel.value = Preferences.openRouterSelectedModel
        _ollamaHost.value = Preferences.ollamaHost

        when (Preferences.llmMode) {
            "openrouter" -> {
                if (OpenRouterProvider.isReady()) {
                    downloadVM.setCurrentModelName("OpenRouter: ${Preferences.openRouterSelectedModel}")
                } else {
                    _llmMode.value = "local"
                    Preferences.llmMode = "local"
                    onLoadFirstAvailableModel?.invoke()
                }
            }
            "ollama" -> {
                downloadVM.setCurrentModelName("Ollama: ${Preferences.ollamaSelectedModel.ifBlank { "Kein Modell" }}")
                refreshOllamaModels()
            }
            "opencode_zen" -> {
                downloadVM.setCurrentModelName("OpenCode Zen: ${Preferences.openCodeZenSelectedModel}")
                refreshOpenCodeZenGateway()
            }
            "local" -> {
                onLoadFirstAvailableModel?.invoke()
            }
            else -> {
                downloadVM.setCurrentModelName("Kein Backend konfiguriert")
            }
        }
    }
}

/** Converts a string into a safe directory name: spaces→underscores, strips unsafe chars. */
private fun String.sanitizeForFolder(): String =
    replace(Regex("[^a-zA-Z0-9._\-]"), "_").trim('_').ifBlank { "untitled" }
