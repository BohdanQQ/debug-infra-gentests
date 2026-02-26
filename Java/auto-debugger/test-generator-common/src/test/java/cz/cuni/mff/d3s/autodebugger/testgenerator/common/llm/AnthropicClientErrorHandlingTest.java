package cz.cuni.mff.d3s.autodebugger.testgenerator.common.llm;

import cz.cuni.mff.d3s.autodebugger.testgenerator.common.AnthropicClient;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.LLMConfiguration;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMClientNotConfiguredException;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for error handling scenarios in AnthropicClient.
 */
class AnthropicClientErrorHandlingTest {

    private AnthropicClient client;

    @BeforeEach
    void setUp() {
        client = new AnthropicClient();
    }

    @Test
    void givenNullConfiguration_whenConfiguring_thenThrowsLLMConfigurationException() {
        // when/then
        assertThrows(LLMConfigurationException.class, () -> {
            client.configure(null);
        });
    }
    
    @Test
    void givenConfigurationWithNullModelName_whenConfiguring_thenThrowsLLMConfigurationException() {
        // given
        var config = LLMConfiguration.builder()
            .modelName(null)
            .apiKey("test-api-key")
            .build();
        
        // when/then
        assertThrows(LLMConfigurationException.class, () -> {
            client.configure(config);
        });
    }
    
    @Test
    void givenConfigurationWithEmptyModelName_whenConfiguring_thenThrowsLLMConfigurationException() {
        // given
        var config = LLMConfiguration.builder()
            .modelName("")
            .apiKey("test-api-key")
            .build();
        
        // when/then
        assertThrows(LLMConfigurationException.class, () -> {
            client.configure(config);
        });
    }
    
    @Test
    void givenConfigurationWithBlankModelName_whenConfiguring_thenThrowsLLMConfigurationException() {
        // given
        var config = LLMConfiguration.builder()
            .modelName("   ")
            .apiKey("test-api-key")
            .build();
        
        // when/then
        assertThrows(LLMConfigurationException.class, () -> {
            client.configure(config);
        });
    }
    
    @Test
    void givenNullPrompt_whenGenerateCode_thenThrowsIllegalArgumentException() {
        // given - configure with mock model to avoid API key requirement
        var config = LLMConfiguration.builder()
            .modelName("mock")
            .build();
        
        assertDoesNotThrow(() -> client.configure(config));
        
        // when/then
        assertThrows(IllegalArgumentException.class, () -> {
            client.generateCode(null);
        });
    }
    
    @Test
    void givenEmptyPrompt_whenGenerateCode_thenThrowsIllegalArgumentException() {
        // given - configure with mock model
        var config = LLMConfiguration.builder()
            .modelName("mock")
            .build();
        
        assertDoesNotThrow(() -> client.configure(config));
        
        // when/then
        assertThrows(IllegalArgumentException.class, () -> {
            client.generateCode("");
        });
    }
    
    @Test
    void givenBlankPrompt_whenGenerateCode_thenThrowsIllegalArgumentException() {
        // given - configure with mock model
        var config = LLMConfiguration.builder()
            .modelName("mock")
            .build();
        
        assertDoesNotThrow(() -> client.configure(config));
        
        // when/then
        assertThrows(IllegalArgumentException.class, () -> {
            client.generateCode("   ");
        });
    }
    
    @Test
    void givenUnconfiguredClient_whenGenerateCode_thenThrowsLLMClientNotConfiguredException() {
        // when/then
        assertThrows(LLMClientNotConfiguredException.class, () -> {
            client.generateCode("test prompt");
        });
    }
    
    @Test
    void givenValidConfiguration_whenConfiguring_thenSucceeds() {
        // given
        var config = LLMConfiguration.builder()
            .modelName("mock")
            .build();
        
        // when/then
        assertDoesNotThrow(() -> client.configure(config));
    }
    
    @Test
    void givenValidPromptAfterConfiguration_whenGenerateCode_thenSucceeds() throws Exception {
        // given
        var config = LLMConfiguration.builder()
            .modelName("mock")
            .build();
        
        client.configure(config);
        
        // when
        String result = client.generateCode("Generate a test");
        
        // then
        assertNotNull(result);
        assertFalse(result.trim().isEmpty());
    }
}

