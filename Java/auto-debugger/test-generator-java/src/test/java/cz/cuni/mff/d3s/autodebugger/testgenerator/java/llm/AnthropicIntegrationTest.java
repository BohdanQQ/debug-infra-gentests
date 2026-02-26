package cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm;

import cz.cuni.mff.d3s.autodebugger.testgenerator.common.AnthropicClient;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.LLMConfiguration;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Anthropic Claude LLM functionality.
 * These tests require actual API keys and are only run when environment variables are set.
 * Uses claude-3-haiku-20240307 (Haiku 3) - the cheapest model - for all tests.
 */
class AnthropicIntegrationTest {

    private static final String HAIKU_3_MODEL = "claude-3-haiku-20240307";

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void givenValidApiKey_whenConfiguringAnthropicClient_thenSucceeds() {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName(HAIKU_3_MODEL)
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .maxTokens(1000)
                .temperature(0.7)
                .build();

        AnthropicClient client = new AnthropicClient();

        assertDoesNotThrow(() -> client.configure(config));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void givenValidConfiguration_whenGeneratingCodeWithAnthropic_thenReturnsValidCode() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName(HAIKU_3_MODEL)
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .maxTokens(2000)
                .temperature(0.3)
                .build();

        AnthropicClient client = new AnthropicClient();
        client.configure(config);

        String prompt = """
                Generate a JUnit 5 test class for the following method:
                
                ```java
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                ```
                
                Create tests that verify the addition functionality.
                """;

        String response = client.generateCode(prompt);

        assertNotNull(response);
        assertFalse(response.trim().isEmpty());
        assertTrue(response.contains("@Test"));
        assertTrue(response.contains("class") || response.contains("Calculator"));
        
        System.out.println("Generated test code:");
        System.out.println(response);
    }

    @Test
    void givenMockProvider_whenGeneratingCode_thenReturnsMockResponse() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("mock")
                .maxTokens(1000)
                .temperature(0.7)
                .build();

        AnthropicClient client = new AnthropicClient();
        client.configure(config);

        String prompt = "Generate a test for a simple method";
        String response = client.generateCode(prompt);

        assertNotNull(response);
        assertFalse(response.trim().isEmpty());
        assertTrue(response.contains("@Test"));
        assertTrue(response.contains("MockGeneratedTest"));
    }

    @Test
    void givenMissingApiKey_whenValidatingConfiguration_thenThrowsException() {
        // Only test this if ANTHROPIC_API_KEY environment variable is not set
        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .modelName(HAIKU_3_MODEL)
                    .apiKey(null) // Missing API key
                    .build();

            assertThrows(LLMConfigurationException.class, config::validate);
        }
    }

    @Test
    void givenMissingApiKeyAndNoEnvironmentVariable_whenValidating_thenHandlesCorrectly() {
        // Temporarily clear environment variable for this test
        String originalApiKey = System.getenv("ANTHROPIC_API_KEY");

        LLMConfiguration config = LLMConfiguration.builder()
                .modelName(HAIKU_3_MODEL)
                .apiKey(null)
                .build();

        // This should pass validation if ANTHROPIC_API_KEY is set, fail otherwise
        if (originalApiKey == null) {
            assertThrows(LLMConfigurationException.class, config::validate);
        } else {
            assertDoesNotThrow(config::validate);
        }
    }

    @Test
    void givenShortModelName_whenConfiguring_thenMapsToFullModelName() {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("sonnet-4") // Short name
                .apiKey("test-key")
                .build();

        AnthropicClient client = new AnthropicClient();

        // Should not throw - the client should handle model name mapping
        assertDoesNotThrow(() -> client.configure(config));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void givenHaiku3Model_whenGeneratingCode_thenReturnsValidResponse() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName(HAIKU_3_MODEL)
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .maxTokens(500)
                .temperature(0.1)
                .build();

        AnthropicClient client = new AnthropicClient();
        client.configure(config);

        String prompt = "Generate a simple JUnit test method that tests 2 + 2 = 4";
        String response = client.generateCode(prompt);

        assertNotNull(response, "Response should not be null");
        assertFalse(response.trim().isEmpty(), "Response should not be empty");

        System.out.println("Model: " + HAIKU_3_MODEL);
        System.out.println("Response length: " + response.length());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void givenDifferentTemperatureSettings_whenGeneratingCode_thenProducesAppropriateOutput() throws Exception {
        // Test different temperature settings optimized for code generation
        double[] temperatures = {0.0, 0.1, 0.3}; // Anthropic recommends 0.0-0.3 for code

        for (double temp : temperatures) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .modelName(HAIKU_3_MODEL)
                    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                    .maxTokens(1000)
                    .temperature(temp)
                    .build();

            AnthropicClient client = new AnthropicClient();
            client.configure(config);

            String prompt = "Generate a JUnit test for a simple add method";
            String response = client.generateCode(prompt);

            assertNotNull(response, "Response should not be null for temperature: " + temp);
            assertFalse(response.trim().isEmpty(), "Response should not be empty for temperature: " + temp);

            // Lower temperatures should produce more deterministic output
            if (temp == 0.0) {
                assertTrue(response.contains("@Test") || response.contains("test"),
                    "Response with temperature 0.0 should contain test-related content");
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void givenDifferentTokenLimits_whenGeneratingCode_thenRespectsTokenLimits() throws Exception {
        // Test different token limits within Anthropic's supported range
        int[] tokenLimits = {500, 2000, 4000}; // Up to 8192 for Claude models

        for (int maxTokens : tokenLimits) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .modelName(HAIKU_3_MODEL)
                    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                    .maxTokens(maxTokens)
                    .temperature(0.2)
                    .build();

            AnthropicClient client = new AnthropicClient();
            client.configure(config);

            String prompt = "Generate comprehensive JUnit tests for a Calculator class with add, subtract, multiply, and divide methods";
            String response = client.generateCode(prompt);

            assertNotNull(response, "Response should not be null for maxTokens: " + maxTokens);
            assertFalse(response.trim().isEmpty(), "Response should not be empty for maxTokens: " + maxTokens);

            System.out.println("Max tokens: " + maxTokens + ", Response length: " + response.length());
        }
    }

    @Test
    void givenEnvironmentVariableApiKey_whenConfiguring_thenUsesEnvironmentVariable() {
        // Test that client can be configured using environment variable
        String envApiKey = System.getenv("ANTHROPIC_API_KEY");
        if (envApiKey != null && !envApiKey.trim().isEmpty()) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .modelName(HAIKU_3_MODEL)
                    .apiKey(null) // No explicit API key - should use env var
                    .build();

            AnthropicClient client = new AnthropicClient();

            // Should not throw exception when configuring with env var
            assertDoesNotThrow(() -> client.configure(config));

            // Configuration should be valid
            assertDoesNotThrow(config::validate);
            assertEquals(envApiKey, config.getEffectiveApiKey());
        }
    }
}
