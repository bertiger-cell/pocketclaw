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
    // === Produktivitaet ===
    HubSkill("web_search", "Web Search", "Durchsucht das Internet via DuckDuckGo", "Wissen", listOf("web", "suche", "internet", "nachrichten", "aktuell", "google"), "Suche nach aktuellen Nachrichten", "Hier sind die Ergebnisse..."),
    HubSkill("blueprint", "Projekt-Blueprint", "Verwandelt Ziele in Schritt-fuer-Schritt-Plaene", "Produktivitaet", listOf("plan", "ziel", "projekt", "planung", "schritte"), "Erstelle einen Plan fuer mein Projekt", "Schritt 1: ..."),
    HubSkill("concise-planning", "Kompakte Planung", "Klare, aktionsfaehige Checklisten", "Produktivitaet", listOf("checkliste", "plan", "aufgabe", "todo", "aktion"), "Erstelle eine Checkliste fuer...", "1. ...
2. ..."),
    HubSkill("brain-to-docs", "Brainstorm zu Docs", "Verwandelt Ideen in README und Dokumentation", "Produktivitaet", listOf("doku", "readme", "dokumentation", "idee"), "Dokumentiere unsere Ideen", "Hier ist die Dokumentation..."),

    // === Schreiben & Content ===
    HubSkill("bulletmind", "Bullet Points", "Strukturierte Aufzaehlungen und Zusammenfassungen", "Schreiben", listOf("bullet", "zusammenfassung", "liste", "punkte", "struktur"), "Fasse das als Bullet Points zusammen", "- Punkt 1
- Punkt 2"),
    HubSkill("beautiful-prose", "Gutes Schreiben", "Kraftvoller Schreibstil ohne AI-Klischees", "Schreiben", listOf("schreiben", "text", "prosa", "stil", "editorial"), "Schreibe einen kraftvollen Text ueber...", "Hier ist der Text..."),
    HubSkill("blog-writing-guide", "Blog Schreiben", "Blog-Artikel mit SEO-Struktur", "Schreiben", listOf("blog", "artikel", "seo", "posting"), "Schreibe einen Blog-Artikel ueber...", "Titel: ...
Einleitung: ..."),
    HubSkill("avoid-ai-writing", "Kein AI-Stil", "Entfernt typische AI-Schreibmuster", "Schreiben", listOf("ai", "schreibstil", "natuerlich", "menschlich"), "Mache diesen Text natuerlicher", "Hier ist die ueberarbeitete Version..."),
    HubSkill("citation-management", "Zitationen", "Quellenangaben und Referenzen verwalten", "Schreiben", listOf("quelle", "zitation", "referenz", "bibliographie"), "Fuege Quellenangaben hinzu", "Hier sind die Zitationen..."),
    HubSkill("content-marketer", "Content Marketing", "AI-gestuetzte Content-Erstellung und SEO", "Schreiben", listOf("marketing", "content", "seo", "text"), "Erstelle Marketing-Content fuer...", "Hier ist der Content..."),

    // === Business ===
    HubSkill("business-analyst", "Business Analyse", "Datengetriebene Geschaeftsanalyse", "Business", listOf("business", "analyse", "geschaeft", "daten", "kpi"), "Analysiere mein Geschaeftsmodell", "Analyse: ..."),
    HubSkill("competitive-landscape", "Wettbewerbsanalyse", "Konkurrenz analysieren und differenzieren", "Business", listOf("konkurrenz", "wettbewerb", "markt", "analyse"), "Analysiere meine Konkurrenz", "Wettbewerbsanalyse: ..."),
    HubSkill("finance", "Finanz-Assistent", "Budgetplanung und Sparen", "Business", listOf("geld", "budget", "sparen", "finanz"), "Erstelle ein Budget", "Dein Monatsbudget..."),

    // === Entwicklung ===
    HubSkill("code_helper", "Code Helper", "Hilft beim Schreiben und Erklaeren von Code", "Entwicklung", listOf("code", "programmierung", "python", "java", "javascript", "function"), "Erklaere diese Funktion", "Hier ist die Erklaerung..."),
    HubSkill("android-dev", "Android Entwicklung", "Production-grade Android App Development", "Entwicklung", listOf("android", "kotlin", "java", "app"), "Hilf mir bei Android Entwicklung", "Guide: ..."),

    // === Kreativitaet ===
    HubSkill("brainstorm", "Brainstorming", "Hilft bei Ideenfindung", "Kreativitaet", listOf("idee", "brainstorm", "kreativ"), "Hilf mir beim Brainstorming", "5 Ideen..."),
    HubSkill("story_writer", "Geschichten-Schreiber", "Kurze Geschichten und Gedichte", "Kreativitaet", listOf("geschichte", "story", "gedicht"), "Schreib eine Geschichte", "Es war einmal..."),
    HubSkill("article-illustrations", "Illustrationen", "Beschreibt Illustrationen fuer Artikel", "Kreativitaet", listOf("bild", "illustration", "grafik"), "Beschreibe eine Illustration fuer...", "Beschreibung: ..."),

    // === Gesundheit ===
    HubSkill("meal_planner", "Essensplaner", "Wochenplan fuer Mahlzeiten", "Gesundheit", listOf("essen", "mahlzeit", "kochen", "rezept"), "Erstelle einen Wochenplan", "Dein Wochenplan..."),
    HubSkill("workout", "Fitness Coach", "Trainingsplaeene", "Gesundheit", listOf("training", "fitness", "sport"), "Erstelle einen Trainingsplan", "Dein Plan..."),
    HubSkill("meditation", "Meditations-Guide", "Meditation und Atemuebungen", "Gesundheit", listOf("meditation", "atmen", "entspannen"), "Fuehr mich durch eine Meditation", "Schliesse die Augen..."),

    // === Bildung ===
    HubSkill("study_buddy", "Lern-Assistent", "Zusammenfassungen und Karteikarten", "Bildung", listOf("lernen", "zusammenfassung", "pruefung"), "Fasse dieses Thema zusammen", "Zusammenfassung..."),
    HubSkill("language_tutor", "Sprach-Tutor", "Fremdsprachen lernen", "Bildung", listOf("sprache", "lernen", "englisch"), "Lehr mich Japanisch", "Grundlagen..."),

    // === Alltag ===
    HubSkill("email_writer", "E-Mail Schreiber", "Professionelle E-Mails", "Alltag", listOf("email", "mail", "nachricht"), "Schreib mir eine E-Mail", "Entwurf..."),
    HubSkill("shopping_list", "Einkaufsliste", "Listen basierend auf Rezepten", "Alltag", listOf("einkauf", "liste", "supermarkt"), "Einkaufsliste fuer Pasta", "500g Hackfleisch..."),
    HubSkill("travel_planner", "Reiseplaner", "Reisen mit Budget", "Reisen", listOf("reise", "urlaub", "flug", "hotel"), "Plan mir eine Reise", "Dein Plan..."),
    HubSkill("diy_helper", "DIY-Assistent", "Heimprojekte und Reparaturen", "Alltag", listOf("diy", "reparatur", "bauen"), "Wie repariere ich...", "Schritt-fuer-Schritt..."),
    HubSkill("recipe_finder", "Rezept-Finder", "Rezepte nach Zutaten", "Kochen", listOf("rezept", "kochen", "zutat"), "Was kann ich mit Tomaten kochen?", "Tomaten-Kaprese..."),
    HubSkill("pet_care", "Haustier-Berater", "Pflege-Tipps", "Haustiere", listOf("haustier", "hund", "katze"), "Wie pflege ich meinen Hund", "Dein Hund braucht..."),
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
