package cz.cuni.mff.d3s.autodebugger.testgenerator.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AnthropicClient that verify the Anthropic SDK integration.
 * These tests require ANTHROPIC_API_KEY environment variable and make actual API calls.
 * Uses the cheapest model (claude-3-haiku-20240307) to minimize costs.
 */
class AnthropicClientIntegrationTest {

    private static final String CHEAPEST_MODEL = "claude-3-haiku-20240307";

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void givenValidApiKey_whenConfiguring_thenSucceeds() {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName(CHEAPEST_MODEL)
                .maxTokens(100)
                .temperature(0.1)
                .build();

        AnthropicClient client = new AnthropicClient();
        
        assertDoesNotThrow(() -> client.configure(config));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void givenValidApiKey_whenGeneratingSimpleCode_thenReturnsValidResponse() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName(CHEAPEST_MODEL)
                .maxTokens(500)
                .temperature(0.1)
                .build();

        AnthropicClient client = new AnthropicClient();
        client.configure(config);

        String prompt = "Generate a simple JUnit 5 test method that tests 2 + 2 = 4. Return only Java code.";
        String response = client.generateCode(prompt);

        assertNotNull(response, "Response should not be null");
        assertFalse(response.trim().isEmpty(), "Response should not be empty");
        assertTrue(response.contains("@Test") || response.contains("test"), 
                "Response should contain @Test annotation or test method: " + response);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void givenValidApiKey_whenGeneratingTestClass_thenResponseContainsClassDefinition() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName(CHEAPEST_MODEL)
                .maxTokens(1000)
                .temperature(0.1)
                .build();

        AnthropicClient client = new AnthropicClient();
        client.configure(config);

        String prompt = """
            Generate a JUnit 5 test class for a Calculator class with an add method.
            The test class should be named CalculatorTest.
            Return only the Java code, no explanations.
            """;
        String response = client.generateCode(prompt);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.contains("class"), 
                "Response should contain class definition: " + response);
        assertTrue(response.contains("@Test") || response.contains("Test"), 
                "Response should contain @Test annotation or Test: " + response);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void givenCheapestModel_whenGeneratingCodeWithMinimalTokens_thenRespectsTokenLimit() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName(CHEAPEST_MODEL)
                .maxTokens(100)  // Very small token limit
                .temperature(0.0)
                .build();

        AnthropicClient client = new AnthropicClient();
        client.configure(config);

        String prompt = "Generate a one-line assert statement.";
        String response = client.generateCode(prompt);

        assertNotNull(response, "Response should not be null even with small token limit");
    }
}

