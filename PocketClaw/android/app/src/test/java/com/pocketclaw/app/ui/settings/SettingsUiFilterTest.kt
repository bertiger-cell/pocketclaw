package com.pocketclaw.app.ui.settings

import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelRequirements
import com.llmhub.llmhub.inference.InferenceService
import com.llmhub.llmhub.repository.ChatRepository
import com.pocketclaw.app.PocketClawApplication
import com.pocketclaw.claw.security.AuditLog
import com.pocketclaw.claw.skills.CustomSkillDao
import com.pocketclaw.app.data.ScheduledTaskDao
import com.pocketclaw.app.data.WorkspaceDao
import com.pocketclaw.app.ui.download.DownloadViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Unit tests for the device-profile filtering logic in [SettingsViewModel].
 *
 * Verifies:
 * 1. [selectDeviceProfile] toggles correctly (click same = NONE).
 * 2. [filteredModels] produces the exact model-format set per profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsUiFilterTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var mockDownloadVM: DownloadViewModel
    private lateinit var mockApp: PocketClawApplication
    private lateinit var viewModel: SettingsViewModel

    // ── Fixtures: test models in various formats ──────────────────────

    private val taskModel = createModel("MediaPipe-Task", "task")
    private val litertlmModel = createModel("Qwen3-LiteRT", "litertlm")
    private val ggufModel = createModel("Phi-3-GGUF", "gguf")
    private val onnxModel = createModel("BERT-ONNX", "onnx")
    private val tfliteModel = createModel("TFLite-Small", "tflite")
    private val embeddingModel = createModel("Embedding-Model", "embedding")

    private val allTestModels = listOf(
        taskModel, litertlmModel, ggufModel, onnxModel, tfliteModel, embeddingModel,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockDownloadVM = mockk(relaxed = true) {
            every { allLocalModels } returns MutableStateFlow(allTestModels)
            every { isModelLoaded } returns MutableStateFlow(false)
            every { currentModelName } returns MutableStateFlow("")
            every { modelDownloads } returns MutableStateFlow(emptyMap())
            every { modelLoading } returns MutableStateFlow(false)
            every { lastError } returns MutableStateFlow(null)
        }

        mockApp = mockk(relaxed = true) {
            every { downloadViewModel } returns mockDownloadVM
            every { inferenceService } returns mockk<InferenceService>(relaxed = true)
            every { database } returns mockk(relaxed = true) {
                every { customSkillDao() } returns mockk<CustomSkillDao>(relaxed = true)
                every { scheduledTaskDao() } returns mockk<ScheduledTaskDao>(relaxed = true)
                every { workspaceDao() } returns mockk<WorkspaceDao>(relaxed = true)
            }
            every { auditLog } returns AuditLog()
        }

        viewModel = SettingsViewModel(mockApp)
        advanceUntilIdle()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun createModel(name: String, format: String) = LLMModel(
        name = name,
        description = "Test: $name",
        url = "https://example.com/$name.$format",
        category = "text",
        sizeBytes = 500_000_000L,
        source = "test",
        supportsVision = false,
        requirements = ModelRequirements(minRamGB = 2, recommendedRamGB = 4),
        modelFormat = format,
    )

    // ================================================================
    // TEST 1: selectDeviceProfile toggling
    // ================================================================

    /**
     * **Given** the current profile is NONE.
     * **When** [selectDeviceProfile] is called with GALAXY_NOTE_20.
     * **Then** the profile changes to GALAXY_NOTE_20.
     */
    @Test
    fun selectDeviceProfile_clickOnce_selectsProfile() = runTest {
        assertEquals(DeviceProfile.NONE, viewModel.selectedDeviceProfile.value)

        viewModel.selectDeviceProfile(DeviceProfile.GALAXY_NOTE_20)
        advanceUntilIdle()

        assertEquals(DeviceProfile.GALAXY_NOTE_20, viewModel.selectedDeviceProfile.value)
    }

    /**
     * **Given** the current profile is GALAXY_NOTE_20.
     * **When** [selectDeviceProfile] is called with GALAXY_NOTE_20 again.
     * **Then** the profile resets to NONE (toggle behavior).
     */
    @Test
    fun selectDeviceProfile_clickSame_resetsToNone() = runTest {
        viewModel.selectDeviceProfile(DeviceProfile.GALAXY_NOTE_20)
        advanceUntilIdle()
        assertEquals(DeviceProfile.GALAXY_NOTE_20, viewModel.selectedDeviceProfile.value)

        // Click same profile again → toggle to NONE
        viewModel.selectDeviceProfile(DeviceProfile.GALAXY_NOTE_20)
        advanceUntilIdle()

        assertEquals(DeviceProfile.NONE, viewModel.selectedDeviceProfile.value)
    }

    /**
     * **Given** the current profile is GALAXY_NOTE_20.
     * **When** [selectDeviceProfile] is called with XIAOMI_13.
     * **Then** the profile switches to XIAOMI_13 (not toggled off).
     */
    @Test
    fun selectDeviceProfile_switchProfile_selectsNewProfile() = runTest {
        viewModel.selectDeviceProfile(DeviceProfile.GALAXY_NOTE_20)
        advanceUntilIdle()

        viewModel.selectDeviceProfile(DeviceProfile.XIAOMI_13)
        advanceUntilIdle()

        assertEquals(DeviceProfile.XIAOMI_13, viewModel.selectedDeviceProfile.value)
    }

    // ================================================================
    // TEST 2: filteredModels per profile
    // ================================================================

    /**
     * **Given** profile is GALAXY_NOTE_20.
     * **When** filteredModels is collected.
     * **Then** only "task" format models are returned (MediaPipe-optimized).
     */
    @Test
    fun filteredModels_galaxyNote20_returnsTaskFormatOnly() = runTest {
        viewModel.selectDeviceProfile(DeviceProfile.GALAXY_NOTE_20)
        advanceUntilIdle()

        val filtered = viewModel.filteredModels.value
        val formats = filtered.map { it.modelFormat }.toSet()

        assertTrue(
            "Should contain 'task' format, got: $formats",
            formats.contains("task"),
        )
        assertFalse(
            "Should NOT contain 'litertlm' format, got: $formats",
            formats.contains("litertlm"),
        )
        assertFalse(
            "Should NOT contain 'gguf' format, got: $formats",
            formats.contains("gguf"),
        )
        assertEquals("Only 1 task model expected", 1, filtered.size)
        assertEquals(taskModel.name, filtered.first().name)
    }

    /**
     * **Given** profile is XIAOMI_13.
     * **When** filteredModels is collected.
     * **Then** "litertlm", "gguf", and "qnn_npu" formats are returned (Snapdragon).
     */
    @Test
    fun filteredModels_xiaomi13_returnsLitertlmAndGguf() = runTest {
        viewModel.selectDeviceProfile(DeviceProfile.XIAOMI_13)
        advanceUntilIdle()

        val filtered = viewModel.filteredModels.value
        val formats = filtered.map { it.modelFormat }.toSet()

        assertTrue(
            "Should contain 'litertlm', got: $formats",
            formats.contains("litertlm"),
        )
        assertTrue(
            "Should contain 'gguf', got: $formats",
            formats.contains("gguf"),
        )
        assertFalse(
            "Should NOT contain 'task' format, got: $formats",
            formats.contains("task"),
        )
        assertFalse(
            "Should NOT contain 'onnx' format, got: $formats",
            formats.contains("onnx"),
        )
        assertEquals("Expected 2 models (litertlm + gguf)", 2, filtered.size)
    }

    /**
     * **Given** profile is NONE.
     * **When** filteredModels is collected.
     * **Then** the list is empty (accordion collapsed).
     */
    @Test
    fun filteredModels_noneProfile_returnsEmptyList() = runTest {
        // Default state is NONE
        assertEquals(DeviceProfile.NONE, viewModel.selectedDeviceProfile.value)

        val filtered = viewModel.filteredModels.value
        assertTrue(
            "Filtered list should be empty when no profile selected, got: ${filtered.map { it.name }}",
            filtered.isEmpty(),
        )
    }

    /**
     * **Given** profile switches from GALAXY_NOTE_20 to NONE.
     * **When** filteredModels is collected.
     * **Then** the list transitions from task models to empty.
     */
    @Test
    fun filteredModels_toggleOff_clearsList() = runTest {
        viewModel.selectDeviceProfile(DeviceProfile.GALAXY_NOTE_20)
        advanceUntilIdle()
        assertEquals(1, viewModel.filteredModels.value.size)

        viewModel.selectDeviceProfile(DeviceProfile.GALAXY_NOTE_20) // toggle off
        advanceUntilIdle()

        assertTrue(
            "List should be empty after toggle off, got: ${viewModel.filteredModels.value.map { it.name }}",
            viewModel.filteredModels.value.isEmpty(),
        )
    }
}
