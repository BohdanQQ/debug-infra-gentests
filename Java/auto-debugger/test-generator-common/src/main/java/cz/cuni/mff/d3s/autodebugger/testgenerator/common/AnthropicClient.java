package cz.cuni.mff.d3s.autodebugger.testgenerator.common;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.AnthropicApiException;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMClientNotConfiguredException;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMConfigurationException;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMResponseException;
import lombok.extern.slf4j.Slf4j;

/**
 * Language-agnostic Anthropic Claude client for test generation.
 * This client can generate test code for any programming language by using appropriate prompts.
 * 
 * Features:
 * - Direct Anthropic API integration
 * - Built-in caching and connection pooling
 * - Environment variable API key support
 * - Mock mode for testing
 * - Simple configuration
 */
@Slf4j
public class AnthropicClient {

    private LLMConfiguration config;
    private com.anthropic.client.AnthropicClient client;

    /**
     * Configures the client with the specified LLM configuration.
     * Follows Anthropic SDK best practices for API key handling.
     *
     * @param config LLM configuration with model and API key settings
     * @throws LLMConfigurationException if configuration is invalid
     */
    public void configure(LLMConfiguration config) throws LLMConfigurationException {
        if (config == null) {
            throw new LLMConfigurationException("LLM configuration cannot be null");
        }

        config.validate();
        this.config = config;

        if (!config.isMockModel()) {
            // Follow Anthropic SDK best practices for API key handling
            if (config.getApiKey() != null && !config.getApiKey().trim().isEmpty()) {
                // Use explicit API key
                this.client = AnthropicOkHttpClient.builder()
                        .apiKey(config.getApiKey())
                        .build();
            } else {
                // Use environment variable (ANTHROPIC_API_KEY)
                this.client = AnthropicOkHttpClient.fromEnv();
            }
        }

        log.info("Configured Anthropic client for model: {}", config.getModelName());
    }

    /**
     * Generates code using Claude models.
     * This method provides a simple interface for any programming language.
     *
     * @param prompt The prompt to send to Claude
     * @return Generated code response
     * @throws AnthropicApiException if the API request fails
     * @throws LLMClientNotConfiguredException if client is not configured
     * @throws LLMResponseException if the response is invalid
     */
    public String generateCode(String prompt) throws AnthropicApiException, LLMResponseException {
        if (config == null) {
            throw new LLMClientNotConfiguredException("Client must be configured before generating code", "AnthropicClient");
        }

        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("Prompt cannot be null or empty");
        }

        log.debug("Generating code with Claude (prompt length: {} chars)", prompt.length());

        try {
            if (config.isMockModel()) {
                return generateMockResponse(prompt);
            }

            String response = callClaude(prompt);
            log.debug("Received response from Claude (length: {} chars)", response.length());
            return response;

        } catch (LLMResponseException | AnthropicApiException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate code using Claude", e);
            throw new AnthropicApiException(
                "Claude code generation failed: " + e.getMessage(),
                config.getModelName(),
                null,
                null,
                null,
                e
            );
        }
    }

    /**
     * Calls Claude with the given prompt using the Anthropic Java SDK.
     * Uses system parameter for role instruction and user message for the actual prompt.
     */
    private String callClaude(String promptText) throws AnthropicApiException, LLMResponseException {
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(getClaudeModel())
                    .maxTokens(config.getMaxTokens())
                    .temperature(config.getTemperature())
                    .system("You are an expert software developer who specializes in writing robust, well-structured test code. " +
                            "Generate clean, comprehensive test code without explanations or markdown formatting.")
                    .addUserMessage(promptText)
                    .build();

            Message response = client.messages().create(params);

            // Extract text content from the response
            if (response.content() != null && !response.content().isEmpty()) {
                var contentBlock = response.content().getFirst();
                if (contentBlock.text().isPresent()) {
                    String responseText = contentBlock.text().get().text();
                    if (responseText != null && !responseText.trim().isEmpty()) {
                        return responseText;
                    }
                }
            }

            // Handle empty response
            String promptSummary = LLMResponseException.createTextSummary(promptText, 100);
            throw new LLMResponseException(
                "Empty response from Claude",
                config.getModelName(),
                promptSummary,
                "empty",
                LLMResponseException.ResponseIssueType.EMPTY_RESPONSE
            );

        } catch (LLMResponseException e) {
            // Re-throw our custom exception
            throw e;
        } catch (Exception e) {
            log.error("Anthropic API call failed", e);

            // Try to extract HTTP status code and error details from the exception
            Integer httpStatus = extractHttpStatusFromException(e);
            String apiErrorCode = extractApiErrorCodeFromException(e);
            String requestId = extractRequestIdFromException(e);

            throw new AnthropicApiException(
                "Anthropic API call failed: " + e.getMessage(),
                config.getModelName(),
                httpStatus,
                apiErrorCode,
                requestId,
                e
            );
        }
    }

    /**
     * Maps the configured model name to the appropriate Claude model.
     * Defaults to Claude Sonnet 4.5 if not specified.
     */
    private Model getClaudeModel() {
        if (config.getModelName() == null) {
            return Model.CLAUDE_SONNET_4_5_20250929;
        }

        return switch (config.getModelName().toLowerCase()) {
            case "claude-sonnet-4-5-20250929", "claude-sonnet-4-5", "claude-sonnet-4.5", "sonnet-4.5" -> Model.CLAUDE_SONNET_4_5_20250929;
            case "claude-sonnet-4-20250514", "claude-sonnet-4", "sonnet-4" -> Model.CLAUDE_SONNET_4_20250514;
            case "claude-3-7-sonnet-20250219", "claude-3-7-sonnet", "sonnet-3.7" -> Model.CLAUDE_3_7_SONNET_20250219;
            case "claude-3-5-haiku-20241022", "claude-3-5-haiku", "haiku-3.5" -> Model.CLAUDE_3_5_HAIKU_20241022;
            case "claude-3-haiku-20240307", "claude-3-haiku", "haiku-3" -> Model.CLAUDE_3_HAIKU_20240307;
            case "mock" -> Model.CLAUDE_SONNET_4_5_20250929; // Doesn't matter for mock
            default -> {
                log.warn("Unknown model '{}', defaulting to Claude Sonnet 4.5", config.getModelName());
                yield Model.CLAUDE_SONNET_4_5_20250929;
            }
        };
    }

    /**
     * Generates a mock response for testing purposes.
     */
    private String generateMockResponse(String prompt) {
        log.debug("Generating mock response for prompt: {}", prompt.substring(0, Math.min(50, prompt.length())));

        return """
            package com.example.generated;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class MockGeneratedTest {
                
                @Test
                void testMockMethod() {
                    // Mock test generated by Anthropic client
                    // This would be replaced with actual Claude-generated content
                    assertTrue(true, "Mock assertion");
                }
                
                @Test
                void testAnotherMockMethod() {
                    // Additional mock test method
                    assertNotNull("test", "Mock string should not be null");
                }
            }
            """;
    }

    /**
     * Attempts to extract HTTP status code from an exception.
     * This is a best-effort attempt to get status information from Anthropic SDK exceptions.
     */
    private Integer extractHttpStatusFromException(Exception e) {
        // Try to extract HTTP status from exception message or type
        String message = e.getMessage();
        if (message != null) {
            if (message.contains("401") || message.toLowerCase().contains("unauthorized")) {
                return 401;
            }
            if (message.contains("402") || message.toLowerCase().contains("payment")) {
                return 402;
            }
            if (message.contains("429") || message.toLowerCase().contains("rate limit")) {
                return 429;
            }
            if (message.contains("500") || message.toLowerCase().contains("internal server")) {
                return 500;
            }
        }
        return null;
    }

    /**
     * Attempts to extract API error code from an exception.
     */
    private String extractApiErrorCodeFromException(Exception e) {
        // This would need to be implemented based on the specific structure
        // of Anthropic SDK exceptions when they become available
        return null;
    }

    /**
     * Attempts to extract request ID from an exception.
     */
    private String extractRequestIdFromException(Exception e) {
        // This would need to be implemented based on the specific structure
        // of Anthropic SDK exceptions when they become available
        return null;
    }
}
