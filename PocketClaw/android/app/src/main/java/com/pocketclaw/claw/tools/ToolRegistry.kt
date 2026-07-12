package com.pocketclaw.claw.tools

import android.content.Context

enum class RiskLevel { L0_READ, L1_WRITE, L2_DESTRUCTIVE, L3_SYSTEM }

interface Tool {
    val id: String
    val name: String
    val description: String
    val riskLevel: RiskLevel
    val paramHint: String

    suspend fun execute(args: String, context: Context): ToolResult

    fun summarize(raw: String, budget: Int): String {
        return if (raw.length <= budget) raw
        else raw.take(budget - 20) + "...(${raw.length} chars total)"
    }
}

data class ToolResult(
    val success: Boolean,
    val output: String,
    val toolId: String,
    val args: String,
)

object ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.id] = tool
    }

    fun get(id: String): Tool? = tools[id]

    fun all(): List<Tool> = tools.values.toList()

    fun buildToolListPrompt(): String {
        if (tools.isEmpty()) return ""
        return buildString {
            append("## Verfügbare Werkzeuge\n")
            for (tool in tools.values) {
                append("- ${tool.id}(${tool.paramHint}): ${tool.description}\n")
            }
            append("\nAufruf-Format: [T:tool_id:argument] (eigene Zeile, am Ende der Antwort)\n")
            append("Regeln: Maximal 1 Werkzeug pro Antwort; Text zuerst, dann Werkzeug; wenn nicht nötig, weglassen.\n")
        }
    }
}
