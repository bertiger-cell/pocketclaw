package com.pocketclaw.app.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketclaw.app.manager.LocalModelManager
import com.pocketclaw.app.ui.theme.AppColors
import com.pocketclaw.app.ui.theme.CrabOrange

@Composable
fun LocalModelDialog(
    onDismiss: () -> Unit,
    onModelSelected: (modelPath: String) -> Unit,
) {
    val colors = AppColors
    val context = LocalContext.current
    val commonPaths = LocalModelManager.getCommonModelPaths(context)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text("Load Local Model", color = colors.textPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Looking for Qwen3 model on your device...",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                
                commonPaths.forEach { (fullPath, displayName) ->
                    val exists = LocalModelManager.modelExists(fullPath)
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { if (exists) it else it },
                        colors = CardDefaults.cardColors(
                            containerColor = if (exists) colors.surface else colors.card.copy(alpha = 0.5f)
                        ),
                        shape = MaterialTheme.shapes.small,
                        enabled = exists,
                        onClick = {
                            if (exists) {
                                onModelSelected(fullPath)
                                onDismiss()
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (exists) colors.textPrimary else colors.textMuted
                                )
                                if (exists) {
                                    Text(
                                        "✓ Found (${String.format("%.1f", LocalModelManager.getModelSizeGB(fullPath))} GB)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                } else {
                                    Text(
                                        "Not found",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textMuted
                                    )
                                }
                            }
                            if (exists) {
                                Icon(Icons.Default.Storage, null, tint = CrabOrange, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    "💡 Tip: Use the download buttons in Settings → AI Brain, or manually place .gguf files in:\n• /sdcard/Download/\n• App-internal models/ (auto-detected)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}