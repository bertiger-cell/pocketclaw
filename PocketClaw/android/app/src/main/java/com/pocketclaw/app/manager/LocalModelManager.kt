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
        
        // /sdcard/Download/
        paths.add(Pair(
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/qwen3-1.7b-instruct-q8_0.gguf",
            "📥 Downloads/qwen3-1.7b-instruct-q8_0.gguf"
        ))
        
        // /sdcard/Documents/models/
        paths.add(Pair(
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)}/models/qwen3-1.7b-instruct-q8_0.gguf",
            "📄 Documents/models/qwen3-1.7b-instruct-q8_0.gguf"
        ))
        
        // /sdcard/models/
        paths.add(Pair(
            "${Environment.getExternalStorageDirectory()}/models/qwen3-1.7b-instruct-q8_0.gguf",
            "📁 /models/qwen3-1.7b-instruct-q8_0.gguf"
        ))
        
        // /storage/emulated/0/PocketClaw/models/
        paths.add(Pair(
            "${Environment.getExternalStorageDirectory()}/PocketClaw/models/qwen3-1.7b-instruct-q8_0.gguf",
            "🦞 PocketClaw/models/qwen3-1.7b-instruct-q8_0.gguf"
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