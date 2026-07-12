package com.pocketclaw.app.ui

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelRepository
import com.llmhub.llmhub.data.localFileName
import com.llmhub.llmhub.inference.InferenceService
import com.llmhub.llmhub.repository.ChatRepository
import com.llmhub.llmhub.ui.components.TtsService
import com.pocketclaw.app.PocketClawApplication
import com.pocketclaw.app.api.DashScopeProvider
import com.pocketclaw.app.data.Preferences
import com.pocketclaw.app.data.ScheduledTask
import com.pocketclaw.claw.bond.BondEngine
import com.pocketclaw.claw.bond.BondGrowth
import com.pocketclaw.claw.bond.BondMemory
import com.pocketclaw.claw.prompt.ContextBudget
import com.pocketclaw.claw.prompt.PromptAssembler
import com.pocketclaw.claw.prompt.SkillsPrompt
import com.pocketclaw.claw.prompt.ToolContext
import com.pocketclaw.claw.security.AuditLog
import com.pocketclaw.claw.security.RateLimiter
import com.pocketclaw.app.ui.components.ToolConfirmState
import com.pocketclaw.claw.skills.CustomSkill
import com.pocketclaw.claw.skills.SkillRouter
import com.pocketclaw.claw.tools.ToolExecutor
import com.pocketclaw.claw.tools.ToolParser
import com.pocketclaw.claw.tools.ToolRegistry
import com.pocketclaw.claw.tools.ToolResult
import com.pocketclaw.app.ui.chat.ChatMessage
import com.llmhub.llmhub.data.ModelDownloader
import com.pocketclaw.app.BuildConfig
import com.llmhub.llmhub.data.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Stable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel(application: Application) : AndroidViewModel(application) {

    /** Ensures only one model download runs at a time (coroutines-expert pattern) */

    companion object {
        private const val TAG = "MainVM"
        private const val MAX_TOOL_CALLS_PER_TURN = 3
        private const val TOOL_TIMEOUT_MS = 30_000L
    }

    private val app = application as PocketClawApplication
    private val downloadVM = app.downloadViewModel
    private val inferenceService: InferenceService = app.inferenceService
    private val chatRepository: ChatRepository = app.chatRepository
    private val bondEngine: BondEngine = app.bondEngine
    private val toolExecutor: ToolExecutor = app.toolExecutor
    private val ttsService = TtsService(application)

    private var currentModel: LLMModel? = null
    private var currentChatId: String? = null

    private val recentToolResults = ArrayDeque<ToolContext>(5)
    private val _pendingConfirm = MutableStateFlow<String?>(null)
    val pendingConfirm: StateFlow<String?> = _pendingConfirm.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    val isModelLoaded get() = downloadVM.isModelLoaded

    val currentModelName get() = downloadVM.currentModelName

    val availableModels get() = downloadVM.availableModels

    val allLocalModels get() = downloadVM.allLocalModels

    private val _llmMode = MutableStateFlow(Preferences.llmMode)
    val llmMode: StateFlow<String> = _llmMode.asStateFlow()

    val llmReady: StateFlow<Boolean> = combine(_isModelLoaded, _llmMode) { loaded, mode ->
        when (mode) {
            "groq" -> Preferences.groqApiKey.isNotBlank()
            "api" -> DashScopeProvider.isReady
            else -> loaded
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val memories: StateFlow<List<BondMemory>> = app.database.bondMemoryDao().allMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val growthState: StateFlow<BondGrowth?> = bondEngine.growthDao.observeGrowth()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val customSkills: StateFlow<List<CustomSkill>> = app.database.customSkillDao().allSkills()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scheduledTasks: StateFlow<List<ScheduledTask>> = app.database.scheduledTaskDao().allTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val growthStage: StateFlow<Int> = growthState.map { it?.stage ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val interactionCount: StateFlow<Int> = growthState.map { it?.totalInteractions ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val skillUsage: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())

    private val _qwenDownloaded = MutableStateFlow(false)
    val qwenDownloaded: StateFlow<Boolean> = _qwenDownloaded.asStateFlow()
    private val _qwenDownloading = MutableStateFlow(false)
    val qwenDownloading: StateFlow<Boolean> = _qwenDownloading.asStateFlow()
    private val _qwenProgress = MutableStateFlow(0f)
    val qwenProgress: StateFlow<Float> = _qwenProgress.asStateFlow()

    private val _selectedGroqModel = MutableStateFlow("llama-3.3-70b-versatile")
    val selectedGroqModel: StateFlow<String> = _selectedGroqModel.asStateFlow()

    // Generic model download state
    @Stable
    data class ModelDownloadState(
        val isDownloading: Boolean = false,
        val progress: Float = 0f,
        val downloaded: Boolean = false,
        val modelId: String = "",
    )
    val modelDownloads get() = downloadVM.modelDownloads

    // Animation triggers
    val modelLoading get() = downloadVM.modelLoading

    val lastError get() = downloadVM.lastError

    // Token tracking for cost awareness
    private val _totalTokensUsed = MutableStateFlow(0)
    val totalTokensUsed: StateFlow<Int> = _totalTokensUsed.asStateFlow()
    private val _sessionTokensUsed = MutableStateFlow(0)
    val sessionTokensUsed: StateFlow<Int> = _sessionTokensUsed.asStateFlow()

    val auditEntries: List<AuditLog.Entry> get() = app.auditLog.recent()

    init {
        loadAvailableModels()
        // Auto-set Groq model selection
        _selectedGroqModel.value = Preferences.groqSelectedModel
        if (Preferences.groqApiKey.isNotBlank()) {
            // Groq key exists - use groq cloud mode
            _llmMode.value = "groq"
            Preferences.llmMode = "groq"
            _currentModelName.value = "Groq: ${Preferences.groqSelectedModel}"
            val unified = inferenceService as? com.llmhub.llmhub.inference.UnifiedInferenceService
            unified?.groqService?.setApiKey(Preferences.groqApiKey)
        } else if (Preferences.llmMode == "local") {
            loadFirstAvailableModel()
        } else {
            _currentModelName.value = "Cloud (Qwen)"
        }
        initializeChat()
    }

    private fun loadFirstAvailableModel() {
        viewModelScope.launch {
            try {
                // Initialize Groq API key + model selection
                val unified = inferenceService as? com.llmhub.llmhub.inference.UnifiedInferenceService
                if (Preferences.groqApiKey.isNotBlank()) {
                    unified?.groqService?.setApiKey(Preferences.groqApiKey)
                }
                _selectedGroqModel.value = Preferences.groqSelectedModel

                val models = withContext(Dispatchers.IO) {
                    ModelRepository.getAvailableModels(app)
                }
                val downloaded = models.filter { it.isDownloaded }
                if (downloaded.isNotEmpty()) {
                    val model = downloaded.first()
                    currentModel = model
                    _currentModelName.value = model.name
                    Log.d(TAG, "Loading model: ${model.name}")
                    val ok = inferenceService.loadModel(model)
                    _isModelLoaded.value = ok
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

    private fun loadAvailableModels() {
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    ModelRepository.getAvailableModels(app)
                }
                _availableModels.value = models
                // Also load ALL local models (downloaded + available)
                // Show ALL text-capable models for download
                val excludedFormats = setOf("embedding", "groq", "tflite", "qnn_npu", "mnn_cpu")
                val excludedCategories = setOf("embedding", "image_generation", "multimodal")
                val allLocal = com.llmhub.llmhub.data.ModelData.models.filter {
                    it.category !in excludedCategories && it.modelFormat !in excludedFormats
                }
                // Merge download status
                val enriched = allLocal.map { model ->
                    val downloaded = models.find { it.name == model.name }?.isDownloaded ?: false
                    model.copy(isDownloaded = downloaded)
                }
                _allLocalModels.value = enriched
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load available models: ${e.message}", e)
            }
        }
    }

    fun selectModel(model: LLMModel) {
        viewModelScope.launch {
            try {
                _isProcessing.value = true
                val success = inferenceService.loadModel(model)
                if (success) {
                    currentModel = model
                    _currentModelName.value = model.name
                    _isModelLoaded.value = true
                    if (model.modelFormat == "groq") {
                        _llmMode.value = "groq"
                        Preferences.llmMode = "groq"
                        // Update Groq selected model if model has groqModelId
                        model.groqModelId?.let {
                            _selectedGroqModel.value = it
                            Preferences.groqSelectedModel = it
                        }
                    } else {
                        _llmMode.value = "local"
                        Preferences.llmMode = "local"
                    }
                    Log.d(TAG, "Switched to model: ${model.name}")
                } else {
                    Log.w(TAG, "Failed to load model: ${model.name}")
                    _messages.update { it + ChatMessage(text = "Fehler: Modell ${model.name} konnte nicht geladen werden.", isUser = false) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model switch failed: ${e.message}", e)
                _messages.update { it + ChatMessage(text = "Fehler beim Wechseln: ${e.message}", isUser = false) }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun setGroqApiKey(key: String) {
        Preferences.groqApiKey = key
        val unified = app.inferenceService as? com.llmhub.llmhub.inference.UnifiedInferenceService
        unified?.groqService?.setApiKey(key)
        Log.d(TAG, "Groq API key saved and set")
    }

    fun setGroqModel(modelId: String) {
        _selectedGroqModel.value = modelId
        Preferences.groqSelectedModel = modelId
        Log.d(TAG, "Groq model set to: $modelId")
    }

    fun downloadQwenModel() {
        downloadVM.downloadQwenModel { dm ->
            loadFirstAvailableModel()
        }
    }

    fun downloadModel(model: LLMModel) {
        downloadVM.downloadModel(model) { dm ->
            loadFirstAvailableModel()
        }
    }

    fun loadLocalModel(model: LLMModel) {
        viewModelScope.launch {
            _modelLoading.value = true
            _lastError.value = null
            var gpuFailed = false
            try {
                val success = inferenceService.loadModel(model, com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend.GPU)
                if (success) {
                    currentModel = model
                    _currentModelName.value = model.name
                    _isModelLoaded.value = true
                    _llmMode.value = "local"
                    Preferences.llmMode = "local"
                    loadAvailableModels()
                    _messages.update { it + ChatMessage(text = "✅ ${model.name} geladen! Modell ist bereit.", isUser = false) }
                    return@launch
                } else {
                    gpuFailed = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "GPU load failed for ${model.name}: ${e.message}")
                gpuFailed = true
            }
            // Fallback to CPU
            if (gpuFailed) {
                try {
                    val cpuSuccess = inferenceService.loadModel(model, com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend.CPU)
                    if (cpuSuccess) {
                        currentModel = model
                        _currentModelName.value = model.name
                        _isModelLoaded.value = true
                        _llmMode.value = "local"
                        Preferences.llmMode = "local"
                        loadAvailableModels()
                        _messages.update { it + ChatMessage(text = "✅ ${model.name} geladen (CPU-Modus)!", isUser = false) }
                        return@launch
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "CPU load also failed for ${model.name}: ${e2.message}")
                }
            }
            // Both GPU and CPU failed
            val errorMsg = "❌ ${model.name} konnte nicht geladen werden. " +
                "Das Modell (${model.sizeBytes / 1_000_000} MB, ${model.modelFormat.uppercase()}) ist möglicherweise nicht kompatibel mit deinem Gerät. " +
                "Versuche ein anderes Modell oder nutze Groq Cloud."
            _lastError.value = errorMsg
            _messages.update { it + ChatMessage(text = errorMsg, isUser = false) }
            _modelLoading.value = false
        }
    }

    fun deleteLocalModel(model: LLMModel) {
        downloadVM.deleteLocalModel(model)
        if (currentModel?.name == model.name) {
            currentModel = null
        }
    }

    fun downloadDeviceModels(models: List<LLMModel>) {
        downloadVM.downloadDeviceModels(models) { dm ->
            loadFirstAvailableModel()
        }
    }

    fun installSkill(skill: CustomSkill) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.database.customSkillDao().upsert(skill)
                }
                _messages.update { it + ChatMessage(text = "Skill installed: ${skill.name}", isUser = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Skill install failed: ${e.message}", e)
            }
        }
    }

    fun switchToGroq() {
        _llmMode.value = "groq"
        Preferences.llmMode = "groq"
        val unified = inferenceService as? com.llmhub.llmhub.inference.UnifiedInferenceService
        unified?.groqService?.setApiKey(Preferences.groqApiKey)
    }

    private fun initializeChat() {
        viewModelScope.launch {
            try {
                val chatId = withContext(Dispatchers.IO) {
                    chatRepository.createNewChat("PocketClaw Chat", currentModel?.name ?: "cloud")
                }
                currentChatId = chatId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create chat: ${e.message}")
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val userMsg = ChatMessage(text = text.trim(), isUser = true)
        _messages.update { it + userMsg }
        _inputText.value = ""

        app.toolExecutor.resetTurn()
        toolCallCount = 0

        if (SkillRouter.isSkillCreationRequest(text)) {
            handleSkillCreation(text)
        } else {
            generateResponse(text.trim())
        }
    }

    private suspend fun buildAssembledPrompt(
        userText: String,
        toolResults: List<ToolContext> = recentToolResults.toList(),
    ): PromptAssembler.AssembledPrompt {
        // For Groq: include installed skills as context
        if (_llmMode.value == "groq") {
            val recentHistory = _messages.value.takeLast(15).map { msg ->
                PromptAssembler.ChatTurn(
                    role = if (msg.isUser) "user" else "assistant",
                    content = msg.text,
                )
            }
            val enabledSkills = withContext(Dispatchers.IO) {
                app.database.customSkillDao().enabledSkills()
            }
            val groqSkills = enabledSkills.map { s ->
                SkillsPrompt.SkillDef(
                    id = "custom_${s.id}", name = s.name, description = s.description,
                    exampleQ = s.exampleQuery, exampleA = s.exampleAnswer, isCustom = true,
                )
            }
            return PromptAssembler.assemble(
                memories = emptyList(),
                skills = groqSkills,
                recentHistory = recentHistory,
                userMessage = userText,
                growthStage = 0,
                toolResults = toolResults,
                budget = ContextBudget.forMode("groq"),
                includeToolInstructions = true,
            )
        }

        val bondMemories = withContext(Dispatchers.IO) { bondEngine.getMemoriesForPrompt() }
        val growthStageVal = withContext(Dispatchers.IO) { bondEngine.getGrowthStage() }
        val enabledSkills = withContext(Dispatchers.IO) {
            app.database.customSkillDao().enabledSkills()
        }
        val allSkills = SkillsPrompt.BUILTIN + enabledSkills.map { s ->
            SkillsPrompt.SkillDef(
                id = "custom_${s.id}", name = s.name, description = s.description,
                exampleQ = s.exampleQuery, exampleA = s.exampleAnswer, isCustom = true,
            )
        }

        val recentHistory = _messages.value.takeLast(15).map { msg ->
            PromptAssembler.ChatTurn(
                role = if (msg.isUser) "user" else "assistant",
                content = msg.text,
            )
        }

        val budget = ContextBudget.forMode(_llmMode.value)

        return PromptAssembler.assemble(
            memories = bondMemories,
            skills = allSkills,
            recentHistory = recentHistory,
            userMessage = userText,
            growthStage = growthStageVal,
            toolResults = toolResults,
            budget = budget,
        )
    }

    private fun generateResponse(userText: String) {
        _isProcessing.value = true
        viewModelScope.launch {
            try {
                val mode = _llmMode.value
                val assembled = buildAssembledPrompt(userText)

                val placeholderId = java.util.UUID.randomUUID().toString()
                _messages.update { it + ChatMessage(id = placeholderId, text = "", isUser = false) }

                val sb = StringBuilder()

                val genResult = withTimeoutOrNull(120_000L) { // 2 min timeout per request
                    if (mode == "groq") {
                        generateViaGroq(assembled, sb, placeholderId)
                    } else if (mode == "api") {
                        generateViaApi(assembled, sb, placeholderId)
                    } else {
                        generateViaLocal(assembled, userText, sb, placeholderId)
                    }
                }

                if (genResult == null && sb.isEmpty()) {
                    _messages.update { list ->
                        list.map { if (it.id == placeholderId) it.copy(text = "⏱️ Antwort-Timeout (2 Min.). Versuch es erneut.") else it }
                    }
                    return@launch
                }

                val rawOutput = sb.toString()

                val toolCalls = ToolParser.parse(rawOutput)
                if (mode == "groq" && toolCalls.isEmpty()) {
                    // Groq: no tool calls found, show directly
                    _messages.update { list ->
                        list.map { if (it.id == placeholderId) it.copy(text = rawOutput) else it }
                    }
                } else if (mode == "groq" && toolCalls.isNotEmpty()) {
                    // Groq: tool calls found, execute them
                    handleToolCalls(toolCalls, rawOutput, placeholderId, userText)
                } else {
                    if (toolCalls.isNotEmpty()) {
                        handleToolCalls(toolCalls, rawOutput, placeholderId, userText)
                    } else {
                        val cleaned = withContext(Dispatchers.IO) { bondEngine.processResponse(rawOutput) }
                        _messages.update { list ->
                            list.map { if (it.id == placeholderId) it.copy(text = cleaned) else it }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Generation failed: ${e.message}", e)
                _messages.update { it + ChatMessage(text = "Error: ${e.message}", isUser = false) }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private var toolCallCount = 0

    private suspend fun handleToolCalls(
        calls: List<ToolParser.ParsedCall>,
        rawOutput: String,
        placeholderId: String,
        userText: String,
    ) {
        toolCallCount++
        if (toolCallCount > MAX_TOOL_CALLS_PER_TURN) {
            Log.w(TAG, "Max tool calls ($MAX_TOOL_CALLS_PER_TURN) reached, stopping")
            _messages.update { list ->
                list.map { if (it.id == placeholderId) it.copy(text = "🔧 Tool-Limit erreicht ($MAX_TOOL_CALLS_PER_TURN/Antwort)") else it }
            }
            toolCallCount = 0
            return
        }

        val textPart = ToolParser.stripToolMarkers(rawOutput)
        val cleaned = withContext(Dispatchers.IO) { bondEngine.processResponse(textPart) }

        _messages.update { list ->
            list.map { if (it.id == placeholderId) it.copy(text = cleaned) else it }
        }

        val call = calls.first()
        Log.d(TAG, "Tool call #${toolCallCount}: ${call.toolId}(${call.args.take(60)})")

        val startTime = System.currentTimeMillis()
        val result = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
            toolExecutor.execute(call) { confirmMsg ->
                val deferred = ToolConfirmState.request(confirmMsg)
                deferred.await()
            }
        }
        val elapsed = System.currentTimeMillis() - startTime

        if (result == null) {
            Log.w(TAG, "Tool ${call.toolId} timed out after ${elapsed}ms")
            _messages.update { list ->
                list.map { if (it.id == placeholderId) it.copy(text = "⏱️ Tool ${call.toolId} hat zu lange gedauert (${elapsed}ms)") else it }
            }
            toolCallCount = 0
            return
        }

        Log.d(TAG, "Tool ${call.toolId} completed in ${elapsed}ms, success=${result.success}")

        val tool = ToolRegistry.get(call.toolId)
        val summary = tool?.summarize(result.output, 600) ?: result.output.take(600)

        val tc = ToolContext(call.toolId, call.args.take(40), summary)
        recentToolResults.addLast(tc)
        val budget = ContextBudget.forMode(_llmMode.value)
        while (recentToolResults.size > budget.maxToolResults) recentToolResults.removeFirst()

        if (result.success) {
            val secondPassId = java.util.UUID.randomUUID().toString()
            _messages.update { it + ChatMessage(id = secondPassId, text = "", isUser = false) }

            val sb2 = StringBuilder()
            val assembled2 = buildAssembledPrompt(userText, recentToolResults.toList())

            if (_llmMode.value == "groq") {
                generateViaGroq(assembled2, sb2, secondPassId)
            } else if (_llmMode.value == "api") {
                generateViaApi(assembled2, sb2, secondPassId)
            } else {
                generateViaLocal(assembled2, userText, sb2, secondPassId)
            }

            val secondRaw = sb2.toString()
            val secondCleaned = withContext(Dispatchers.IO) { bondEngine.processResponse(secondRaw) }
            _messages.update { list ->
                list.map { if (it.id == secondPassId) it.copy(text = secondCleaned) else it }
            }
        } else {
            _messages.update { it + ChatMessage(text = "Tool error: ${result.output}", isUser = false) }
        }
    }

    private suspend fun generateViaGroq(
        assembled: PromptAssembler.AssembledPrompt,
        sb: StringBuilder,
        placeholderId: String,
    ) {
        Log.d(TAG, "Groq prompt: ${assembled.userMessage.length} chars, model: ${Preferences.groqSelectedModel}")
        val unified = inferenceService as? com.llmhub.llmhub.inference.UnifiedInferenceService
        if (unified == null || Preferences.groqApiKey.isBlank()) {
            sb.append("Kein Groq API Key. Bitte in Einstellungen unter Groq Cloud eintragen.")
            _messages.update { list -> list.map { if (it.id == placeholderId) it.copy(text = sb.toString()) else it } }
            return
        }
        try {
            unified.groqService.setApiKey(Preferences.groqApiKey)
            val chatId = currentChatId ?: return
            val responseFlow = unified.groqService.generateResponseStreamWithAssembledPrompt(assembled)
            responseFlow.collect { chunk ->
                sb.append(chunk)
                val currentText = sb.toString()
                _messages.update { list -> list.map { if (it.id == placeholderId) it.copy(text = currentText) else it } }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq error: ${e.message}", e)
            sb.append("Groq Fehler: ${e.message}")
            _messages.update { list -> list.map { if (it.id == placeholderId) it.copy(text = sb.toString()) else it } }
        }
    }

    private suspend fun generateViaApi(
        assembled: PromptAssembler.AssembledPrompt,
        sb: StringBuilder,
        placeholderId: String,
    ) {
        val prompt = PromptAssembler.toGenericFormat(assembled)
        Log.d(TAG, "API prompt length: ${prompt.length} chars")

        // Check if Groq API key is set
        val unified = inferenceService as? com.llmhub.llmhub.inference.UnifiedInferenceService
        if (unified != null && Preferences.groqApiKey.isNotBlank()) {
            Log.d(TAG, "Using Groq Cloud API with model: ${Preferences.groqSelectedModel}")
            try {
                unified.groqService.setApiKey(Preferences.groqApiKey)
                val chatId = currentChatId ?: return
                val responseFlow = unified.groqService.generateResponseStreamWithAssembledPrompt(assembled)
                responseFlow.collect { chunk ->
                    sb.append(chunk)
                    val currentText = sb.toString()
                    _messages.update { list ->
                        list.map { if (it.id == placeholderId) it.copy(text = currentText) else it }
                    }
                }
                return
            } catch (e: Exception) {
                Log.e(TAG, "Groq API failed: ${e.message}", e)
                sb.append("\n\n[Groq Fehler: ${e.message} - Fallback zu DashScope]\n\n")
            }
        }

        // Fallback to DashScope
        Log.d(TAG, "Using DashScope API")
        DashScopeProvider.generateStream(assembled).collect { chunk ->
            sb.append(chunk)
            val currentText = sb.toString()
            _messages.update { list ->
                list.map { if (it.id == placeholderId) it.copy(text = currentText) else it }
            }
        }
    }

    private suspend fun generateViaLocal(
        assembled: PromptAssembler.AssembledPrompt,
        userText: String,
        sb: StringBuilder,
        placeholderId: String,
    ) {
        val model = currentModel
        if (model == null || !_isModelLoaded.value) {
            sb.append("No local model loaded. Switch to Cloud API or download a model.")
            _messages.update { list ->
                list.map { if (it.id == placeholderId) it.copy(text = sb.toString()) else it }
            }
            return
        }

        val prompt = PromptAssembler.toGenericFormat(assembled)
        Log.d(TAG, "Local prompt length: ${prompt.length} chars")

        val chatId = currentChatId ?: return
        val responseFlow = inferenceService.generateResponseStreamWithSession(prompt, model, chatId)

        responseFlow.collect { chunk ->
            sb.append(chunk)
            val currentText = sb.toString()
            _messages.update { list ->
                list.map { if (it.id == placeholderId) it.copy(text = currentText) else it }
            }
        }
    }

    private fun handleSkillCreation(text: String) {
        _isProcessing.value = true
        viewModelScope.launch {
            try {
                val skill = CustomSkill(
                    name = text.substringAfter("skill").substringAfter("技能").trim().take(30).ifBlank { "New Skill" },
                    description = text,
                    keywords = text.split(" ").filter { it.length > 2 }.take(5).joinToString(","),
                    exampleQuery = "",
                    exampleAnswer = "",
                )
                withContext(Dispatchers.IO) { app.database.customSkillDao().upsert(skill) }
                _messages.update { it + ChatMessage(text = "Skill added: ${skill.name}", isUser = false) }
            } catch (e: Exception) {
                _messages.update { it + ChatMessage(text = "Failed to add skill: ${e.message}", isUser = false) }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun newTopic() {
        _messages.value = emptyList()
        _inputText.value = ""
        recentToolResults.clear()
        initializeChat()
    }

    fun deleteMessage(message: ChatMessage) {
        _messages.update { list -> list.filter { it.id != message.id } }
    }

    fun speakMessage(text: String) {
        ttsService.speak(text)
    }

    private var speechRecognizer: android.speech.SpeechRecognizer? = null

    fun startRecording() {
        val ctx = getApplication<Application>()
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
            showToast("Speech recognition not available")
            return
        }
        _isRecording.value = true
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        _inputText.value = (_inputText.value + " " + text).trim()
                    }
                    _isRecording.value = false
                }
                override fun onError(error: Int) {
                    Log.w(TAG, "STT error: $error")
                    _isRecording.value = false
                }
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            startListening(intent)
        }
    }

    fun stopRecording() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isRecording.value = false
    }

    fun deleteMemory(memory: BondMemory) {
        viewModelScope.launch(Dispatchers.IO) { app.database.bondMemoryDao().delete(memory.id) }
    }

    fun deleteSkill(skill: CustomSkill) {
        viewModelScope.launch(Dispatchers.IO) { app.database.customSkillDao().delete(skill.id) }
    }

    fun toggleSkill(skill: CustomSkill) {
        viewModelScope.launch(Dispatchers.IO) {
            app.database.customSkillDao().setEnabled(skill.id, !skill.enabled)
        }
    }

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

    fun switchLlmMode(mode: String) {
        Preferences.llmMode = mode
        _llmMode.value = mode
        _lastError.value = null
        when (mode) {
            "local" -> {
                _currentModelName.value = currentModel?.name ?: "No model"
                if (currentModel == null) loadFirstAvailableModel()
            }
            "api" -> {
                _currentModelName.value = "Cloud (Qwen)"
            }
        }
        Log.d(TAG, "Switched LLM mode to: $mode")
    }

    /**
     * Validates a downloaded model file is a real binary, not an HTML error page.
     * Returns null if valid, or a human-readable error message.
     */

    private fun showToast(msg: String) {
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onCleared() {
        super.onCleared()
        ttsService.shutdown()
    }
}
