package com.pocketclaw.app.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.inference.InferenceService
import com.llmhub.llmhub.repository.ChatRepository
import com.pocketclaw.app.PocketClawApplication
import com.pocketclaw.app.api.CloudInferenceProvider
import com.pocketclaw.app.api.FileAttachment
import com.pocketclaw.app.data.Preferences
import com.pocketclaw.claw.bond.BondEngine
import com.pocketclaw.claw.prompt.ContextBudget
import com.pocketclaw.claw.prompt.PromptAssembler
import com.pocketclaw.claw.prompt.SkillsPrompt
import com.pocketclaw.claw.prompt.ToolContext
import com.pocketclaw.claw.skills.CustomSkill
import com.pocketclaw.claw.tools.ToolExecutor
import com.pocketclaw.claw.tools.ToolParser
import com.pocketclaw.app.data.WorkspaceProject
import com.pocketclaw.claw.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single Responsibility: Chat messages, input state, generation pipeline, and tool-call loop.
 * Extracted from MainViewModel (SOLID refactoring step 2 – code-refactoring-refactor-clean).
 *
 * Owns: messages, inputText, isProcessing, pendingConfirm, currentChatId,
 *       recentToolResults, toolCallCount, and the full generate→tool-call→regenerate loop.
 *
 * Safety guarantees (coroutines-expert):
 * 1. Backend switching explicitly releases native (C/C++) memory via
 *    [releaseCurrentBackend] before loading a new model.
 * 2. Tool-call recursion is bounded by [MAX_TOOL_LOOPS]; exceeding 5
 *    consecutive tool calls aborts the chain and emits a user-visible error.
 *
 * Does NOT own: model download/load, voice (STT/TTS), settings, skills/tasks CRUD, bond state.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatVM"
        private const val MAX_TOOL_CALLS_PER_TURN = 3
        private const val TOOL_TIMEOUT_MS = 30_000L
        private const val MAX_TOOL_LOOPS = 5
    }

    private val app = application as PocketClawApplication
    private val inferenceService: InferenceService = app.inferenceService
    private val chatRepository: ChatRepository = app.chatRepository
    private val bondEngine: BondEngine = app.bondEngine
    private val toolExecutor: ToolExecutor = app.toolExecutor

    // -- Chat State ------------------------------------------------

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _pendingConfirm = MutableStateFlow<String?>(null)
    val pendingConfirm: StateFlow<String?> = _pendingConfirm.asStateFlow()

    private var currentChatId: String? = null
    private var currentModel: LLMModel? = null
    private val recentToolResults = ArrayDeque<ToolContext>(5)
    private var toolCallCount = 0
    private var toolLoopDepth = 0
    private val _activeWorkspace = MutableStateFlow<WorkspaceProject?>(null)
    val activeWorkspace: StateFlow<WorkspaceProject?> = _activeWorkspace.asStateFlow()

    // -- Lifecycle ------------------------------------------------

    fun setCurrentModel(model: LLMModel?) {
        currentModel = model
    }

    fun addSystemMessage(text: String) {
        _messages.update { it + ChatMessage(text = text, isUser = false) }
    }

    fun setActiveWorkspace(project: WorkspaceProject?) {
        _activeWorkspace.value = project
    }

    init {
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

    // -- Backend Release (Memory Leak Protection) ------------------------------------------------

    fun releaseCurrentBackend() {
        viewModelScope.launch {
            try {
                inferenceService.unloadModel()
                inferenceService.onCleared()
                Log.d(TAG, "Previous backend released")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release backend: ${e.message}", e)
            }
        }
    }

    // -- Input ------------------------------------------------

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendTextMessage(text: String) {
        _messages.update { it + ChatMessage(text = text, isUser = true) }
        _inputText.value = ""
        toolLoopDepth = 0
        generateResponse(text)
    }

    fun newTopic() {
        _messages.value = emptyList()
        currentChatId = null
        toolLoopDepth = 0
        initializeChat()
    }

    fun deleteMessage(message: ChatMessage) {
        _messages.update { it.filter { msg -> msg.id != message.id } }
    }

    // -- Provider Resolution ------------------------------------------------

    /**
     * Resolves the active [CloudInferenceProvider] for the current [Preferences.llmMode].
     * Returns `null` when the mode is local (on-device inference via [InferenceService]).
     */
    private fun resolveActiveProvider(mode: String): CloudInferenceProvider? = when (mode) {
        "openrouter"     -> app.openRouterProvider
        "ollama"         -> app.ollamaProvider
        "opencode_zen"   -> app.openCodeZenProvider
        else             -> null
    }

    private fun isCloudMode(mode: String): Boolean = resolveActiveProvider(mode) != null

    // -- Generation Pipeline ------------------------------------------------

    private fun generateResponse(userText: String) {
        _isProcessing.value = true
        viewModelScope.launch {
            try {
                val mode = Preferences.llmMode
                val assembled = buildAssembledPrompt(userText)

                val placeholderId = java.util.UUID.randomUUID().toString()
                _messages.update { it + ChatMessage(id = placeholderId, text = "", isUser = false) }

                val sb = StringBuilder()
                val provider = resolveActiveProvider(mode)

                val genResult = withTimeoutOrNull(120_000L) {
                    if (provider != null) {
                        generateViaCloud(provider, assembled, sb, placeholderId)
                    } else {
                        generateViaLocal(assembled, userText, sb, placeholderId)
                    }
                }

                if (genResult == null && sb.isEmpty()) {
                    _messages.update { list ->
                        list.map {
                            if (it.id == placeholderId) it.copy(text = "\u23f1\ufe0f Antwort-Timeout (2 Min.). Versuch es erneut.")
                            else it
                        }
                    }
                    return@launch
                }

                val rawOutput = sb.toString()
                val toolCalls = ToolParser.parse(rawOutput)

                when {
                    isCloudMode(mode) && toolCalls.isEmpty() -> {
                        _messages.update { list ->
                            list.map { if (it.id == placeholderId) it.copy(text = rawOutput) else it }
                        }
                    }
                    toolCalls.isNotEmpty() -> {
                        handleToolCalls(toolCalls, rawOutput, placeholderId, userText)
                    }
                    else -> {
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

    // -- Prompt Assembly ------------------------------------------------

    private suspend fun buildAssembledPrompt(
        userText: String,
        toolResults: List<ToolContext> = recentToolResults.toList(),
    ): PromptAssembler.AssembledPrompt {
        val mode = Preferences.llmMode

        val recentHistory = _messages.value.takeLast(15).map { msg ->
            PromptAssembler.ChatTurn(
                role = if (msg.isUser) "user" else "assistant",
                content = msg.text,
            )
        }

        val enabledSkills = withContext(Dispatchers.IO) {
            app.database.customSkillDao().enabledSkills()
        }
        val customSkills = enabledSkills.map { s ->
            SkillsPrompt.SkillDef(
                id = "custom_${s.id}", name = s.name, description = s.description,
                exampleQ = s.exampleQuery, exampleA = s.exampleAnswer, isCustom = true,
            )
        }

        if (isCloudMode(mode)) {
            return PromptAssembler.assemble(
                memories = emptyList(),
                skills = customSkills,
                recentHistory = recentHistory,
                userMessage = userText,
                growthStage = 0,
                toolResults = toolResults,
                budget = ContextBudget.forMode(mode),
                includeToolInstructions = true,
            )
        }

        val bondMemories = withContext(Dispatchers.IO) { bondEngine.getMemoriesForPrompt() }
        val growthStageVal = withContext(Dispatchers.IO) { bondEngine.getGrowthStage() }
        val allSkills = SkillsPrompt.BUILTIN + customSkills

        return PromptAssembler.assemble(
            memories = bondMemories,
            skills = allSkills,
            recentHistory = recentHistory,
            userMessage = userText,
            growthStage = growthStageVal,
            toolResults = toolResults,
            budget = ContextBudget.forMode(mode),
            includeToolInstructions = true,
        )
    }

    // -- Tool Call Loop ------------------------------------------------

    private fun handleToolCalls(
        toolCalls: List<ToolParser.ParsedCall>,
        rawOutput: String,
        placeholderId: String,
        userText: String,
    ) {
        if (toolLoopDepth >= MAX_TOOL_LOOPS) {
            _messages.update { list ->
                list.map {
                    if (it.id == placeholderId) it.copy(
                        text = "Tool-Aufruf-Limit \u00fcberschritten (${MAX_TOOL_LOOPS} Durchl\u00e4ufe). Bitte formuliere deine Anfrage neu.",
                    ) else it
                }
            }
            toolLoopDepth = 0
            return
        }

        toolLoopDepth++

        viewModelScope.launch {
            for ((index, call) in toolCalls.withIndex()) {
                if (index >= MAX_TOOL_CALLS_PER_TURN) break
                executeToolCall(call, rawOutput, placeholderId, userText)
            }
        }
    }

    /**
     * Execute a single tool call and re-inject the result back into the
     * generation pipeline. Handles three outcome paths:
     *
     * 1. **Timeout**: Injects a timeout error into tool results, then
     *    re-runs generation so the LLM can explain the failure to the user.
     * 2. **Success**: Adds the tool output to recentToolResults, then
     *    re-runs generation. If the LLM produces more tool calls, the
     *    loop continues (bounded by [MAX_TOOL_LOOPS]).
     * 3. **Error**: Adds the error output to recentToolResults as a
     *    system-annotated failure message, then re-runs generation so
     *    the LLM can explain the error to the user — the stream does
     *    not halt.
     *
     * This design ensures full resilience: tool failures are always
     * communicated back to the provider for graceful explanation.
     */
    private suspend fun executeToolCall(
        call: ToolParser.ParsedCall,
        rawOutput: String,
        placeholderId: String,
        userText: String,
    ) {
        val startTime = System.currentTimeMillis()

        val result = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                toolExecutor.execute(call)
            }
        }

        val elapsed = System.currentTimeMillis() - startTime

        // ── Path 1: Timeout — inject error, re-run generation ──
        if (result == null) {
            Log.w(TAG, "Tool ${call.toolId} timed out after ${elapsed}ms")
            val timeoutSummary = "Tool ${call.toolId} hat nach ${elapsed}ms nicht geantwortet (Timeout)."
            val tc = ToolContext(call.toolId, call.args.take(40), timeoutSummary)
            recentToolResults.addLast(tc)
            val budget = ContextBudget.forMode(Preferences.llmMode)
            while (recentToolResults.size > budget.maxToolResults) recentToolResults.removeFirst()

            regenerateAfterToolCall(userText, placeholderId)
            return
        }

        Log.d(TAG, "Tool ${call.toolId} completed in ${elapsed}ms, success=${result.success}")

        // ── Build tool context for the next generation pass ──
        val summary = if (result.success) {
            val tool = ToolRegistry.get(call.toolId)
            tool?.summarize(result.output, 600) ?: result.output.take(600)
        } else {
            // Prefix error with system annotation so LLM knows this is a failure
            "[Tool-Fehler bei ${call.toolId}] ${result.output}"
        }

        val tc = ToolContext(call.toolId, call.args.take(40), summary)
        recentToolResults.addLast(tc)
        val budget = ContextBudget.forMode(Preferences.llmMode)
        while (recentToolResults.size > budget.maxToolResults) recentToolResults.removeFirst()

        // ── Paths 2 & 3: Re-run generation (both success and error) ──
        regenerateAfterToolCall(userText, placeholderId)
    }

    /**
     * After a tool call completes (success or error), re-assemble the prompt
     * with the updated [recentToolResults] and run another generation pass.
     * If the LLM produces further tool calls, the loop continues recursively
     * (bounded by [MAX_TOOL_LOOPS] in [handleToolCalls]).
     */
    private suspend fun regenerateAfterToolCall(userText: String, placeholderId: String) {
        val passId = java.util.UUID.randomUUID().toString()
        _messages.update { it + ChatMessage(id = passId, text = "", isUser = false) }

        val sb = StringBuilder()
        val assembled = buildAssembledPrompt(userText, recentToolResults.toList())
        val provider = resolveActiveProvider(Preferences.llmMode)

        if (provider != null) {
            generateViaCloud(provider, assembled, sb, passId)
        } else {
            generateViaLocal(assembled, userText, sb, passId)
        }

        val raw = sb.toString()
        val nextToolCalls = ToolParser.parse(raw)
        if (nextToolCalls.isNotEmpty()) {
            handleToolCalls(nextToolCalls, raw, passId, userText)
        } else {
            val cleaned = withContext(Dispatchers.IO) { bondEngine.processResponse(raw) }
            _messages.update { list ->
                list.map { if (it.id == passId) it.copy(text = cleaned) else it }
            }
        }
    }

    // -- Inference Backends ------------------------------------------------

    /**
     * Unified cloud generation via any [CloudInferenceProvider] (OpenAI-compatible format).
     * Collects the streaming response and updates the placeholder message in-place.
     */
    private suspend fun generateViaCloud(
        provider: CloudInferenceProvider,
        assembled: PromptAssembler.AssembledPrompt,
        sb: StringBuilder,
        placeholderId: String,
    ) {
        if (!provider.isReady()) {
            sb.append("${provider.displayName} nicht konfiguriert. Bitte in Einstellungen eintragen.")
            _messages.update { list ->
                list.map { if (it.id == placeholderId) it.copy(text = sb.toString()) else it }
            }
            return
        }
        try {
            val attachments = loadWorkspaceAttachments()
            provider.generateStream(assembled, attachments).collect { chunk ->
                sb.append(chunk)
                val currentText = sb.toString()
                _messages.update { list ->
                    list.map { if (it.id == placeholderId) it.copy(text = currentText) else it }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "${provider.displayName} error: ${e.message}", e)
            sb.append("${provider.displayName} Fehler: ${e.message}")
            _messages.update { list ->
                list.map { if (it.id == placeholderId) it.copy(text = sb.toString()) else it }
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
        if (model == null || !app.downloadViewModel.isModelLoaded.value) {
            sb.append("Kein lokales Modell geladen. Wechsle zu Cloud API oder lade ein Modell herunter.")
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

    // -- Workspace File Attachments ------------------------------------------------

    /**
     * Reads files from the active workspace's local folder and converts
     * them to [FileAttachment] objects for multimodal provider requests.
     * Only loads text-based files (.kt, .java, .py, .js, .ts, .md, .txt, etc.)
     * under a 50KB size limit per file. Runs on IO dispatcher.
     */
    private suspend fun loadWorkspaceAttachments(): List<FileAttachment> {
        val project = _activeWorkspace.value ?: return emptyList()
        val dir = java.io.File(project.localFolderPath)
        if (!dir.isDirectory) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val allowedExtensions = setOf(
                    "kt", "java", "py", "js", "ts", "tsx", "jsx",
                    "md", "txt", "json", "xml", "yaml", "yml",
                    "toml", "cfg", "ini", "properties", "gradle",
                    "sql", "sh", "bash", "css", "html",
                )
                dir.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in allowedExtensions }
                    .filter { it.length() in 1..50_000 }
                    .take(10)
                    .map { file ->
                        val content = file.readText(Charsets.UTF_8)
                        val mimeType = guessMimeType(file.extension)
                        val relPath = file.relativeTo(dir).path
                        FileAttachment(
                            mimeType = mimeType,
                            base64Data = content,
                            fileName = "$relPath (${project.name})",
                        )
                    }
                    .toList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load workspace files: ${e.message}")
                emptyList()
            }
        }
    }

    private fun guessMimeType(extension: String): String = when (extension.lowercase()) {
        "kt" -> "text/x-kotlin"
        "java" -> "text/x-java"
        "py" -> "text/x-python"
        "js", "jsx", "ts", "tsx" -> "text/javascript"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html" -> "text/html"
        "css" -> "text/css"
        "sql" -> "text/x-sql"
        "sh", "bash" -> "text/x-shellscript"
        else -> "text/plain"
    }

    // -- Skill Creation ------------------------------------------------

    private fun handleSkillCreation(text: String) {
        _isProcessing.value = true
        viewModelScope.launch {
            try {
                val skill = CustomSkill(
                    name = text.substringAfter("skill").substringAfter("\u6280\u80fd").trim().take(30).ifBlank { "New Skill" },
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
}
