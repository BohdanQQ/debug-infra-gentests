package cz.cuni.mff.d3s.autodebugger.testgenerator.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AnthropicClient.
 * Tests the language-agnostic LLM client functionality.
 */
class AnthropicClientTest {

    private AnthropicClient client;

    @BeforeEach
    void setUp() {
        client = new AnthropicClient();
    }

    @Test
    void givenNullConfiguration_whenConfiguring_thenThrowsException() {
        assertThrows(Exception.class, () -> client.configure(null));
    }

    @Test
    void givenValidConfiguration_whenConfiguring_thenSucceeds() {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey("test-api-key")
                .maxTokens(1000)
                .temperature(0.7)
                .build();

        assertDoesNotThrow(() -> client.configure(config));
    }

    @Test
    void givenMockModel_whenGeneratingCode_thenReturnsMockResponse() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("mock")
                .maxTokens(1000)
                .temperature(0.7)
                .build();

        client.configure(config);

        String response = client.generateCode("Generate a test for a Calculator class");

        assertNotNull(response);
        assertFalse(response.trim().isEmpty());
        assertTrue(response.contains("MockGeneratedTest"));
        assertTrue(response.contains("@Test"));
    }

    @Test
    void givenUnconfiguredClient_whenGeneratingCode_thenThrowsException() {
        assertThrows(RuntimeException.class, () ->
            client.generateCode("test prompt"));
    }

    @Test
    void givenValidConfiguration_whenValidating_thenSucceeds() {
        // Test valid configuration
        LLMConfiguration validConfig = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey("test-key")
                .maxTokens(1000)
                .temperature(0.7)
                .build();

        assertDoesNotThrow(validConfig::validate);

        // Test missing API key for non-mock model (only fails if no env var)
        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            LLMConfiguration missingApiKey = LLMConfiguration.builder()
                    .modelName("claude-sonnet-4-20250514")
                    .apiKey(null)
                    .build();

            assertThrows(Exception.class, missingApiKey::validate);
        }

        // Test invalid max tokens
        LLMConfiguration invalidMaxTokens = LLMConfiguration.builder()
                .apiKey("test-key")
                .maxTokens(-1)
                .build();

        assertThrows(Exception.class, invalidMaxTokens::validate);

        // Test invalid temperature
        LLMConfiguration invalidTemperature = LLMConfiguration.builder()
                .apiKey("test-key")
                .temperature(1.5)
                .build();

        assertThrows(Exception.class, invalidTemperature::validate);
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
    void givenExplicitApiKey_whenGettingEffectiveApiKey_thenReturnsExplicitKey() {
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

    @Test
    void givenSupportedModelNames_whenValidating_thenAllAreValid() {
        // Test all supported Anthropic model names
        String[] supportedModels = {
            "claude-sonnet-4-5-20250929",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-20250219",
            "claude-3-5-haiku-20241022",
            "claude-3-haiku-20240307",
            "mock"
        };

        for (String model : supportedModels) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .modelName(model)
                    .apiKey(model.equals("mock") ? null : "test-key")
                    .build();

            assertDoesNotThrow(config::validate,
                "Model " + model + " should be valid");
            assertEquals(model, config.getModelName());
        }
    }

    @Test
    void givenDefaultConfiguration_whenCheckingDefaults_thenUsesAnthropicOptimizedValues() {
        // Test that defaults are optimized for Anthropic Claude
        LLMConfiguration config = LLMConfiguration.builder()
                .apiKey("test-key")
                .build();

        // Default model should be the most capable Claude model (Sonnet 4.5)
        assertEquals("claude-sonnet-4-5-20250929", config.getModelName());

        // Default temperature should be optimized for code generation (0.3)
        assertEquals(0.3, config.getTemperature(), 0.001);

        // Default max tokens should be reasonable for test generation
        assertEquals(4000, config.getMaxTokens());

        // Code generation features should be enabled by default
        assertTrue(config.isEnableIterativeRefinement());
        assertTrue(config.isValidateGeneratedCode());
        assertEquals(3, config.getMaxRefinementIterations());
    }

    @Test
    void givenTemperatureValues_whenValidatingForCodeGeneration_thenValidatesCorrectly() {
        // Test temperature validation with focus on code generation ranges
        double[] validTemperatures = {0.0, 0.1, 0.3, 0.5, 1.0};
        double[] invalidTemperatures = {-0.1, 1.1, 2.0};

        for (double temp : validTemperatures) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .apiKey("test-key")
                    .temperature(temp)
                    .build();

            assertDoesNotThrow(config::validate,
                "Temperature " + temp + " should be valid");
        }

        for (double temp : invalidTemperatures) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .apiKey("test-key")
                    .temperature(temp)
                    .build();

            assertThrows(Exception.class, config::validate,
                "Temperature " + temp + " should be invalid");
        }
    }

    @Test
    void givenTokenLimits_whenValidatingForClaude_thenValidatesWithinClaudeLimits() {
        // Test max tokens validation within Claude's limits
        int[] validTokenLimits = {1, 1000, 4000, 8000};
        int[] invalidTokenLimits = {0, -1, -100};

        for (int tokens : validTokenLimits) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .apiKey("test-key")
                    .maxTokens(tokens)
                    .build();

            assertDoesNotThrow(config::validate,
                "Max tokens " + tokens + " should be valid");
        }

        for (int tokens : invalidTokenLimits) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .apiKey("test-key")
                    .maxTokens(tokens)
                    .build();

            assertThrows(Exception.class, config::validate,
                "Max tokens " + tokens + " should be invalid");
        }
    }
}
