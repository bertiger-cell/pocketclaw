package com.pocketclaw.app.ui.settings

import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketclaw.app.data.Preferences
import com.pocketclaw.app.data.WorkspaceProject
import com.pocketclaw.app.data.ScheduledTask
import com.pocketclaw.app.ui.theme.*
import com.pocketclaw.claw.security.AuditLog
import com.pocketclaw.app.service.ScreenControlService
import java.text.SimpleDateFormat
import java.util.*

// ══════════════════════════════════════════════════════════════════
//  Main Composable
// ══════════════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    llmMode: String,
    llmReady: Boolean,
    onSwitchLlmMode: (String) -> Unit,
    scheduledTasks: List<ScheduledTask>,
    onDeleteTask: (ScheduledTask) -> Unit,
    onToggleTask: (ScheduledTask) -> Unit,
    onCreateTask: (String, Int, Int, Boolean) -> Unit,
    auditEntries: List<AuditLog.Entry>,
    onOpenNotificationSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    // -- OpenRouter --
    onSetOpenRouterApiKey: (String) -> Unit = {},
    onSetOpenRouterModel: (String) -> Unit = {},
    selectedOpenRouterModel: String = "meta-llama/llama-3.3-70b-instruct:free",
    // -- Ollama --
    ollamaHost: String = "http://localhost:11434",
    onSetOllamaHost: (String) -> Unit = {},
    onSetOllamaModel: (String) -> Unit = {},
    ollamaModels: List<String> = emptyList(),
    ollamaConnected: Boolean = false,
    // -- OpenCode Zen --
    openCodeZenApiKey: String = "",
    onSetOpenCodeZenApiKey: (String) -> Unit = {},
    openCodeZenEndpoint: String = "https://zen.opencode.example/v1",
    onSetOpenCodeZenEndpoint: (String) -> Unit = {},
    openCodeZenModel: String = "zen-coder-v1",
    onSetOpenCodeZenModel: (String) -> Unit = {},
    openCodeZenConnected: Boolean = false,
    // -- Local models --
    qwenDownloaded: Boolean = false,
    qwenDownloading: Boolean = false,
    qwenProgress: Float = 0f,
    onDownloadQwen: () -> Unit = {},
    localModels: List<com.llmhub.llmhub.data.LLMModel> = emptyList(),
    modelDownloads: Map<String, com.pocketclaw.app.ui.download.DownloadViewModel.ModelDownloadState> = emptyMap(),
    modelLoading: Boolean = false,
    lastError: String? = null,
    onDownloadModel: (com.llmhub.llmhub.data.LLMModel) -> Unit = {},
    onLoadLocalModel: (com.llmhub.llmhub.data.LLMModel) -> Unit = {},
    onDeleteModel: (com.llmhub.llmhub.data.LLMModel) -> Unit = {},
    onDownloadDeviceModels: (List<com.llmhub.llmhub.data.LLMModel>) -> Unit = {},
    // -- Device profile --
    selectedDeviceProfile: DeviceProfile = DeviceProfile.NONE,
    filteredModels: List<com.llmhub.llmhub.data.LLMModel> = emptyList(),
    onSelectDeviceProfile: (DeviceProfile) -> Unit = {},
    // -- Personalization --
    userPreferredName: String = "User",
    onSetUserPreferredName: (String) -> Unit = {},
    userDataContext: String = "",
    onSetUserDataContext: (String) -> Unit = {},
    agentResponseStyle: String = "Standard",
    onSetAgentResponseStyle: (String) -> Unit = {},
    agentCustomBehaviorInstructions: String = "",
    onSetAgentCustomBehaviorInstructions: (String) -> Unit = {},
    // -- Workspaces --
    workspaceProjects: List<WorkspaceProject> = emptyList(),
    onCreateWorkspace: (String, String) -> Unit = { _, _ -> },
    onDeleteWorkspace: (WorkspaceProject) -> Unit = {},
    onUpdateWorkspace: (WorkspaceProject) -> Unit = {},
    activeWorkspace: WorkspaceProject? = null,
    onSetActiveWorkspace: (WorkspaceProject?) -> Unit = {},
    // -- Workspace error --
    workspaceError: String? = null,
    onClearWorkspaceError: () -> Unit = {},
) {
    var sttEnabled by remember { mutableStateOf(Preferences.sttEnabled) }
    var ttsEnabled by remember { mutableStateOf(Preferences.ttsEnabled) }
    var showOllamaModelDialog by remember { mutableStateOf(false) }
    var showMessagingDialog by remember { mutableStateOf(false) }
    var showCreateTaskDialog by remember { mutableStateOf(false) }
    var showAuditLog by remember { mutableStateOf(false) }
    var themeMode by remember { mutableStateOf(Preferences.themeMode) }
    val colors = AppColors
    val snackbarHostState = remember { SnackbarHostState() }

    // Show workspace error as Snackbar
    LaunchedEffect(workspaceError) {
        if (workspaceError != null) {
            snackbarHostState.showSnackbar(workspaceError, duration = SnackbarDuration.Short)
            onClearWorkspaceError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = CrabOrange,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 20.dp),
        )

        AppearanceSection(
            themeMode = themeMode,
            onThemeChange = { mode -> themeMode = mode; Preferences.themeMode = mode },
            colors = colors,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ════════════════════════════════════════════════════════
        //  KI-Persönlichkeit & Profil
        // ════════════════════════════════════════════════════════

        PersonalizationBentoCard(
            userPreferredName = userPreferredName,
            onSetUserPreferredName = onSetUserPreferredName,
            userDataContext = userDataContext,
            onSetUserDataContext = onSetUserDataContext,
            agentResponseStyle = agentResponseStyle,
            onSetAgentResponseStyle = onSetAgentResponseStyle,
            agentCustomBehaviorInstructions = agentCustomBehaviorInstructions,
            onSetAgentCustomBehaviorInstructions = onSetAgentCustomBehaviorInstructions,
            colors = colors,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ════════════════════════════════════════════════════════
        //  Projekt-Workspaces
        // ════════════════════════════════════════════════════════

        WorkspaceBentoCard(
            workspaceProjects = workspaceProjects,
            activeWorkspace = activeWorkspace,
            onCreateWorkspace = onCreateWorkspace,
            onDeleteWorkspace = onDeleteWorkspace,
            onUpdateWorkspace = onUpdateWorkspace,
            onSetActiveWorkspace = onSetActiveWorkspace,
            colors = colors,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ════════════════════════════════════════════════════════
        //  AI Brain — mode selector + provider cards
        // ════════════════════════════════════════════════════════

        SettingsSection("AI Brain", colors) {
            // ── Status header ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Memory, null, tint = CrabOrange, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("LLM Mode", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(
                        if (llmReady) "Bereit" else "Nicht bereit",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (llmReady) AccentGreen else AccentRed,
                    )
                }
            }

            // ── Mode chips ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModeChip("Local", llmMode == "local") { onSwitchLlmMode("local") }
                ModeChip("OpenRouter", llmMode == "openrouter") { onSwitchLlmMode("openrouter") }
                ModeChip("Ollama", llmMode == "ollama") { onSwitchLlmMode("ollama") }
                ModeChip("OpenCode Zen", llmMode == "opencode_zen") { onSwitchLlmMode("opencode_zen") }
            }

            // ── OpenRouter config card ──
            AnimatedVisibility(visible = llmMode == "openrouter", enter = fadeIn() + expandVertically()) {
                OpenRouterBentoCard(
                    apiKey = Preferences.openRouterApiKey,
                    selectedModel = selectedOpenRouterModel,
                    onApiKeyChange = { onSetOpenRouterApiKey(it) },
                    onModelSelect = { onSetOpenRouterModel(it) },
                    colors = colors,
                )
            }

            // ── Ollama config card ──
            AnimatedVisibility(visible = llmMode == "ollama", enter = fadeIn() + expandVertically()) {
                OllamaBentoCard(
                    host = ollamaHost,
                    connected = ollamaConnected,
                    models = ollamaModels,
                    selectedModel = Preferences.ollamaSelectedModel,
                    onHostChange = { onSetOllamaHost(it) },
                    onModelSelect = { onSetOllamaModel(it) },
                    onShowModels = { showOllamaModelDialog = true },
                    colors = colors,
                )
            }

            // ── OpenCode Zen config card ──
            AnimatedVisibility(visible = llmMode == "opencode_zen", enter = fadeIn() + expandVertically()) {
                OpenCodeZenBentoCard(
                    apiKey = openCodeZenApiKey,
                    endpoint = openCodeZenEndpoint,
                    selectedModel = openCodeZenModel,
                    connected = openCodeZenConnected,
                    onApiKeyChange = { onSetOpenCodeZenApiKey(it) },
                    onEndpointChange = { onSetOpenCodeZenEndpoint(it) },
                    onModelSelect = { onSetOpenCodeZenModel(it) },
                    colors = colors,
                )
            }

            // ── Local models ──
            AnimatedVisibility(visible = llmMode == "local", enter = fadeIn() + expandVertically()) {
                LocalModelsSection(
                    localModels = localModels,
                    selectedDeviceProfile = selectedDeviceProfile,
                    filteredModels = filteredModels,
                    onSelectDeviceProfile = onSelectDeviceProfile,
                    modelDownloads = modelDownloads,
                    modelLoading = modelLoading,
                    lastError = lastError,
                    onDownloadModel = onDownloadModel,
                    onLoadLocalModel = onLoadLocalModel,
                    onDeleteModel = onDeleteModel,
                    colors = colors,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        VoiceSection(
            sttEnabled = sttEnabled,
            ttsEnabled = ttsEnabled,
            onToggleStt = { sttEnabled = it; Preferences.sttEnabled = it },
            onToggleTts = { ttsEnabled = it; Preferences.ttsEnabled = it },
            colors = colors,
        )

        Spacer(modifier = Modifier.height(16.dp))

        PermissionsSection(
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onRequestStoragePermission = onRequestStoragePermission,
            colors = colors,
        )

        Spacer(modifier = Modifier.height(16.dp))

        RemindersSection(
            scheduledTasks = scheduledTasks,
            onDeleteTask = onDeleteTask,
            onToggleTask = onToggleTask,
            onCreateTask = onCreateTask,
            showCreateTaskDialog = showCreateTaskDialog,
            onShowCreateTaskDialog = { showCreateTaskDialog = true },
            colors = colors,
        )

        Spacer(modifier = Modifier.height(16.dp))

        MessagingSection(onShowDialog = { showMessagingDialog = true }, colors = colors)

        Spacer(modifier = Modifier.height(16.dp))

        SecuritySection(
            auditEntries = auditEntries,
            showAuditLog = showAuditLog,
            onToggleAuditLog = { showAuditLog = !showAuditLog },
            colors = colors,
        )

        Spacer(modifier = Modifier.height(16.dp))

        AboutSection(colors = colors)

        Spacer(modifier = Modifier.height(32.dp))
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        snackbar = { snackbarData ->
            Snackbar(
                snackbarData = snackbarData,
                containerColor = AccentRed.copy(alpha = 0.9f),
                contentColor = DarkTextPrimary,
                shape = RoundedCornerShape(8.dp),
            )
        },
    )
    } // end Box

    // ── Dialogs ──

    if (showCreateTaskDialog) {
        CreateTaskDialog(
            onDismiss = { showCreateTaskDialog = false },
            onCreate = { name, hour, minute, repeat ->
                onCreateTask(name, hour, minute, repeat)
                showCreateTaskDialog = false
            },
            colors = colors,
        )
    }

    if (showMessagingDialog) {
        MessagingTokensDialog(onDismiss = { showMessagingDialog = false })
    }

    if (showOllamaModelDialog) {
        OllamaModelDialog(
            onDismiss = { showOllamaModelDialog = false },
            onSelect = { model ->
                onSetOllamaModel(model)
                showOllamaModelDialog = false
            },
            models = ollamaModels,
            currentModel = Preferences.ollamaSelectedModel,
            colors = colors,
        )
    }

}

// ══════════════════════════════════════════════════════════════════
//  Mode Chip (shared)
// ══════════════════════════════════════════════════════════════════

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { if (selected) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = CrabOrangeDark,
            selectedLabelColor = DarkTextPrimary,
        ),
    )
}

// ══════════════════════════════════════════════════════════════════
//  OpenRouter Bento Card
// ══════════════════════════════════════════════════════════════════

@Composable
private fun OpenRouterBentoCard(
    apiKey: String,
    selectedModel: String,
    onApiKeyChange: (String) -> Unit,
    onModelSelect: (String) -> Unit,
    colors: ColorPalette,
) {
    val isConfigured = apiKey.isNotBlank()
    var showKey by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(apiKey) }
    var keyDirty by remember { mutableStateOf(false) }

    Surface(
        color = colors.card,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header with badge ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, tint = CrabOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("OpenRouter", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isConfigured) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = if (isConfigured) "Aktiv" else "Inaktiv",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isConfigured) AccentGreen else AccentRed,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── API Key field ──
            OutlinedTextField(
                value = editingKey,
                onValueChange = { editingKey = it; keyDirty = true },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = colors.textMuted,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CrabOrange,
                    unfocusedBorderColor = colors.textMuted.copy(alpha = 0.4f),
                    cursorColor = CrabOrange,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedLabelColor = CrabOrange,
                    unfocusedLabelColor = colors.textSecondary,
                ),
            )

            // ── Save button (only when dirty) ──
            if (keyDirty && editingKey != apiKey) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onApiKeyChange(editingKey); keyDirty = false },
                    colors = ButtonDefaults.buttonColors(containerColor = CrabOrange),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Speichern", color = DarkTextPrimary)
                }
            }

            // ── Model selector ──
            if (isConfigured) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = colors.surface, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Text("Modell", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(6.dp))

                var expanded by remember { mutableStateOf(false) }
                Surface(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.textMuted.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val matchedLabel = OPENROUTER_MODELS.find { it.first == selectedModel }?.second
                        Text(
                            text = matchedLabel ?: selectedModel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ArrowDropDown, null, tint = colors.textMuted)
                    }
                }

                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    OPENROUTER_MODELS.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(label, fontWeight = if (id == selectedModel) FontWeight.Bold else FontWeight.Normal)
                                    Text(id, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                                }
                            },
                            onClick = { onModelSelect(id); expanded = false },
                            leadingIcon = {
                                if (id == selectedModel) Icon(Icons.Default.Check, null, tint = CrabOrange, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Ollama Bento Card
// ══════════════════════════════════════════════════════════════════

@Composable
private fun OllamaBentoCard(
    host: String,
    connected: Boolean,
    models: List<String>,
    selectedModel: String,
    onHostChange: (String) -> Unit,
    onModelSelect: (String) -> Unit,
    onShowModels: () -> Unit,
    colors: ColorPalette,
) {
    var editingHost by remember { mutableStateOf(host) }
    var hostDirty by remember { mutableStateOf(false) }

    Surface(
        color = colors.card,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header with status ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, null, tint = CrabOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ollama", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (connected) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = if (connected) "Verbunden" else "Getrennt",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) AccentGreen else AccentRed,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Host field ──
            OutlinedTextField(
                value = editingHost,
                onValueChange = { editingHost = it; hostDirty = true },
                label = { Text("Server-Adresse") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CrabOrange,
                    unfocusedBorderColor = colors.textMuted.copy(alpha = 0.4f),
                    cursorColor = CrabOrange,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedLabelColor = CrabOrange,
                    unfocusedLabelColor = colors.textSecondary,
                ),
            )

            // ── Save host button ──
            if (hostDirty && editingHost != host) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onHostChange(editingHost); hostDirty = false },
                    colors = ButtonDefaults.buttonColors(containerColor = CrabOrange),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Speichern", color = DarkTextPrimary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Connection indicator + hint ──
            if (!connected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, null, tint = AccentRed, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Ollama nicht erreichbar. Ist der Server gestartet?",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentRed,
                    )
                }
            }

            // ── Model selector ──
            if (connected && models.isNotEmpty()) {
                HorizontalDivider(color = colors.surface, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Text("Modell", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(6.dp))

                var expanded by remember { mutableStateOf(false) }
                Surface(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.textMuted.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selectedModel.ifBlank { "Kein Modell ausgewählt" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selectedModel.isNotBlank()) colors.textPrimary else colors.textMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ArrowDropDown, null, tint = colors.textMuted)
                    }
                }

                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Text(model, fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal)
                            },
                            onClick = { onModelSelect(model); expanded = false },
                            leadingIcon = {
                                if (model == selectedModel) Icon(Icons.Default.Check, null, tint = CrabOrange, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Local Models Section (wraps DeviceAccordion + filtered list)
// ══════════════════════════════════════════════════════════════════

@Composable
private fun LocalModelsSection(
    localModels: List<com.llmhub.llmhub.data.LLMModel>,
    selectedDeviceProfile: DeviceProfile,
    filteredModels: List<com.llmhub.llmhub.data.LLMModel>,
    onSelectDeviceProfile: (DeviceProfile) -> Unit,
    modelDownloads: Map<String, com.pocketclaw.app.ui.download.DownloadViewModel.ModelDownloadState>,
    modelLoading: Boolean,
    lastError: String?,
    onDownloadModel: (com.llmhub.llmhub.data.LLMModel) -> Unit,
    onLoadLocalModel: (com.llmhub.llmhub.data.LLMModel) -> Unit,
    onDeleteModel: (com.llmhub.llmhub.data.LLMModel) -> Unit,
    colors: ColorPalette,
) {
    Surface(
        color = colors.card,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Empty state ──
            val availableLocal = localModels.filter { !it.name.contains("Gemma-4", ignoreCase = true) }
            if (availableLocal.isEmpty() && selectedDeviceProfile == DeviceProfile.NONE) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.CloudDownload, null, tint = CrabOrange, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Modelle zum Download bereit", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Lade ein Modell herunter, um es lokal zu nutzen", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }

            // ── Error banner ──
            if (lastError != null) {
                Surface(
                    color = AccentRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, tint = AccentRed, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(lastError, style = MaterialTheme.typography.bodySmall, color = AccentRed, maxLines = 2)
                    }
                }
            }

            // ── Device Accordion (UNCHANGED) ──
            DeviceAccordion(
                selectedProfile = selectedDeviceProfile,
                onSelectProfile = onSelectDeviceProfile,
                colors = colors,
            )

            // ── Filtered model list ──
            if (filteredModels.isNotEmpty()) {
                HorizontalDivider(color = colors.surface, thickness = 1.dp)
                for (model in filteredModels) {
                    ModelItem(
                        model = model,
                        downloadState = modelDownloads[model.name],
                        modelLoading = modelLoading,
                        onDownload = onDownloadModel,
                        onLoad = onLoadLocalModel,
                        onDelete = onDeleteModel,
                        colors = colors,
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  ModelItem — single model row inside the local section
// ══════════════════════════════════════════════════════════════════

@Composable
private fun ModelItem(
    model: com.llmhub.llmhub.data.LLMModel,
    downloadState: com.pocketclaw.app.ui.download.DownloadViewModel.ModelDownloadState?,
    modelLoading: Boolean,
    onDownload: (com.llmhub.llmhub.data.LLMModel) -> Unit,
    onLoad: (com.llmhub.llmhub.data.LLMModel) -> Unit,
    onDelete: (com.llmhub.llmhub.data.LLMModel) -> Unit,
    colors: ColorPalette,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            Text(
                "${model.modelFormat.uppercase()} \u2022 ${model.sizeBytes / 1_000_000} MB",
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
            )
        }
        if (model.isDownloaded) {
            IconButton(onClick = { onLoad(model) }, enabled = !modelLoading) {
                Icon(Icons.Default.PlayArrow, null, tint = CrabOrange)
            }
            IconButton(onClick = { onDelete(model) }) {
                Icon(Icons.Default.Delete, null, tint = AccentRed)
            }
        } else {
            IconButton(onClick = { onDownload(model) }) {
                Icon(Icons.Default.Download, null, tint = CrabOrange)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  DeviceAccordion — UNTOUCHED per directive
// ══════════════════════════════════════════════════════════════════

@Composable
private fun DeviceAccordion(
    selectedProfile: DeviceProfile,
    onSelectProfile: (DeviceProfile) -> Unit,
    colors: ColorPalette,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(
            text = "\ud83d\udcf1 Ger\u00e4t Profil",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val isNoteSelected = selectedProfile == DeviceProfile.GALAXY_NOTE_20
            Surface(
                onClick = { onSelectProfile(DeviceProfile.GALAXY_NOTE_20) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (isNoteSelected) CrabOrange else colors.surface,
                border = BorderStroke(
                    width = if (isNoteSelected) 2.dp else 1.dp,
                    color = if (isNoteSelected) CrabOrange else colors.textMuted.copy(alpha = 0.3f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(16.dp), tint = if (isNoteSelected) colors.surface else CrabOrange)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Galaxy Note 20", style = MaterialTheme.typography.labelSmall, color = if (isNoteSelected) colors.surface else colors.textPrimary, maxLines = 1)
                }
            }

            val isXiaomiSelected = selectedProfile == DeviceProfile.XIAOMI_13
            Surface(
                onClick = { onSelectProfile(DeviceProfile.XIAOMI_13) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (isXiaomiSelected) CrabOrangeDark else colors.surface,
                border = BorderStroke(
                    width = if (isXiaomiSelected) 2.dp else 1.dp,
                    color = if (isXiaomiSelected) CrabOrangeDark else colors.textMuted.copy(alpha = 0.3f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(16.dp), tint = if (isXiaomiSelected) colors.surface else CrabOrangeDark)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Xiaomi 13", style = MaterialTheme.typography.labelSmall, color = if (isXiaomiSelected) colors.surface else colors.textPrimary, maxLines = 1)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  OpenRouter Models List
// ══════════════════════════════════════════════════════════════════

private val OPENROUTER_MODELS = listOf(
    "meta-llama/llama-3.3-70b-instruct:free" to "Llama 3.3 70B (Stark, kostenlos)",
    "meta-llama/llama-3.1-8b-instruct:free" to "Llama 3.1 8B (Schnell, kostenlos)",
    "qwen/qwen3-32b:free" to "Qwen 3 32B (Stark, kostenlos)",
    "deepseek/deepseek-chat-v3-0324:free" to "DeepSeek V3 (Stark, kostenlos)",
    "google/gemma-3-27b-it:free" to "Gemma 3 27B (Google, kostenlos)",
    "anthropic/claude-3.5-sonnet" to "Claude 3.5 Sonnet (Premium)",
    "openai/gpt-4o" to "GPT-4o (Premium)",
    "openai/gpt-4o-mini" to "GPT-4o Mini (Billig)",
)

private val OPENCODE_ZEN_MODELS = listOf(
    "zen-coder-v1" to "Zen Coder v1 (Code-optimiert)",
    "zen-chat-v1" to "Zen Chat v1 (Konversation)",
    "zen-reason-v1" to "Zen Reason v1 (Komplexe Logik)",
    "zen-fast-v1" to "Zen Fast v1 (Niedrige Latenz)",
    "zen-pro-v1" to "Zen Pro v1 (Premium,最强)",
)

// ══════════════════════════════════════════════════════════════════
//  OpenCode Zen Bento Card
// ══════════════════════════════════════════════════════════════════

@Composable
private fun OpenCodeZenBentoCard(
    apiKey: String,
    endpoint: String,
    selectedModel: String,
    connected: Boolean,
    onApiKeyChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelSelect: (String) -> Unit,
    colors: ColorPalette,
) {
    val isConfigured = apiKey.isNotBlank() && endpoint.isNotBlank()
    var showKey by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(apiKey) }
    var editingEndpoint by remember { mutableStateOf(endpoint) }
    var keyDirty by remember { mutableStateOf(false) }
    var endpointDirty by remember { mutableStateOf(false) }

    Surface(
        color = colors.card,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header with badge ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, null, tint = CrabOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("OpenCode Zen", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (connected) AccentGreen.copy(alpha = 0.15f) else if (isConfigured) CrabOrange.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = if (connected) "Verbunden" else if (isConfigured) "Konfiguriert" else "Inaktiv",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) AccentGreen else if (isConfigured) CrabOrange else AccentRed,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── API Key field ──
            OutlinedTextField(
                value = editingKey,
                onValueChange = { editingKey = it; keyDirty = true },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = colors.textMuted,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CrabOrange,
                    unfocusedBorderColor = colors.textMuted.copy(alpha = 0.4f),
                    cursorColor = CrabOrange,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedLabelColor = CrabOrange,
                    unfocusedLabelColor = colors.textSecondary,
                ),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Gateway Endpoint field ──
            OutlinedTextField(
                value = editingEndpoint,
                onValueChange = { editingEndpoint = it; endpointDirty = true },
                label = { Text("Gateway Endpoint") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CrabOrange,
                    unfocusedBorderColor = colors.textMuted.copy(alpha = 0.4f),
                    cursorColor = CrabOrange,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedLabelColor = CrabOrange,
                    unfocusedLabelColor = colors.textSecondary,
                ),
            )

            // ── Save buttons ──
            val hasKeyChange = keyDirty && editingKey != apiKey
            val hasEndpointChange = endpointDirty && editingEndpoint != endpoint
            if (hasKeyChange || hasEndpointChange) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasKeyChange) {
                        Button(
                            onClick = { onApiKeyChange(editingKey); keyDirty = false },
                            colors = ButtonDefaults.buttonColors(containerColor = CrabOrange),
                            shape = RoundedCornerShape(8.dp),
                        ) { Text("Key speichern", color = DarkTextPrimary) }
                    }
                    if (hasEndpointChange) {
                        Button(
                            onClick = { onEndpointChange(editingEndpoint); endpointDirty = false },
                            colors = ButtonDefaults.buttonColors(containerColor = CrabOrange),
                            shape = RoundedCornerShape(8.dp),
                        ) { Text("Endpoint speichern", color = DarkTextPrimary) }
                    }
                }
            }

            // ── Model selector ──
            if (isConfigured) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = colors.surface, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Text("Modell", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(6.dp))

                var expanded by remember { mutableStateOf(false) }
                Surface(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.textMuted.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val matchedLabel = OPENCODE_ZEN_MODELS.find { it.first == selectedModel }?.second
                        Text(
                            text = matchedLabel ?: selectedModel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ArrowDropDown, null, tint = colors.textMuted)
                    }
                }

                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    OPENCODE_ZEN_MODELS.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(label, fontWeight = if (id == selectedModel) FontWeight.Bold else FontWeight.Normal)
                                    Text(id, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                                }
                            },
                            onClick = { onModelSelect(id); expanded = false },
                            leadingIcon = {
                                if (id == selectedModel) Icon(Icons.Default.Check, null, tint = CrabOrange, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Ollama Model Dialog
// ══════════════════════════════════════════════════════════════════

@Composable
private fun OllamaModelDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    models: List<String>,
    currentModel: String,
    colors: ColorPalette,
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = colors.card,
        title = { Text("Ollama Modell", color = colors.textPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (models.isEmpty()) {
                    Text("Keine Modelle gefunden. Ist Ollama gestartet?", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                } else {
                    Text("${models.size} Modell(e) gefunden:", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    models.forEach { model ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(model) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = model == currentModel,
                                onClick = { onSelect(model) },
                                colors = RadioButtonDefaults.colors(selectedColor = CrabOrange),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(model, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, fontWeight = if (model == currentModel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig", color = CrabOrange) } },
        dismissButton = null,
    )
}

// ══════════════════════════════════════════════════════════════════
//  Shared section composables (unchanged logic)
// ══════════════════════════════════════════════════════════════════

@Composable
fun SettingsSection(title: String, colors: ColorPalette, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = colors.card,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = CrabOrange,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            content()
        }
    }
}

@Composable
private fun SettingsItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, colors: ColorPalette) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = CrabOrange, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Icon(Icons.Default.ChevronRight, null, tint = colors.textMuted)
    }
}

@Composable
private fun SettingsToggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, colors: ColorPalette) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = CrabOrange, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = CrabOrange, checkedTrackColor = CrabOrangeDark),
        )
    }
}

@Composable
fun AppearanceSection(themeMode: String, onThemeChange: (String) -> Unit, colors: ColorPalette) {
    SettingsSection("Appearance", colors) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Palette, null, tint = CrabOrange, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text("Theme", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((mode, label) in listOf("dark" to "Dark", "light" to "Light", "system" to "Auto")) {
                FilterChip(
                    selected = themeMode == mode, onClick = { onThemeChange(mode) }, label = { Text(label) },
                    leadingIcon = { if (themeMode == mode) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CrabOrangeDark, selectedLabelColor = DarkTextPrimary),
                )
            }
        }
    }
}

@Composable
fun VoiceSection(sttEnabled: Boolean, ttsEnabled: Boolean, onToggleStt: (Boolean) -> Unit, onToggleTts: (Boolean) -> Unit, colors: ColorPalette) {
    SettingsSection("Voice", colors) {
        SettingsToggle(icon = Icons.Default.Mic, title = "Speech-to-Text", subtitle = "Android Speech (on-device)", checked = sttEnabled, onCheckedChange = onToggleStt, colors = colors)
        HorizontalDivider(color = colors.surface, thickness = 1.dp)
        SettingsToggle(icon = Icons.AutoMirrored.Filled.VolumeUp, title = "Text-to-Speech", subtitle = "Kokoro / System TTS", checked = ttsEnabled, onCheckedChange = onToggleTts, colors = colors)
    }
}

@Composable
fun PermissionsSection(onOpenNotificationSettings: () -> Unit, onOpenAccessibilitySettings: () -> Unit, onRequestStoragePermission: () -> Unit, colors: ColorPalette) {
    SettingsSection("Permissions", colors) {
        SettingsItem(icon = Icons.Default.Accessibility, title = "Screen Control", subtitle = if (ScreenControlService.isEnabled) "Enabled" else "Tap to enable in system settings", onClick = onOpenAccessibilitySettings, colors = colors)
        HorizontalDivider(color = colors.surface, thickness = 1.dp)
        SettingsItem(icon = Icons.Default.Folder, title = "File Access", subtitle = if (Environment.isExternalStorageManager()) "Full access granted" else "Tap to grant storage access", onClick = onRequestStoragePermission, colors = colors)
        HorizontalDivider(color = colors.surface, thickness = 1.dp)
        SettingsItem(icon = Icons.Default.Notifications, title = "Notification Access", subtitle = "Manage which apps to monitor", onClick = onOpenNotificationSettings, colors = colors)
    }
}

@Composable
fun RemindersSection(scheduledTasks: List<ScheduledTask>, onDeleteTask: (ScheduledTask) -> Unit, onToggleTask: (ScheduledTask) -> Unit, onCreateTask: (String, Int, Int, Boolean) -> Unit, showCreateTaskDialog: Boolean, onShowCreateTaskDialog: () -> Unit, colors: ColorPalette) {
    SettingsSection("Reminders (${scheduledTasks.size})", colors) {
        if (scheduledTasks.isEmpty()) {
            Text("No reminders. Ask PocketClaw or create one below.", color = colors.textMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        } else {
            for (task in scheduledTasks.take(5)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d:%02d".format(task.hour, task.minute), style = MaterialTheme.typography.titleMedium, color = if (task.enabled) CrabOrange else colors.textMuted, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(task.name, modifier = Modifier.weight(1f), color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = task.enabled, onCheckedChange = { onToggleTask(task) }, colors = SwitchDefaults.colors(checkedThumbColor = CrabOrange, checkedTrackColor = CrabOrangeDark), modifier = Modifier.height(24.dp))
                    IconButton(onClick = { onDeleteTask(task) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, null, tint = colors.textMuted, modifier = Modifier.size(18.dp)) }
                }
            }
        }
        HorizontalDivider(color = colors.surface, thickness = 1.dp)
        Surface(onClick = onShowCreateTaskDialog, color = colors.card) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, tint = CrabOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Reminder", color = CrabOrange, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun MessagingSection(onShowDialog: () -> Unit, colors: ColorPalette) {
    SettingsSection("Messaging", colors) {
        SettingsItem(icon = Icons.Default.Send, title = "Bot Tokens", subtitle = "Configure Telegram / Discord / Feishu / Slack", onClick = onShowDialog, colors = colors)
    }
}

@Composable
fun SecuritySection(auditEntries: List<AuditLog.Entry>, showAuditLog: Boolean, onToggleAuditLog: () -> Unit, colors: ColorPalette) {
    SettingsSection("Security", colors) {
        SettingsItem(icon = Icons.Default.Security, title = "Audit Log", subtitle = "${auditEntries.size} recent tool executions", onClick = onToggleAuditLog, colors = colors)
        if (showAuditLog && auditEntries.isNotEmpty()) {
            HorizontalDivider(color = colors.surface, thickness = 1.dp)
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            for (entry in auditEntries.take(15)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Text(fmt.format(Date(entry.timestamp)), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${if (entry.success) "\u2713" else "\u2717"} ${entry.toolId}", style = MaterialTheme.typography.bodySmall, color = if (entry.success) AccentGreen else AccentRed)
                        if (entry.args.isNotBlank()) Text(entry.args.take(60), style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}



// ══════════════════════════════════════════════════════════════════
//  KI-Persönlichkeit & Profil Bento Card
// ══════════════════════════════════════════════════════════════════

@Composable
private fun PersonalizationBentoCard(
    userPreferredName: String,
    onSetUserPreferredName: (String) -> Unit,
    userDataContext: String,
    onSetUserDataContext: (String) -> Unit,
    agentResponseStyle: String,
    onSetAgentResponseStyle: (String) -> Unit,
    agentCustomBehaviorInstructions: String,
    onSetAgentCustomBehaviorInstructions: (String) -> Unit,
    colors: ColorPalette,
) {
    var editingName by remember { mutableStateOf(userPreferredName) }
    var editingContext by remember { mutableStateOf(userDataContext) }
    var editingInstructions by remember { mutableStateOf(agentCustomBehaviorInstructions) }
    var nameDirty by remember { mutableStateOf(false) }
    var contextDirty by remember { mutableStateOf(false) }
    var instructionsDirty by remember { mutableStateOf(false) }

    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CrabOrange,
        unfocusedBorderColor = colors.textMuted.copy(alpha = 0.4f),
        cursorColor = CrabOrange,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedLabelColor = CrabOrange,
        unfocusedLabelColor = colors.textSecondary,
    )
    val styleOptions = listOf("Standard", "Prägnant", "Sokratisch", "Formell")

    Surface(
        color = colors.card,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = CrabOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("KI-Persönlichkeit & Profil", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Name ──
            OutlinedTextField(
                value = editingName,
                onValueChange = { editingName = it; nameDirty = true },
                label = { Text("Dein Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = tfColors,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── User context ──
            OutlinedTextField(
                value = editingContext,
                onValueChange = { editingContext = it; contextDirty = true },
                label = { Text("Infos über mich (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = tfColors,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Custom instructions ──
            OutlinedTextField(
                value = editingInstructions,
                onValueChange = { editingInstructions = it; instructionsDirty = true },
                label = { Text("Eigene Anweisungen (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = tfColors,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Response style ──
            Text("Antwortstil", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                styleOptions.forEach { style ->
                    FilterChip(
                        selected = agentResponseStyle == style,
                        onClick = { onSetAgentResponseStyle(style) },
                        label = { Text(style, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = if (agentResponseStyle == style) {
                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CrabOrangeDark,
                            selectedLabelColor = DarkTextPrimary,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── Save buttons (only when dirty) ──
            val anyDirty = nameDirty || contextDirty || instructionsDirty
            if (anyDirty) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (nameDirty && editingName != userPreferredName) onSetUserPreferredName(editingName)
                        if (contextDirty && editingContext != userDataContext) onSetUserDataContext(editingContext)
                        if (instructionsDirty && editingInstructions != agentCustomBehaviorInstructions) {
                            onSetAgentCustomBehaviorInstructions(editingInstructions)
                        }
                        nameDirty = false; contextDirty = false; instructionsDirty = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrabOrange),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Speichern", color = DarkTextPrimary)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Projekt-Workspaces Bento Card
// ══════════════════════════════════════════════════════════════════

@Composable
private fun WorkspaceBentoCard(
    workspaceProjects: List<WorkspaceProject>,
    activeWorkspace: WorkspaceProject?,
    onCreateWorkspace: (String, String) -> Unit,
    onDeleteWorkspace: (WorkspaceProject) -> Unit,
    onUpdateWorkspace: (WorkspaceProject) -> Unit,
    onSetActiveWorkspace: (WorkspaceProject?) -> Unit,
    colors: ColorPalette,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingProject by remember { mutableStateOf<WorkspaceProject?>(null) }

    Surface(
        color = colors.card,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null, tint = CrabOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Projekt-Workspaces", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Spacer(modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { showCreateDialog = true },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = CrabOrange.copy(alpha = 0.12f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp), tint = CrabOrange)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Projekt erstellen", style = MaterialTheme.typography.labelSmall, color = CrabOrange)
                }
            }

            if (workspaceProjects.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Noch keine Workspaces angelegt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                workspaceProjects.forEach { project ->
                    val isActive = activeWorkspace?.id == project.id
                    val skillCount = project.boundSkillIds.split(",").filter { it.isNotBlank() }.size

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isActive) CrabOrange.copy(alpha = 0.08f) else colors.surface,
                        border = BorderStroke(1.dp, if (isActive) CrabOrange.copy(alpha = 0.4f) else colors.textMuted.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isActive) {
                                    Icon(Icons.Default.Link, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(project.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                                Spacer(modifier = Modifier.weight(1f))
                                // Activate / Deactivate toggle
                                TextButton(
                                    onClick = {
                                        if (isActive) onSetActiveWorkspace(null)
                                        else onSetActiveWorkspace(project)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        text = if (isActive) "Deaktivieren" else "Aktivieren",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isActive) AccentRed else AccentGreen,
                                    )
                                }
                                // Delete button
                                IconButton(
                                    onClick = { onDeleteWorkspace(project) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.Delete, null, tint = AccentRed, modifier = Modifier.size(16.dp))
                                }
                            }

                            if (project.projectInstructions.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = project.projectInstructions.take(80) + if (project.projectInstructions.length > 80) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    maxLines = 2,
                                )
                            }

                            if (skillCount > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AutoAwesome, null, tint = CrabOrange, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "$skillCount Skill${if (skillCount != 1) "s" else ""} gebunden",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textMuted,
                                    )
                                }
                            }

                            if (isActive) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = AccentGreen.copy(alpha = 0.1f),
                                ) {
                                    Text(
                                        "Aktiv — Dateien werden automatisch als Kontext geladen",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentGreen,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Edit dialog trigger ──
            if (editingProject != null) {
                // handled below as separate dialog
            }
        }
    }

    // ── Create workspace dialog ──
    if (showCreateDialog) {
        CreateWorkspaceDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, instructions ->
                onCreateWorkspace(name, instructions)
                showCreateDialog = false
            },
            colors = colors,
        )
    }
}

// ══════════════════════════════════════════════════════════════════
//  Create Workspace Dialog
// ══════════════════════════════════════════════════════════════════

@Composable
private fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
    colors: ColorPalette,
) {
    var name by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CrabOrange,
        unfocusedBorderColor = colors.textMuted,
        cursorColor = CrabOrange,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedLabelColor = CrabOrange,
        unfocusedLabelColor = colors.textSecondary,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text("Projekt erstellen", color = colors.textPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Projektname") },
                    singleLine = true,
                    colors = tfColors,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Projektanweisungen (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    colors = tfColors,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name, instructions) },
                enabled = name.isNotBlank(),
            ) {
                Text("Erstellen", color = CrabOrange)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = colors.textSecondary) }
        },
    )
}


@Composable
fun AboutSection(colors: ColorPalette) {
    SettingsSection("About", colors) {
        SettingsItem(icon = Icons.Default.Info, title = "PocketClaw", subtitle = "v0.5.0 — Your pocket butler, no server needed", onClick = {}, colors = colors)
    }
}

// ══════════════════════════════════════════════════════════════════
//  Dialogs
// ══════════════════════════════════════════════════════════════════

@Composable
private fun CreateTaskDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int, Boolean) -> Unit, colors: ColorPalette) {
    var name by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("09") }
    var minute by remember { mutableStateOf("00") }
    var repeat by remember { mutableStateOf(false) }
    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CrabOrange, unfocusedBorderColor = colors.textMuted, cursorColor = CrabOrange,
        focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary,
        focusedLabelColor = CrabOrange, unfocusedLabelColor = colors.textSecondary,
    )
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = colors.card,
        title = { Text("Create Reminder", color = colors.textPrimary) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, colors = tfColors)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = hour, onValueChange = { hour = it }, label = { Text("HH") }, singleLine = true, modifier = Modifier.weight(1f), colors = tfColors)
                    OutlinedTextField(value = minute, onValueChange = { minute = it }, label = { Text("MM") }, singleLine = true, modifier = Modifier.weight(1f), colors = tfColors)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = repeat, onCheckedChange = { repeat = it }, colors = CheckboxDefaults.colors(checkedColor = CrabOrange))
                    Text("Repeat daily", color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name, hour.toIntOrNull() ?: 9, minute.toIntOrNull() ?: 0, repeat) }, enabled = name.isNotBlank()) {
                Text("Create", color = CrabOrange)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}

@Composable
private fun MessagingTokensDialog(onDismiss: () -> Unit) {
    var tgToken by remember { mutableStateOf(Preferences.telegramToken) }
    var discordToken by remember { mutableStateOf(Preferences.discordToken) }
    val colors = AppColors
    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CrabOrange, unfocusedBorderColor = colors.textMuted, cursorColor = CrabOrange,
        focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary,
        focusedLabelColor = CrabOrange, unfocusedLabelColor = colors.textSecondary,
    )
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = colors.card,
        title = { Text("Bot Tokens", color = colors.textPrimary) },
        text = {
            Column {
                OutlinedTextField(value = tgToken, onValueChange = { tgToken = it }, label = { Text("Telegram Bot Token") }, singleLine = true, colors = tfColors)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = discordToken, onValueChange = { discordToken = it }, label = { Text("Discord Bot Token") }, singleLine = true, colors = tfColors)
            }
        },
        confirmButton = {
            TextButton(onClick = { Preferences.telegramToken = tgToken; Preferences.discordToken = discordToken; onDismiss() }) {
                Text("Save", color = CrabOrange)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}
