package com.pocketclaw.app.api

import com.pocketclaw.app.data.Preferences
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the cloud inference providers:
 * 1. OpenRouterProvider – isReady logic, API key validation
 * 2. OllamaProvider – isReady logic, host configuration
 * 3. CloudInferenceProvider – unified interface contract
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudProviderTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // ================================================================
    // TEST 1: OpenRouterProvider.isReady()
    // ================================================================

    @Test
    fun openRouter_isReady_noKey_returnsFalse() {
        mockkObject(Preferences)
        every { Preferences.openRouterApiKey } returns ""
        assertFalse("Should not be ready without API key", OpenRouterProvider.isReady())
    }

    @Test
    fun openRouter_isReady_withKey_returnsTrue() {
        mockkObject(Preferences)
        every { Preferences.openRouterApiKey } returns "sk-or-v1-test123"
        assertTrue("Should be ready with API key", OpenRouterProvider.isReady())
    }

    @Test
    fun openRouter_isReady_whitespaceKey_returnsFalse() {
        mockkObject(Preferences)
        every { Preferences.openRouterApiKey } returns "   "
        assertFalse("Whitespace-only key should not be ready", OpenRouterProvider.isReady())
    }

    // ================================================================
    // TEST 2: OpenRouterProvider.displayName
    // ================================================================

    @Test
    fun openRouter_displayName_isCorrect() {
        assertEquals("OpenRouter", OpenRouterProvider.displayName)
    }

    // ================================================================
    // TEST 3: OllamaProvider.isReady()
    // ================================================================

    @Test
    fun ollama_isReady_noHost_returnsFalse() {
        mockkObject(Preferences)
        every { Preferences.ollamaHost } returns ""
        assertFalse("Should not be ready without host", OllamaProvider.isReady())
    }

    @Test
    fun ollama_isReady_withHost_returnsTrue() {
        mockkObject(Preferences)
        every { Preferences.ollamaHost } returns "http://localhost:11434"
        assertTrue("Should be ready with host", OllamaProvider.isReady())
    }

    // ================================================================
    // TEST 4: OllamaProvider.displayName
    // ================================================================

    @Test
    fun ollama_displayName_isCorrect() {
        assertEquals("Ollama", OllamaProvider.displayName)
    }

    // ================================================================
    // TEST 5: Interface contract
    // ================================================================

    @Test
    fun bothProviders_implementCloudInferenceProvider() {
        assertTrue(OpenRouterProvider is CloudInferenceProvider)
        assertTrue(OllamaProvider is CloudInferenceProvider)
    }

    // ================================================================
    // TEST 6: Combined state test
    // ================================================================

    @Test
    fun providers_respectPreferencesState() {
        mockkObject(Preferences)

        every { Preferences.openRouterApiKey } returns ""
        every { Preferences.ollamaHost } returns ""
        assertFalse(OpenRouterProvider.isReady())
        assertFalse(OllamaProvider.isReady())

        every { Preferences.openRouterApiKey } returns "sk-or-v1-test"
        every { Preferences.ollamaHost } returns ""
        assertTrue(OpenRouterProvider.isReady())
        assertFalse(OllamaProvider.isReady())

        every { Preferences.ollamaHost } returns "http://localhost:11434"
        assertTrue(OpenRouterProvider.isReady())
        assertTrue(OllamaProvider.isReady())
    }
}
