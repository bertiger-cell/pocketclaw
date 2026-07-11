package com.pocketclaw.app.ui.skills

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketclaw.claw.skills.CustomSkill
import com.pocketclaw.app.ui.theme.*
import org.json.JSONArray

data class HubSkill(
    val name: String,
    val category: String,
    val description: String,
)

fun loadSkillsFromAssets(context: Context): List<HubSkill> {
    return try {
        val json = context.assets.open("skills_catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            HubSkill(
                name = obj.getString("n"),
                category = obj.getString("c"),
                description = obj.getString("d"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
fun SkillHubDialog(
    onDismiss: () -> Unit,
    onInstall: (CustomSkill) -> Unit,
    installedIds: List<String>,
) {
    val context = LocalContext.current
    val allSkills = remember { loadSkillsFromAssets(context) }
    var search by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Alle") }
    val colors = AppColors

    val categories = remember(allSkills) {
        listOf("Alle") + allSkills.map { it.category }.distinct().sorted()
    }

    val filtered = allSkills.filter { skill ->
        val matchesSearch = search.isBlank() ||
            skill.name.contains(search, ignoreCase = true) ||
            skill.description.contains(search, ignoreCase = true) ||
            skill.category.contains(search, ignoreCase = true)
        val matchesCategory = selectedCategory == "Alle" || skill.category == selectedCategory
        matchesSearch && matchesCategory
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storefront, null, tint = CrabOrange, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Skill Hub (${allSkills.size})", color = colors.textPrimary, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    placeholder = { Text("Skill suchen...", color = colors.textMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = colors.textMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CrabOrange, unfocusedBorderColor = colors.textMuted,
                        cursorColor = CrabOrange, focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.take(6).forEach { cat ->
                        FilterChip(
                            selected = cat == selectedCategory,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CrabOrangeDark, selectedLabelColor = DarkTextPrimary,
                            ),
                        )
                    }
                }
                Text(
                    text = "${filtered.size} Skills gefunden",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered) { skill ->
                    val isInstalled = installedIds.contains(skill.name.lowercase())
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.elevated),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(skill.name, color = colors.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(skill.description, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text(skill.category, color = CrabOrange, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (isInstalled) {
                                Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                            } else {
                                TextButton(onClick = {
                                    onInstall(CustomSkill(
                                        name = skill.name,
                                        description = skill.description,
                                        keywords = skill.name.lowercase().replace("-", " ").replace("_", " "),
                                        exampleQuery = "",
                                        exampleAnswer = "",
                                    ))
                                }) {
                                    Text("Install", color = CrabOrange)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schliessen", color = CrabOrange) }
        },
        dismissButton = null,
    )
}
