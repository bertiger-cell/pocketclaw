package com.pocketclaw.app.ui.chat

import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelRequirements
import com.llmhub.llmhub.inference.InferenceService
import com.llmhub.llmhub.repository.ChatRepository
import com.pocketclaw.app.PocketClawApplication
import com.pocketclaw.claw.bond.BondEngine
import com.pocketclaw.claw.bond.BondGrowthDao
import com.pocketclaw.claw.tools.ToolExecutor
import com.pocketclaw.claw.tools.ToolParser
import com.pocketclaw.claw.tools.ToolRegistry
import com.pocketclaw.claw.tools.ToolResult
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Unit tests for [ChatViewModel] covering:
 * 1. Backend-switching resource cleanup (native memory leak protection)
 * 2. Tool-call recursion depth guard (chain-of-thought bounding)
 *
 * Uses JUnit 4 + kotlinx-coroutines-test + MockK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var mockApp: PocketClawApplication
    private lateinit var mockInferenceService: InferenceService
    private lateinit var mockToolExecutor: ToolExecutor
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockInferenceService = mockk(relaxed = true)
        mockToolExecutor = mockk(relaxed = true)

        val mockChatRepository = mockk<ChatRepository>(relaxed = true) {
            coEvery { createNewChat(any(), any(), any()) } returns "test-chat-id"
        }
        val mockBondEngine = mockk<BondEngine>(relaxed = true) {
            coEvery { getMemoriesForPrompt() } returns emptyList()
            coEvery { getGrowthStage() } returns 0
            coEvery { processResponse(any()) } returnsArgument 0
            every { growthDao } returns mockk(relaxed = true)
        }

        val mockDatabase = mockk<com.llmhub.llmhub.data.LlmHubDatabase>(relaxed = true) {
            every { customSkillDao() } returns mockk(relaxed = true) {
                coEvery { enabledSkills() } returns emptyList()
            }
            every { bondMemoryDao() } returns mockk(relaxed = true)
            every { bondGrowthDao() } returns mockk<BondGrowthDao>(relaxed = true)
            every { scheduledTaskDao() } returns mockk(relaxed = true)
        }
        val mockDownloadVM = mockk<com.pocketclaw.app.ui.download.DownloadViewModel>(relaxed = true) {
            every { isModelLoaded } returns mockk { every { value } returns false }
        }

        mockApp = mockk(relaxed = true) {
            every { inferenceService } returns mockInferenceService
            every { chatRepository } returns mockChatRepository
            every { bondEngine } returns mockBondEngine
            every { toolExecutor } returns mockToolExecutor
            every { database } returns mockDatabase
            every { downloadViewModel } returns mockDownloadVM
        }

        viewModel = ChatViewModel(mockApp)
        advanceUntilIdle()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // ================================================================
    // TEST 1: Backend release on switch
    // ================================================================

    /**
     * **Given** a ChatViewModel with an active inference backend.
     * **When** [ChatViewModel.releaseCurrentBackend] is called.
     * **Then** [InferenceService.unloadModel] is called exactly once,
     *         releasing native (C/C++) memory from MediaPipe/Nexa/ONNX/LiteRT-LM.
     */
    @Test
    fun switchBackend_releasesPreviousBackendImmediately() = runTest {
        // Given — a ChatViewModel with a mocked inference service
        verify(exactly = 0) { mockInferenceService wasNot Called }

        // When — release the current backend
        viewModel.releaseCurrentBackend()
        advanceUntilIdle()

        // Then — unloadModel was called exactly once
        coVerify(exactly = 1) { mockInferenceService.unloadModel() }
    }

    /**
     * **Given** a ChatViewModel with a backend that throws during release.
     * **When** [ChatViewModel.releaseCurrentBackend] is called.
     * **Then** the exception is caught silently (no crash) and the ViewModel remains usable.
     */
    @Test
    fun switchBackend_exceptionDuringRelease_doesNotCrash() = runTest {
        // Given — inferenceService.unloadModel() throws
        coEvery { mockInferenceService.unloadModel() } throws RuntimeException("Native crash")

        // When — release is called (should not throw)
        viewModel.releaseCurrentBackend()
        advanceUntilIdle()

        // Then — no exception propagated, ViewModel still functional
        viewModel.addSystemMessage("After failed release")
        val lastMsg = viewModel.messages.value.lastOrNull()
        assertEquals("After failed release", lastMsg?.text)
    }

    // ================================================================
    // TEST 2: Tool-call recursion depth guard
    // ================================================================

    /**
     * **Given** a model that keeps generating tool calls in a chain-of-thought loop.
     * **When** the tool-call chain exceeds [MAX_TOOL_LOOPS] (5) iterations.
     * **Then** execution is halted with a user-visible error message and
     *         the depth counter is reset for the next user message.
     */
    @Test
    fun toolLoop_exceedsMaxDepth_haltsExecutionAndEmitsError() = runTest {
        // ── Setup: mock the tool-call chain ──────────────────────
        val toolCallOutput = "[T:web_search:find latest news about android]"
        val toolCallParsed = listOf(
            ToolParser.ParsedCall(
                toolId = "web_search",
                args = "find latest news about android",
                rawMarker = toolCallOutput,
            ),
        )

        mockkObject(ToolParser)
        every { ToolParser.parse(any()) } returns toolCallParsed
        every { ToolParser.stripToolMarkers(any()) } returns "find latest news about android"

        mockkObject(ToolRegistry)
        every { ToolRegistry.get("web_search") } returns mockk(relaxed = true) {
            every { summarize(any(), any()) } returns "search results"
        }

        coEvery { mockToolExecutor.execute(any(), any()) } returns ToolResult(
            success = true,
            output = "Found 10 articles",
            toolId = "web_search",
            args = "find latest news about android",
        )

        // ── Execute: call handleToolCalls 6 times (exceeds MAX_TOOL_LOOPS = 5) ─
        // Each invocation simulates one step in the recursive chain.
        val handleToolCallsMethod = ChatViewModel::class.java.getDeclaredMethod(
            "handleToolCalls",
            List::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        handleToolCallsMethod.isAccessible = true

        for (i in 1..6) {
            handleToolCallsMethod.invoke(
                viewModel,
                toolCallParsed,
                toolCallOutput,
                "msg-$i",
                "find android news",
            )
            advanceUntilIdle()
        }

        // ── Verify: error message about depth limit ──
        val errorMessages = viewModel.messages.value.filter { msg ->
            msg.text.contains("Tool-Aufruf-Limit überschritten")
        }
        assertTrue(
            "Expected a depth-limit error after 6 iterations, got: ${viewModel.messages.value.map { it.text.take(60) }}",
            errorMessages.isNotEmpty(),
        )
        assertTrue(
            "Error should mention '5 Durchläufe' but was: ${errorMessages.last().text}",
            errorMessages.last().text.contains("5 Durchläufe"),
        )
    }

    /**
     * **Given** a tool-call chain that produces exactly 5 iterations (at the limit).
     * **When** the 5th iteration completes successfully.
     * **Then** no error is emitted (the guard fires on the 6th call, not the 5th).
     */
    @Test
    fun toolLoop_exactlyAtMaxDepth_noErrorEmitted() = runTest {
        val toolCallOutput = "[T:web_search:test]"
        val toolCallParsed = listOf(
            ToolParser.ParsedCall("web_search", "test", toolCallOutput),
        )

        mockkObject(ToolParser)
        every { ToolParser.parse(any()) } returns toolCallParsed
        every { ToolParser.stripToolMarkers(any()) } returns "test"

        mockkObject(ToolRegistry)
        every { ToolRegistry.get("web_search") } returns mockk(relaxed = true) {
            every { summarize(any(), any()) } returns "ok"
        }

        coEvery { mockToolExecutor.execute(any(), any()) } returns ToolResult(
            success = true, output = "result", toolId = "web_search", args = "test",
        )

        val handleToolCallsMethod = ChatViewModel::class.java.getDeclaredMethod(
            "handleToolCalls",
            List::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        handleToolCallsMethod.isAccessible = true

        for (i in 1..5) {
            handleToolCallsMethod.invoke(
                viewModel, toolCallParsed, toolCallOutput, "msg-$i", "test query",
            )
            advanceUntilIdle()
        }

        // Verify: no error about depth limit
        val errorMessages = viewModel.messages.value.filter { msg ->
            msg.text.contains("Tool-Aufruf-Limit überschritten")
        }
        assertFalse(
            "No error should appear at exactly 5 iterations (limit triggers on 6th), " +
                "but got: ${errorMessages.map { it.text }}",
            errorMessages.isNotEmpty(),
        )
    }
}
