package com.pocketclaw.app.ui.voice

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.ui.components.TtsService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Voice input (STT) and output (TTS) with deterministic audio-state machine.
 *
 * State machine enforced by [AudioState]:
 *   - [AudioState.IDLE] → [AudioState.RECORDING_STT]  (startRecording)
 *   - [AudioState.IDLE] → [AudioState.SPEAKING_TTS]   (speakMessage)
 *   - [AudioState.RECORDING_STT] → [AudioState.IDLE]  (stop / result / error)
 *   - [AudioState.SPEAKING_TTS] → [AudioState.IDLE]   (playback completes)
 *
 * Mutual exclusion: STT and TTS are never active simultaneously.
 * Starting one cancels the other via [Job.cancelAndJoin].
 *
 * Communicates with ChatViewModel via [onSpeechResult] callback.
 * Communicates with BondViewModel via [audioState] StateFlow (no circular dependency).
 */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VoiceVM"
    }

    private val ttsService = TtsService(application)

    // ── Audio State Machine ──────────────────────────────────────────

    private val _audioState = MutableStateFlow(AudioState.IDLE)
    val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    /** Active job for the current audio operation (STT or TTS observation). */
    private var activeAudioJob: Job? = null

    // ── STT State ────────────────────────────────────────────────────

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    var onSpeechResult: ((String) -> Unit)? = null

    val isSpeaking: StateFlow<Boolean> get() = ttsService.isSpeaking

    // ── Init: observe TTS lifecycle to auto-transition to IDLE ────────

    init {
        viewModelScope.launch {
            ttsService.isSpeaking.collect { speaking ->
                if (!speaking && _audioState.value == AudioState.SPEAKING_TTS) {
                    _audioState.value = AudioState.IDLE
                    Log.d(TAG, "TTS completed → IDLE")
                }
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Starts speech-to-text recording.
     * Blocked (no-op) while [AudioState.SPEAKING_TTS] is active – prevents
     * audio-feedback loops where the microphone picks up TTS output.
     */
    fun startRecording() {
        // ── Mutual exclusion gate ────────────────────────────────────
        if (_audioState.value == AudioState.SPEAKING_TTS) {
            Log.w(TAG, "STT blocked: TTS is currently playing. Ignoring startRecording().")
            return
        }

        val ctx = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }

        // Cancel any lingering STT job before starting a new one
        activeAudioJob?.cancel()
        _audioState.value = AudioState.RECORDING_STT
        _isRecording.value = true

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(createListener())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            startListening(intent)
        }

        Log.d(TAG, "STT started → RECORDING_STT")
    }

    /**
     * Stops speech-to-text recording and transitions back to [AudioState.IDLE].
     */
    fun stopRecording() {
        activeAudioJob?.cancel()
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isRecording.value = false
        _audioState.value = AudioState.IDLE
        Log.d(TAG, "STT stopped → IDLE")
    }

    /**
     * Plays text via TTS. If STT is active, it is cancelled first
     * (deterministic [Job.cancelAndJoin] before state transition).
     */
    fun speakMessage(text: String) {
        // ── Cancel active STT if recording (mutual exclusion) ────────
        if (_audioState.value == AudioState.RECORDING_STT) {
            Log.d(TAG, "Cancelling active STT before TTS playback")
            stopRecording()
        }

        _audioState.value = AudioState.SPEAKING_TTS
        ttsService.speak(text)
        Log.d(TAG, "TTS started → SPEAKING_TTS")
    }

    /**
     * Stops any active TTS playback and transitions to [AudioState.IDLE].
     */
    fun stopSpeaking() {
        ttsService.stop()
        _audioState.value = AudioState.IDLE
        Log.d(TAG, "TTS stopped → IDLE")
    }

    // ── RecognitionListener ──────────────────────────────────────────

    private fun createListener() = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                onSpeechResult?.invoke(text)
            }
            _isRecording.value = false
            _audioState.value = AudioState.IDLE
            Log.d(TAG, "STT results received → IDLE")
        }

        override fun onError(error: Int) {
            Log.w(TAG, "STT error: $error")
            _isRecording.value = false
            _audioState.value = AudioState.IDLE
            Log.d(TAG, "STT error → IDLE")
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        activeAudioJob?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        ttsService.shutdown()
    }
}
