package cz.cuni.mff.d3s.autodebugger.testgenerator.common;

import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMConfigurationException;
import lombok.Builder;
import lombok.Getter;

/**
 * Configuration for Anthropic Claude-based test generation.
 * Simplified to focus only on Claude models and essential parameters.
 *
 * API Key Handling:
 * - If apiKey is provided, it will be used directly
 * - If apiKey is null, the client will use the ANTHROPIC_API_KEY environment variable
 * - For testing, use "mock" as the model name to enable mock responses
 */
@Builder
@Getter
public class LLMConfiguration {

    /**
     * Claude model name to use.
     * Supported models:
     * - "claude-sonnet-4-5-20250929" (default, most capable - Sonnet 4.5)
     * - "claude-sonnet-4-20250514" (Sonnet 4)
     * - "claude-3-7-sonnet-20250219" (Sonnet 3.7)
     * - "claude-3-5-haiku-20241022" (fast, cost-effective)
     * - "claude-3-haiku-20240307" (cheapest, basic tasks)
     * - "mock" (for testing, returns predefined responses)
     */
    @Builder.Default
    private final String modelName = "claude-sonnet-4-5-20250929";

    /**
     * API key for Anthropic Claude API.
     * If null, the client will use the ANTHROPIC_API_KEY environment variable.
     * Not required for mock model.
     */
    private final String apiKey;

    /**
     * Maximum number of tokens in the response.
     * Claude models support up to 8192 output tokens.
     */
    @Builder.Default
    private final int maxTokens = 4000;

    /**
     * Temperature for response generation (0.0 to 1.0).
     * Lower values make output more deterministic.
     * 0.0-0.3 recommended for code generation.
     */
    @Builder.Default
    private final double temperature = 0.3;

    /**
     * Whether to enable iterative refinement of generated tests.
     * When enabled, the generator will attempt to fix compilation errors automatically.
     */
    @Builder.Default
    private final boolean enableIterativeRefinement = true;

    /**
     * Maximum number of refinement iterations.
     */
    @Builder.Default
    private final int maxRefinementIterations = 3;

    /**
     * Whether to validate generated code before returning.
     */
    @Builder.Default
    private final boolean validateGeneratedCode = true;

    /**
     * Determines if this configuration is using the mock model for testing.
     *
     * @return true if using mock model
     */
    public boolean isMockModel() {
        return "mock".equalsIgnoreCase(modelName);
    }

    /**
     * Determines if this configuration requires an API key.
     * Mock model doesn't need an API key.
     *
     * @return true if API key is required
     */
    public boolean requiresApiKey() {
        return !isMockModel();
    }

    /**
     * Validates the configuration for Claude models.
     *
     * @throws LLMConfigurationException if configuration is invalid
     */
    public void validate() throws LLMConfigurationException {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new LLMConfigurationException("Model name cannot be null or empty", "modelName", modelName);
        }

        if (requiresApiKey() && (apiKey == null || apiKey.trim().isEmpty()) && System.getenv("ANTHROPIC_API_KEY") == null) {
            throw new LLMConfigurationException(
                "API key must be provided either directly or via ANTHROPIC_API_KEY environment variable",
                "apiKey",
                apiKey
            );
        }

        if (maxTokens <= 0) {
            throw new LLMConfigurationException("Max tokens must be positive", "maxTokens", maxTokens);
        }

        if (temperature < 0.0 || temperature > 1.0) {
            throw new LLMConfigurationException("Temperature must be between 0.0 and 1.0", "temperature", temperature);
        }
    }

    /**
     * Gets the effective API key, checking environment variable if not explicitly set.
     *
     * @return the API key to use, or null if using mock model
     */
    public String getEffectiveApiKey() {
        if (isMockModel()) {
            return null;
        }
        return apiKey != null ? apiKey : System.getenv("ANTHROPIC_API_KEY");
    }
}
