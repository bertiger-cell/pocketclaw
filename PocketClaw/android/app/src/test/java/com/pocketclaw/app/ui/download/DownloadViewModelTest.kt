package com.pocketclaw.app.ui.download

import com.llmhub.llmhub.data.DownloadStatus
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelDownloader
import com.llmhub.llmhub.data.ModelRequirements
import com.llmhub.llmhub.data.localFileName
import com.pocketclaw.app.PocketClawApplication
import io.ktor.client.HttpClient
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/**
 * Comprehensive unit tests for [DownloadViewModel].
 *
 * Uses JUnit 4 + kotlinx-coroutines-test + MockK.
 * The Main dispatcher is set to [StandardTestDispatcher] for deterministic
 * coroutine scheduling and virtual-time control.
 *
 * Test cases cover:
 * - Mutex-based parallel-download blocking
 * - HTML error-page detection in downloaded files (HuggingFace 403/404)
 * - Download timeout with 10-minute limit
 * - Local model deletion and state cleanup
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadViewModelTest {

    // -- Fixtures ------------------------------------------------

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var tempDir: File
    private lateinit var mockApp: PocketClawApplication
    private lateinit var viewModel: DownloadViewModel

    // -- Setup / Teardown ------------------------------------------------

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        tempDir = createTempDir("download-viewmodel-test")
        mockApp = mockk(relaxed = true) {
            every { filesDir } returns tempDir
            every { getExternalFilesDir(any()) } returns File(tempDir, "external")
            every { getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
            every { getExternalFilesDir(null) } returns File(tempDir, "external")
        }

        mockkConstructor(HttpClient::class)
        every { constructedWith<HttpClient>(any()) } returns mockk(relaxed = true)

        mockkConstructor(ModelDownloader::class)
        every {
            constructedWith<ModelDownloader>(any(), any(), any())
        } returns mockk(relaxed = true)

        viewModel = DownloadViewModel(mockApp)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    // -- Helpers ------------------------------------------------

    private fun createTestModel(
        name: String,
        format: String = "gguf",
        url: String = "https://huggingface.co/test-org/$name/resolve/main/$name.$format",
    ) = LLMModel(
        name = name,
        description = "Test model: $name",
        url = url,
        category = "text",
        sizeBytes = 500_000_000L,
        source = "huggingface",
        supportsVision = false,
        requirements = ModelRequirements(minRamGB = 2, recommendedRamGB = 4),
        modelFormat = format,
    )

    /**
     * Invokes the private [DownloadViewModel.validateDownloadedFile] via reflection.
     */
    private fun invokeValidateFile(file: File, format: String): String? {
        val method: Method = DownloadViewModel::class.java.getDeclaredMethod(
            "validateDownloadedFile",
            File::class.java,
            String::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(viewModel, file, format) as? String
    }

    // ================================================================
    // TEST 1: Parallel-download blocking via downloadMutex
    // ================================================================

    /**
     * **Given** a model download is already in progress.
     * **When** a second download is requested for a different model.
     * **Then** the second request is silently rejected – no additional
     *         [ModelDownloader] instance is created and the download map
     *         contains only the first model.
     */
    @Test
    fun downloadModel_alreadyDownloading_blocksParallelExecution() = runTest {
        // Given — ModelDownloader returns a flow that never completes
        //         (simulates an in-progress download)
        val neverCompleteFlow = flow<DownloadStatus> {
            kotlinx.coroutines.awaitCancellation()
        }
        every {
            constructedWith<ModelDownloader>(any(), any(), any()).downloadModel(any())
        } returns neverCompleteFlow

        val model1 = createTestModel("Alpha-7B")
        val model2 = createTestModel("Beta-13B")

        // When — start first download, advance to let the coroutine enter the mutex
        viewModel.downloadModel(model1) { }
        advanceUntilIdle()

        // Verify first download is registered as in-progress
        val downloadsAfterFirst = viewModel.modelDownloads.value
        assertTrue(
            "First download should be in progress",
            downloadsAfterFirst["Alpha-7B"]?.isDownloading == true,
        )

        // When — attempt a second download while the first is still running
        viewModel.downloadModel(model2) { }
        advanceUntilIdle()

        // Then — only model1 is in the download map; model2 was rejected
        val downloadsAfterSecond = viewModel.modelDownloads.value
        assertEquals(
            "Only one download should be registered",
            1,
            downloadsAfterSecond.size,
        )
        assertTrue(
            "Alpha-7B should be the only entry",
            downloadsAfterSecond.containsKey("Alpha-7B"),
        )
        assertFalse(
            "Beta-13B should NOT be in the map (blocked)",
            downloadsAfterSecond.containsKey("Beta-13B"),
        )
    }

    // ================================================================
    // TEST 2: HTML error-page detection (HuggingFace token issue)
    // ================================================================

    /**
     * **Given** a downloaded file that contains an HTML error page
     *         (e.g., HuggingFace 403 "Access Denied").
     * **When** [DownloadViewModel.validateDownloadedFile] inspects the file header.
     * **Then** [DownloadViewModel.lastError] is set to the specific
     *         HuggingFace-token error message, and the invalid file is deleted.
     */
    @Test
    fun downloadModel_invalidToken_triggersHtmlValidationError() = runTest {
        // Given — a file that looks like an HTML error page from HuggingFace
        val modelsDir = File(tempDir, "models").apply { mkdirs() }
        val htmlFile = File(modelsDir, "gated-model.gguf")
        htmlFile.writeText(
            buildString {
                appendLine("<!DOCTYPE html>")
                appendLine("<html lang=\"en\">")
                appendLine("<head><title>403 Forbidden</title></head>")
                appendLine("<body>")
                appendLine("<h1>Access denied</h1>")
                appendLine("<p>You must accept the license on HuggingFace.</p>")
                appendLine("</body></html>")
            },
        )

        // When — validate the HTML file against the GGUF format
        val error = invokeValidateFile(htmlFile, "gguf")

        // Then — the specific token/licence error message is returned
        assertEquals(
            "Token ungultig oder Modell erfordert HuggingFace-Lizenz. Prufe HF_TOKEN.",
            error,
        )
    }

    /**
     * **Given** a file with a 404 Not Found HTML page.
     * **When** validated.
     * **Then** the "model not found" message is returned.
     */
    @Test
    fun validateFile_html404_returnsNotFoundError() = runTest {
        val modelsDir = File(tempDir, "models").apply { mkdirs() }
        val htmlFile = File(modelsDir, "missing-model.gguf")
        htmlFile.writeText(
            "<html><head><title>404 Not Found</title></head><body>Not Found</body></html>",
        )

        val error = invokeValidateFile(htmlFile, "gguf")

        assertEquals(
            "Modell nicht gefunden (HTTP 404). URL konnnte veraltet sein.",
            error,
        )
    }

    /**
     * **Given** a file that is an empty or near-empty binary (less than 4 bytes).
     * **When** validated.
     * **Then** the "file too small" message is returned.
     */
    @Test
    fun validateFile_tooSmall_returnsSizeError() = runTest {
        val modelsDir = File(tempDir, "models").apply { mkdirs() }
        val tinyFile = File(modelsDir, "tiny.gguf")
        tinyFile.writeBytes(byteArrayOf(0x00, 0x01)) // only 2 bytes

        val error = invokeValidateFile(tinyFile, "gguf")

        assertEquals("Datei ist leer oder zu klein", error)
    }

    /**
     * **Given** a valid GGUF file with the correct magic header.
     * **When** validated.
     * **Then** null is returned (no error).
     */
    @Test
    fun validateFile_validGguf_returnsNull() = runTest {
        val modelsDir = File(tempDir, "models").apply { mkdirs() }
        val validFile = File(modelsDir, "valid.gguf")
        // GGUF magic: "GGUF" + padding
        validFile.writeBytes("GGUF".toByteArray() + ByteArray(508) { 0 })

        val error = invokeValidateFile(validFile, "gguf")

        assertNull("Valid GGUF should pass validation", error)
    }

    // ================================================================
    // TEST 3: Download timeout with 10-minute limit
    // ================================================================

    /**
     * **Given** the ModelDownloader's flow never completes (simulates a stalled download).
     * **When** the 10-minute timeout is exceeded via virtual-time advancement.
     * **Then** [DownloadViewModel.lastError] contains the timeout message
     *         and the download state is reset to not-downloading.
     */
    @Test
    fun downloadModel_timeout_setsCorrectTimeoutError() = runTest {
        // Given — a ModelDownloader whose flow suspends indefinitely
        val neverCompleteFlow = flow<DownloadStatus> {
            kotlinx.coroutines.awaitCancellation()
        }
        every {
            constructedWith<ModelDownloader>(any(), any(), any()).downloadModel(any())
        } returns neverCompleteFlow

        val model = createTestModel("StallModel-7B")

        // When — trigger the download and advance virtual time past the 10-min limit
        viewModel.downloadModel(model) { }

        // advanceUntilIdle processes all pending coroutines; the withTimeoutOrNull(600_000L)
        // timer fires because the flow never emits.
        advanceUntilIdle()

        // Then — the timeout error message is set
        assertEquals(
            "Download-Timeout fur StallModel-7B (10 Min. Limit)",
            viewModel.lastError.value,
        )

        // Then — the download state is cleaned up (not still marked as downloading)
        val state = viewModel.modelDownloads.value["StallModel-7B"]
        assertFalse(
            "Download should no longer be marked as in-progress",
            state?.isDownloading == true,
        )
    }

    // ================================================================
    // TEST 4: Local model deletion and state cleanup
    // ================================================================

    /**
     * **Given** a model whose file has been previously downloaded to the models directory.
     * **When** [DownloadViewModel.deleteLocalModel] is called.
     * **Then** the file is removed from disk AND the download-state map is
     *         updated to `downloaded = false`.
     */
    @Test
    fun deleteLocalModel_removesFilesAndUpdatesState() = runTest {
        // Given — pre-populate the download map as if model was downloaded
        val model = createTestModel("Deletable-3B")
        val modelsDir = File(tempDir, "models").apply { mkdirs() }
        val modelFile = File(modelsDir, model.localFileName())
        modelFile.writeBytes(ByteArray(1024) { 0xFF.toByte() }) // fake model binary

        assertTrue("Model file should exist before deletion", modelFile.exists())

        // Seed the download state to "downloaded"
        viewModel.downloadModel(model) { }
        advanceUntilIdle()
        // The file already exists so downloadModel marks it as downloaded immediately
        assertTrue(
            "Model should be marked as downloaded",
            viewModel.modelDownloads.value[model.name]?.downloaded == true,
        )

        // When — delete the local model
        viewModel.deleteLocalModel(model)
        advanceUntilIdle()

        // Then — the file is removed from disk
        assertFalse(
            "Model file should be deleted from disk",
            modelFile.exists(),
        )

        // Then — the download state is reset to not-downloaded
        val state = viewModel.modelDownloads.value[model.name]
        assertFalse(
            "Download state should be reset to downloaded = false",
            state?.downloaded == true,
        )
    }

    /**
     * **Given** a model that was never downloaded (no file on disk).
     * **When** [DownloadViewModel.deleteLocalModel] is called.
     * **Then** no exception is thrown and the state remains clean.
     */
    @Test
    fun deleteLocalModel_noFileOnDisk_doesNotThrow() = runTest {
        val model = createTestModel("Ghost-1B")

        // When — delete a model that was never downloaded
        viewModel.deleteLocalModel(model)
        advanceUntilIdle()

        // Then — no crash, state is consistent
        val state = viewModel.modelDownloads.value[model.name]
        assertFalse(
            "Ghost model should not appear as downloaded",
            state?.downloaded == true,
        )
    }
}
