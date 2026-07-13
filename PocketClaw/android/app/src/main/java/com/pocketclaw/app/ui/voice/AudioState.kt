package com.pocketclaw.app.ui.voice

/**
 * Deterministic audio-focus state machine shared between [VoiceViewModel] and [com.pocketclaw.app.ui.memory.BondViewModel].
 *
 * Transitions:
 *   IDLE → RECORDING_STT   (startRecording)
 *   IDLE → SPEAKING_TTS    (speakMessage)
 *   RECORDING_STT → IDLE   (stopRecording / onResults / onError)
 *   SPEAKING_TTS → IDLE    (TTS playback completes)
 *
 * Mutual exclusion: RECORDING_STT and SPEAKING_TTS are never active simultaneously.
 * The VoiceViewModel enforces this by cancelling the outgoing operation before
 * entering the new state.
 */
enum class AudioState {
    /** No audio activity – safe for heavy background work. */
    IDLE,
    /** Speech-to-text is actively capturing microphone input. */
    RECORDING_STT,
    /** Text-to-speech is playing audio output. */
    SPEAKING_TTS,
}
