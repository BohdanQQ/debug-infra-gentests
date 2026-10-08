package cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm;

import cz.cuni.mff.d3s.autodebugger.testgenerator.common.AnthropicClient;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.LLMConfiguration;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMConfigurationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick test to verify the Anthropic implementation works correctly.
 */
class QuickAnthropicTest {

    @Test
    void givenMockConfiguration_whenTestingBasicFunctionality_thenWorks() throws Exception {
        // Test configuration
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("mock")
                .maxTokens(1000)
                .build();

        // Test client creation and configuration
        AnthropicClient client = new AnthropicClient();
        assertDoesNotThrow(() -> client.configure(config));

        // Test code generation
        String prompt = "Generate a JUnit test for a simple add method";
        String response = client.generateCode(prompt);

        // Verify response
        assertNotNull(response);
        assertFalse(response.trim().isEmpty());
        assertTrue(response.contains("@Test"));
        assertTrue(response.contains("MockGeneratedTest"));

        System.out.println("✅ Basic Anthropic client functionality works!");
        System.out.println("Generated response length: " + response.length());
    }

    @Test
    void givenSourceCodeAndTrace_whenBuildingPrompt_thenCreatesValidPrompt() {
        // Test prompt builder
        PromptBuilder promptBuilder = new PromptBuilder();
        
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCodeInfo("public class Calculator { public int add(int a, int b) }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .generateEdgeCases(true)
                .generateNegativeTests(false)
                .traceData("add(2, 3) -> 5\nadd(0, 0) -> 0")
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertNotNull(prompt);
        assertFalse(prompt.trim().isEmpty());
        assertTrue(prompt.contains("junit5"));
        assertTrue(prompt.contains("add(int, int)"));
        assertTrue(prompt.contains("Calculator"));
        assertTrue(prompt.contains("edge cases"));

        System.out.println("✅ Prompt builder works correctly!");
        System.out.println("Generated prompt length: " + prompt.length());
    }

    @Test
    void givenDifferentConfigurations_whenValidating_thenValidatesCorrectly() {
        // Test valid configuration
        LLMConfiguration validConfig = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey("test-key")
                .maxTokens(1000)
                .build();

        assertDoesNotThrow(validConfig::validate);

        System.out.println("✅ Configuration validation works correctly!");
    }
}
