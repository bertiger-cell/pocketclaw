package com.pocketclaw.app.ui.skills

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketclaw.claw.skills.CustomSkill
import com.pocketclaw.app.ui.theme.*

data class HubSkill(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val keywords: List<String>,
    val exampleQ: String,
    val exampleA: String,
)

val HUB_SKILLS = listOf(
    HubSkill("web_search", "Web Search", "Durchsucht das Internet via DuckDuckGo nach aktuellen Informationen", "Wissen", listOf("web", "search", "suche", "internet", "online", "nachrichten", "aktuell", "google", "finden", "recherchieren"), "Suche nach den aktuellen Nachrichten", "Hier sind die aktuellen Nachrichten..."),
    HubSkill("code_helper", "Code Helper", "Hilft beim Schreiben und Erklaeren von Code", "Entwicklung", listOf("code", "programmierung", "python", "java", "javascript", "function"), "Erklaere diese Funktion", "Hier ist die Erklaerung..."),
    HubSkill("email_writer", "E-Mail Schreiber", "Verfasst professionelle E-Mails", "Kommunikation", listOf("email", "mail", "nachricht", "schreiben"), "Schreib mir eine E-Mail", "Hier ist ein Entwurf..."),
    HubSkill("meal_planner", "Essensplaner", "Wochenplan fuer Mahlzeiten mit Einkaufsliste", "Gesundheit", listOf("essen", "mahlzeit", "plan", "kochen", "rezept"), "Erstelle einen Wochenplan fuers Essen", "Dein Wochenplan..."),
    HubSkill("workout", "Fitness Coach", "Trainingsplaeene und Fitness-Tipps", "Gesundheit", listOf("training", "fitness", "uebung", "sport", "workout"), "Erstelle einen Trainingsplan", "Hier ist dein Plan..."),
    HubSkill("study_buddy", "Lern-Assistent", "Zusammenfassungen und Karteikarten", "Bildung", listOf("lernen", "zusammenfassung", "pruefung", "karteikarte", "studium"), "Fasse dieses Thema zusammen", "Zusammenfassung..."),
    HubSkill("travel_planner", "Reiseplaner", "Reisen mit Budget und Tipps", "Reisen", listOf("reise", "urlaub", "flug", "hotel", "budget"), "Plan mir eine Reise nach Tokyo", "Dein Tokyo-Plan..."),
    HubSkill("diy_helper", "DIY-Assistent", "Heimprojekte und Reparatur-Tipps", "Heimwerken", listOf("diy", "reparatur", "bauen", "werkzeug"), "Wie repariere ich einen tropfenden Hahn", "Schritt-fuer-Schritt..."),
    HubSkill("brainstorm", "Brainstorming", "Hilft bei Ideenfindung", "Kreativitaet", listOf("idee", "brainstorm", "kreativ"), "Hilf mir beim Brainstorming", "5 Ideen fuer dein Projekt..."),
    HubSkill("story_writer", "Geschichten-Schreiber", "Kurze Geschichten und Gedichte", "Kreativitaet", listOf("geschichte", "story", "gedicht", "schreiben"), "Schreib eine Geschichte ueber einen Drachen", "Es war einmal..."),
    HubSkill("shopping_list", "Einkaufsliste", "Listen basierend auf Rezepten", "Alltag", listOf("einkauf", "liste", "supermarkt"), "Einkaufsliste fuer Pasta Bolognese", "500g Hackfleisch..."),
    HubSkill("pet_care", "Haustier-Berater", "Pflege-Tipps fuer Haustiere", "Haustiere", listOf("haustier", "hund", "katze", "futter"), "Wie pflege ich meinen Hund", "Dein Hund braucht..."),
    HubSkill("finance", "Finanz-Assistent", "Budgetplanung und Sparen", "Finanzen", listOf("geld", "budget", "sparen", "finanz"), "Erstelle ein Budget", "Dein Monatsbudget..."),
    HubSkill("language_tutor", "Sprach-Tutor", "Fremdsprachen lernen", "Bildung", listOf("sprache", "lernen", "uebersetzen", "englisch"), "Lehr mich Japanisch", "Konnichiwa! Grundlagen..."),
    HubSkill("meditation", "Meditations-Guide", "Meditation und Atemuebungen", "Gesundheit", listOf("meditation", "atmen", "entspannen", "yoga"), "Fuehr mich durch eine Meditation", "Schliesse die Augen..."),
    HubSkill("recipe_finder", "Rezept-Finder", "Findet Rezepte basierend auf Zutaten", "Kochen", listOf("rezept", "kochen", "zutat", "gericht"), "Was kann ich mit Tomaten kochen?", "Tomaten-Kaprese..."),
)

@Composable
fun SkillHubDialog(
    onDismiss: () -> Unit,
    onInstall: (CustomSkill) -> Unit,
    installedIds: List<String>,
) {
    var search by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Alle") }
    val colors = AppColors
    val categories = listOf("Alle") + HUB_SKILLS.map { it.category }.distinct().sorted()

    val filtered = HUB_SKILLS.filter { skill ->
        val matchesSearch = search.isBlank() ||
            skill.name.contains(search, ignoreCase = true) ||
            skill.description.contains(search, ignoreCase = true) ||
            skill.keywords.any { it.contains(search, ignoreCase = true) }
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
                    Text("Skill Hub", color = colors.textPrimary, style = MaterialTheme.typography.titleLarge)
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
                    categories.take(5).forEach { cat ->
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
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered) { skill ->
                    val isInstalled = installedIds.contains(skill.name.lowercase())
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.elevated),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(skill.name, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(skill.description, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(skill.category, color = CrabOrange, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (isInstalled) {
                                Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                            } else {
                                TextButton(onClick = {
                                    onInstall(CustomSkill(
                                        name = skill.name,
                                        description = skill.description,
                                        keywords = skill.keywords.joinToString(","),
                                        exampleQuery = skill.exampleQ,
                                        exampleAnswer = skill.exampleA,
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
