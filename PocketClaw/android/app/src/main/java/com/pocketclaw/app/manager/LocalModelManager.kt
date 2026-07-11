package com.pocketclaw.app.manager

import android.content.Context
import android.os.Environment
import java.io.File

object LocalModelManager {
    
    /**
     * Check if model exists at given path
     */
    fun modelExists(modelPath: String): Boolean {
        val file = File(modelPath)
        return file.exists() && file.isFile && file.canRead()
    }
    
    /**
     * Get common storage paths where user might put model
     */
    fun getCommonModelPaths(): List<Pair<String, String>> {
        val paths = mutableListOf<Pair<String, String>>()
        
        // /sdcard/Download/ - Qwen3
        paths.add(Pair(
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            "📥 Downloads/Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        ))
        
        // /sdcard/Download/ - Gemma
        paths.add(Pair(
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/google_gemma-3-1b-it-Q4_K_M.gguf",
            "📥 Downloads/google_gemma-3-1b-it-Q4_K_M.gguf"
        ))
        
        // /sdcard/Documents/models/
        paths.add(Pair(
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)}/models/Qwen3-1.7B-Q8_0.gguf",
            "📄 Documents/models/Qwen3-1.7B-Q8_0.gguf"
        ))
        
        // /sdcard/models/
        paths.add(Pair(
            "${Environment.getExternalStorageDirectory()}/models/Qwen3-1.7B-Q8_0.gguf",
            "📁 /models/Qwen3-1.7B-Q8_0.gguf"
        ))
        
        // /storage/emulated/0/PocketClaw/models/
        paths.add(Pair(
            "${Environment.getExternalStorageDirectory()}/PocketClaw/models/Qwen3-1.7B-Q8_0.gguf",
            "🦞 PocketClaw/models/Qwen3-1.7B-Q8_0.gguf"
        ))
        
        return paths
    }
    
    /**
     * Get first existing model from common paths
     */
    fun findAvailableModel(): File? {
        return getCommonModelPaths()
            .map { (path, _) -> File(path) }
            .firstOrNull { it.exists() && it.isFile }
    }
    
    /**
     * Get model file size in GB
     */
    fun getModelSizeGB(modelPath: String): Double {
        val file = File(modelPath)
        return if (file.exists()) {
            file.length() / (1024.0 * 1024.0 * 1024.0)
        } else {
            0.0
        }
    }
}