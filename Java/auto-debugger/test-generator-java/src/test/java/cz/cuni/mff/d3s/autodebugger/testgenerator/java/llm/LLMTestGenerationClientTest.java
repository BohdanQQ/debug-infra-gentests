package cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm;

import cz.cuni.mff.d3s.autodebugger.testgenerator.common.AnthropicClient;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.LLMConfiguration;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AnthropicClient.
 * These tests focus on the client's behavior without requiring actual API calls.
 */
class LLMTestGenerationClientTest {

    private AnthropicClient client;

    @BeforeEach
    void setUp() {
        client = new AnthropicClient();
    }

    @Test
    void givenValidAnthropicConfig_whenConfiguring_thenSucceeds() {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey("test-api-key")
                .maxTokens(1000)
                .build();

        assertDoesNotThrow(() -> client.configure(config));
    }

    @Test
    void givenMockProvider_whenConfiguring_thenSucceeds() {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("mock")
                .maxTokens(1000)
                .build();

        assertDoesNotThrow(() -> client.configure(config));
    }

    @Test
    void givenNullConfig_whenConfiguring_thenThrowsException() {
        assertThrows(LLMConfigurationException.class, () -> client.configure(null));
    }

    @Test
    void givenMissingApiKey_whenValidating_thenThrowsException() {
        // Only test this if ANTHROPIC_API_KEY environment variable is not set
        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .modelName("claude-sonnet-4-20250514")
                    .apiKey(null)
                    .maxTokens(1000)
                    .build();

            assertThrows(LLMConfigurationException.class, config::validate);
        }
    }

    @Test
    void givenEmptyApiKey_whenValidating_thenThrowsException() {
        // Only test this if ANTHROPIC_API_KEY environment variable is not set
        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .modelName("claude-sonnet-4-20250514")
                    .apiKey("   ")
                    .maxTokens(1000)
                    .build();

            assertThrows(LLMConfigurationException.class, config::validate);
        }
    }

    @Test
    void givenUnconfiguredClient_whenGeneratingCode_thenThrowsException() {
        assertThrows(RuntimeException.class, () -> client.generateCode("test prompt"));
    }

    @Test
    void givenMockProvider_whenGeneratingCode_thenReturnsMockResponse() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("mock")
                .maxTokens(1000)
                .build();

        client.configure(config);

        String prompt = "Generate a test for a simple method";
        String response = client.generateCode(prompt);

        assertNotNull(response);
        assertFalse(response.trim().isEmpty());
        assertTrue(response.contains("@Test"));
        assertTrue(response.contains("MockGeneratedTest"));
        assertTrue(response.contains("package com.example.generated"));
    }

    @Test
    void givenMockProvider_whenGeneratingCode_thenResponseContainsExpectedElements() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("mock")
                .build();

        client.configure(config);

        String response = client.generateCode("test prompt");

        // Verify the mock response contains expected JUnit elements
        assertTrue(response.contains("import org.junit.jupiter.api.Test"));
        assertTrue(response.contains("import static org.junit.jupiter.api.Assertions.*"));
        assertTrue(response.contains("public class MockGeneratedTest"));
        assertTrue(response.contains("@Test"));
        assertTrue(response.contains("void testMockMethod()"));
        assertTrue(response.contains("void testAnotherMockMethod()"));
        assertTrue(response.contains("assertTrue"));
        assertTrue(response.contains("assertNotNull"));
    }

    @Test
    void givenLLMConfiguration_whenValidating_thenValidatesCorrectly() {
        // Test valid configuration
        LLMConfiguration validConfig = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey("test-key")
                .maxTokens(1000)
                .build();

        assertDoesNotThrow(validConfig::validate);

        // Test missing API key for non-mock model (only fails if no env var)
        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            LLMConfiguration missingApiKey = LLMConfiguration.builder()
                    .modelName("claude-sonnet-4-20250514")
                    .apiKey(null)
                    .build();

            assertThrows(LLMConfigurationException.class, missingApiKey::validate);
        }

        // Test invalid max tokens
        LLMConfiguration invalidMaxTokens = LLMConfiguration.builder()
                .apiKey("test-key")
                .maxTokens(-1)
                .build();

        assertThrows(LLMConfigurationException.class, invalidMaxTokens::validate);
    }

    @Test
    void givenMockModel_whenCheckingApiKeyRequirement_thenDoesNotRequireApiKey() {
        LLMConfiguration mockConfig = LLMConfiguration.builder()
                .modelName("mock")
                .apiKey(null) // Should be fine for mock model
                .build();

        assertDoesNotThrow(mockConfig::validate);
        assertFalse(mockConfig.requiresApiKey());
        assertTrue(mockConfig.isMockModel());
    }

    @Test
    void givenClaudeModel_whenCheckingApiKeyRequirement_thenRequiresApiKey() {
        LLMConfiguration claudeConfig = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey("test-key")
                .build();

        assertTrue(claudeConfig.requiresApiKey());
        assertFalse(claudeConfig.isMockModel());
    }

    @Test
    void givenDifferentApiKeyConfigurations_whenHandlingEffectiveApiKey_thenHandlesCorrectly() {
        // Test explicit API key
        LLMConfiguration explicitConfig = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey("explicit-key")
                .build();

        assertEquals("explicit-key", explicitConfig.getEffectiveApiKey());

        // Test mock model returns null
        LLMConfiguration mockConfig = LLMConfiguration.builder()
                .modelName("mock")
                .build();

        assertNull(mockConfig.getEffectiveApiKey());
    }
}
