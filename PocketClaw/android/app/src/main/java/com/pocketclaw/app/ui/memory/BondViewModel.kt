package com.pocketclaw.app.ui.memory

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketclaw.app.PocketClawApplication
import com.pocketclaw.app.ui.voice.AudioState
import com.pocketclaw.claw.bond.BondEngine
import com.pocketclaw.claw.bond.BondGrowth
import com.pocketclaw.claw.bond.BondMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI bond/memory system with audio-aware dispatch.
 *
 * Observes [audioState] from [com.pocketclaw.app.ui.voice.VoiceViewModel] to
 * coordinate heavy memory operations with the audio pipeline:
 *
 * - When [AudioState.RECORDING_STT] is active, all write operations (delete,
 *   upsert, vector compression) are forced onto [Dispatchers.IO] to avoid
 *   CPU stuttering that would degrade microphone input quality.
 * - When [AudioState.IDLE] or [AudioState.SPEAKING_TTS], operations run on
 *   the default dispatcher for lower latency.
 *
 * No circular dependency: [audioState] is a plain [StateFlow] injected via
 * the constructor by [PocketClawApplication].
 */
class BondViewModel(
    application: Application,
    private val audioState: StateFlow<AudioState>,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BondVM"
    }

    private val app = application as PocketClawApplication
    private val bondEngine: BondEngine = app.bondEngine

    // ── Audio-Aware Flag ─────────────────────────────────────────────

    /**
     * `true` when [AudioState.RECORDING_STT] is active. Heavy memory operations
     * use this to decide whether to force [Dispatchers.IO].
     */
    private val _isRecordingActive = MutableStateFlow(false)
    val isRecordingActive: StateFlow<Boolean> = _isRecordingActive.asStateFlow()

    init {
        viewModelScope.launch {
            audioState.collect { state ->
                _isRecordingActive.value = (state == AudioState.RECORDING_STT)
            }
        }
    }

    /**
     * Executes [block] on the appropriate dispatcher based on current audio state.
     * During [AudioState.RECORDING_STT], forces [Dispatchers.IO] to prevent
     * CPU contention with the audio pipeline.
     */
    suspend fun <T> withAudioAwareDispatcher(block: suspend () -> T): T {
        return if (_isRecordingActive.value) {
            withContext(Dispatchers.IO) { block() }
        } else {
            block()
        }
    }

    // ── Bond State Flows (reactive DB queries) ───────────────────────

    val memories: StateFlow<List<BondMemory>> = app.database.bondMemoryDao().allMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val growthState: StateFlow<BondGrowth?> = bondEngine.growthDao.observeGrowth()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val growthStage: StateFlow<Int> = growthState.map { it?.stage ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val interactionCount: StateFlow<Int> = growthState.map { it?.totalInteractions ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ── CRUD (audio-aware) ───────────────────────────────────────────

    fun deleteMemory(memory: BondMemory) {
        viewModelScope.launch {
            withAudioAwareDispatcher {
                try {
                    app.database.bondMemoryDao().delete(memory.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete memory: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Upserts a memory entry. During [AudioState.RECORDING_STT], this is
     * dispatched to IO to prevent main-thread contention.
     */
    fun upsertMemory(memory: BondMemory) {
        viewModelScope.launch {
            withAudioAwareDispatcher {
                try {
                    app.database.bondMemoryDao().upsert(memory)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upsert memory: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Processes a memory compression / consolidation pass.
     * During [AudioState.RECORDING_STT], this is deferred to IO to avoid
     * CPU stuttering in the microphone input pipeline.
     */
    fun compressMemories() {
        viewModelScope.launch {
            withAudioAwareDispatcher {
                try {
                    val all = app.database.bondMemoryDao().getAllMemories()
                    Log.d(TAG, "Memory compression pass: ${all.size} entries (recording=${_isRecordingActive.value})")
                    // Future: vector deduplication, confidence decay, pruning
                } catch (e: Exception) {
                    Log.e(TAG, "Memory compression failed: ${e.message}", e)
                }
            }
        }
    }
}
