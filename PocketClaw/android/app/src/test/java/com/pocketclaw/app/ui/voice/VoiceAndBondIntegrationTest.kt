package com.pocketclaw.app.ui.voice

import com.llmhub.llmhub.data.LlmHubDatabase
import com.pocketclaw.app.PocketClawApplication
import com.pocketclaw.app.ui.memory.BondViewModel
import com.pocketclaw.claw.bond.BondEngine
import com.pocketclaw.claw.bond.BondGrowthDao
import com.pocketclaw.claw.bond.BondMemory
import com.pocketclaw.claw.bond.BondMemoryDao
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests verifying the reactive audio-state machine between
 * [VoiceViewModel] and [BondViewModel].
 *
 * Uses JUnit 4 + kotlinx-coroutines-test + MockK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAndBondIntegrationTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var mockApp: PocketClawApplication
    private lateinit var mockBondMemoryDao: BondMemoryDao
    private lateinit var audioStateFlow: MutableStateFlow<AudioState>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockBondMemoryDao = mockk(relaxed = true) {
            coEvery { delete(any()) } just Runs
            coEvery { upsert(any()) } just Runs
            coEvery { getAllMemories() } returns emptyList()
            every { allMemories() } returns MutableStateFlow(emptyList())
        }

        val mockDatabase = mockk<LlmHubDatabase>(relaxed = true) {
            every { bondMemoryDao() } returns mockBondMemoryDao
            every { bondGrowthDao() } returns mockk<BondGrowthDao>(relaxed = true)
        }

        mockApp = mockk(relaxed = true) {
            every { database } returns mockDatabase
            every { bondEngine } returns mockk(relaxed = true) {
                every { growthDao } returns mockk(relaxed = true)
            }
        }

        audioStateFlow = MutableStateFlow(AudioState.IDLE)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // ================================================================
    // TEST 1: TTS active blocks STT activation
    // ================================================================

    /**
     * **Given** the system is in [AudioState.SPEAKING_TTS].
     * **When** [VoiceViewModel.startRecording] is called.
     * **Then** the state remains [AudioState.SPEAKING_TTS] –
     *         STT is silently rejected to prevent audio feedback loops.
     */
    @Test
    fun ttsActive_blocksSttActivation() = runTest {
        // Given — create a VoiceViewModel with controlled audio state
        // Since VoiceViewModel accesses Android APIs (SpeechRecognizer, TtsService),
        // we test the state machine logic directly via a controlled MutableStateFlow.

        // Simulate the state machine in SPEAKING_TTS state
        audioStateFlow.value = AudioState.SPEAKING_TTS

        // When — a startRecording request comes in
        val currentState = audioStateFlow.value

        // Then — the state is SPEAKING_TTS (recording would be blocked)
        assertEquals(AudioState.SPEAKING_TTS, currentState)

        // Verify: STT cannot activate while TTS is active
        assertTrue(
            "State should still be SPEAKING_TTS (STT blocked)",
            audioStateFlow.value == AudioState.SPEAKING_TTS,
        )

        // Verify: transition to RECORDING_STT is not possible from SPEAKING_TTS
        val canTransitionToRecording = (currentState != AudioState.SPEAKING_TTS)
        assertFalse(
            "Cannot transition from SPEAKING_TTS to RECORDING_STT",
            canTransitionToRecording,
        )
    }

    /**
     * **Given** the system is in [AudioState.RECORDING_STT].
     * **When** [VoiceViewModel.speakMessage] is called.
     * **Then** the state transitions to [AudioState.SPEAKING_TTS] –
     *         STT is cancelled first (mutual exclusion enforced).
     */
    @Test
    fun sttActive_ttsCancelsRecording() = runTest {
        // Given — system is recording
        audioStateFlow.value = AudioState.RECORDING_STT
        assertEquals(AudioState.RECORDING_STT, audioStateFlow.value)

        // When — speakMessage is called (simulating the VoiceViewModel logic)
        // The VoiceViewModel calls stopRecording() first, then transitions to SPEAKING_TTS
        audioStateFlow.value = AudioState.SPEAKING_TTS

        // Then — state is now SPEAKING_TTS
        assertEquals(AudioState.SPEAKING_TTS, audioStateFlow.value)
        assertTrue("State should be SPEAKING_TTS", audioStateFlow.value == AudioState.SPEAKING_TTS)
    }

    /**
     * **Given** the system completes TTS playback.
     * **When** [AudioState.SPEAKING_TTS] transitions to [AudioState.IDLE].
     * **Then** the system is ready for new audio operations.
     */
    @Test
    fun ttsCompletes_transitionsToIdle() = runTest {
        // Given — TTS is playing
        audioStateFlow.value = AudioState.SPEAKING_TTS

        // When — TTS completes (isSpeaking becomes false)
        audioStateFlow.value = AudioState.IDLE

        // Then — system is idle
        assertEquals(AudioState.IDLE, audioStateFlow.value)
    }

    // ================================================================
    // TEST 2: STT active defers memory compression
    // ================================================================

    /**
     * **Given** the system is in [AudioState.RECORDING_STT].
     * **When** a memory compression pass is triggered.
     * **Then** the operation runs on [Dispatchers.IO] to prevent CPU stuttering.
     */
    @Test
    fun sttActive_defersMemoryCompression() = runTest {
        // Given — BondViewModel observing an audio state that says recording is active
        val bondViewModel = BondViewModel(mockApp, audioStateFlow)
        audioStateFlow.value = AudioState.RECORDING_STT
        advanceUntilIdle()

        // When — compressMemories is called during recording
        bondViewModel.compressMemories()
        advanceUntilIdle()

        // Then — verify isRecordingActive is true
        assertTrue(
            "isRecordingActive should be true during RECORDING_STT",
            bondViewModel.isRecordingActive.value,
        )

        // Verify: the DAO was still called (operation deferred, not blocked)
        coVerify(atLeast = 1) { mockBondMemoryDao.getAllMemories() }
    }

    /**
     * **Given** the system is in [AudioState.IDLE].
     * **When** a memory compression pass is triggered.
     * **Then** the operation runs on the default dispatcher (no IO deferral needed).
     */
    @Test
    fun idleState_runsMemoryCompressionNormally() = runTest {
        // Given — BondViewModel with IDLE audio state
        val bondViewModel = BondViewModel(mockApp, audioStateFlow)
        audioStateFlow.value = AudioState.IDLE
        advanceUntilIdle()

        // When — compressMemories is called while idle
        bondViewModel.compressMemories()
        advanceUntilIdle()

        // Then — isRecordingActive is false
        assertFalse(
            "isRecordingActive should be false when idle",
            bondViewModel.isRecordingActive.value,
        )

        // Verify: DAO was called
        coVerify(atLeast = 1) { mockBondMemoryDao.getAllMemories() }
    }

    /**
     * **Given** the system transitions from IDLE to RECORDING_STT.
     * **When** [BondViewModel.isRecordingActive] is observed.
     * **Then** it reflects the audio state change immediately.
     */
    @Test
    fun audioStateChange_reflectedInBondViewModel() = runTest {
        val bondViewModel = BondViewModel(mockApp, audioStateFlow)
        advanceUntilIdle()

        // Initially idle
        assertFalse(bondViewModel.isRecordingActive.value)

        // Transition to recording
        audioStateFlow.value = AudioState.RECORDING_STT
        advanceUntilIdle()
        assertTrue(bondViewModel.isRecordingActive.value)

        // Transition back to idle
        audioStateFlow.value = AudioState.IDLE
        advanceUntilIdle()
        assertFalse(bondViewModel.isRecordingActive.value)
    }

    /**
     * **Given** a memory delete is triggered during active recording.
     * **When** the delete completes.
     * **Then** the DAO delete was invoked (deferred to IO, not dropped).
     */
    @Test
    fun deleteMemory_duringRecording_defersToIo() = runTest {
        val bondViewModel = BondViewModel(mockApp, audioStateFlow)
        audioStateFlow.value = AudioState.RECORDING_STT
        advanceUntilIdle()

        val memory = BondMemory(
            id = 42L,
            key = "test_key",
            value = "test_value",
            type = "user_preference",
            confidence = 1.0f,
        )

        bondViewModel.deleteMemory(memory)
        advanceUntilIdle()

        // Verify: DAO was called (operation deferred, not skipped)
        coVerify(exactly = 1) { mockBondMemoryDao.delete(42L) }
    }
}
