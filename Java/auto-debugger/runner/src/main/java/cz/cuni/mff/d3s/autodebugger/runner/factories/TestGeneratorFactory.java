package cz.cuni.mff.d3s.autodebugger.runner.factories;

import cz.cuni.mff.d3s.autodebugger.model.common.RunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.common.TargetLanguage;
import cz.cuni.mff.d3s.autodebugger.model.common.TraceMode;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.runner.strategies.TestGenerationStrategyProvider;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.LLMConfiguration;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerator;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm.LLMBasedTestGenerator;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.AnthropicClient;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm.PromptBuilder;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm.CodeValidator;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace.NaiveTraceBasedGenerator;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace.TemporalTraceBasedGenerator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Slf4j
public class TestGeneratorFactory {

    // Default model for production use - Claude Sonnet 4.5
    private static final String DEFAULT_LLM_MODEL = "claude-sonnet-4-5-20250929";

    public static TestGenerator createTestGenerator(RunConfiguration runConfiguration, String strategyId) {
        return createTestGenerator(runConfiguration, strategyId, null);
    }

    public static TestGenerator createTestGenerator(RunConfiguration runConfiguration, String strategyId, String apiKey) {
        return createTestGenerator(runConfiguration, strategyId, apiKey, null);
    }

    public static TestGenerator createTestGenerator(RunConfiguration runConfiguration, String strategyId, String apiKey, Path identifierMappingPath) {
        return createTestGenerator(runConfiguration, strategyId, apiKey, identifierMappingPath, DEFAULT_LLM_MODEL);
    }

    /**
     * Creates a test generator with a custom LLM model name.
     * This overload is primarily for testing purposes to allow using the "mock" model.
     *
     * @param runConfiguration The run configuration
     * @param strategyId The test generation strategy ID
     * @param apiKey The API key (can be null for mock model)
     * @param identifierMappingPath Path to identifier mapping (for trace-based strategies)
     * @param llmModelName The LLM model name (use "mock" for testing without API calls)
     * @return The configured test generator
     */
    public static TestGenerator createTestGenerator(RunConfiguration runConfiguration, String strategyId,
            String apiKey, Path identifierMappingPath, String llmModelName) {
        TargetLanguage language = runConfiguration.getLanguage();
        if (language == TargetLanguage.JAVA) {
            return createJavaTestGenerator(runConfiguration, strategyId, apiKey, identifierMappingPath, llmModelName);
        }

        throw new IllegalArgumentException("Unsupported language: " + language);
    }

    private static TestGenerator createJavaTestGenerator(RunConfiguration runConfiguration, String strategyId,
            String apiKey, Path identifierMappingPath, String llmModelName) {
        log.info("Creating Java test generator with strategy: {}, model: {}", strategyId, llmModelName);

        // Validate that the strategy exists
        if (!TestGenerationStrategyProvider.hasStrategy(strategyId)) {
            throw new IllegalArgumentException("Unknown test generation strategy: " + strategyId);
        }

        if (runConfiguration instanceof JavaRunConfiguration javaRunConfiguration) {
            try {
                if ("ai-assisted".equals(strategyId)) {
                    // Create dependencies for LLM-based test generator
                    AnthropicClient anthropicClient = new AnthropicClient();
                    PromptBuilder promptBuilder = new PromptBuilder();
                    CodeValidator codeValidator = new CodeValidator();

                    // Create LLM-based test generator with dependencies
                    LLMBasedTestGenerator llmGenerator = new LLMBasedTestGenerator(
                            anthropicClient, promptBuilder, codeValidator);

                    // Configure with Anthropic Claude settings
                    // For mock model, API key is not required
                    String resolvedApiKey = "mock".equals(llmModelName) ? "mock-key" : getApiKeyFromEnvironmentOrConfig(apiKey);

                    LLMConfiguration llmConfig = LLMConfiguration.builder()
                            .modelName(llmModelName)
                            .apiKey(resolvedApiKey)
                            .maxTokens(4000)
                            .temperature(0.3)
                            .build();

                    llmGenerator.configure(llmConfig);
                    // Set technique label for UI/tests compatibility
                    llmGenerator.setGenerationTechnique("ai-assisted");

                    log.info("Successfully created LLM-based Java test generator with Claude");
                    return llmGenerator;

                } else if (strategyId.startsWith("trace-based")) {
                    // Route based on TraceMode: TEMPORAL mode uses TemporalTraceBasedGenerator
                    if (runConfiguration.getTraceMode() == TraceMode.TEMPORAL) {
                        log.info("Temporal trace mode detected, using TemporalTraceBasedGenerator");
                        TemporalTraceBasedGenerator generator = new TemporalTraceBasedGenerator();
                        log.info("Successfully created TemporalTraceBasedGenerator for strategy: {}", strategyId);
                        return generator;
                    }

                    // NAIVE mode (default) uses NaiveTraceBasedGenerator
                    // Use provided identifier mapping path if available, otherwise fall back to default location
                    Path identifiersPath = identifierMappingPath != null
                            ? identifierMappingPath
                            : javaRunConfiguration.getSourceCodePath().resolve("identifiers");
                    NaiveTraceBasedGenerator generator = new NaiveTraceBasedGenerator(identifiersPath);

                    log.info("Successfully created NaiveTraceBasedGenerator for strategy: {}", strategyId);
                    return generator;
                } else {
                    throw new UnsupportedOperationException("Test generation technique not yet supported: " + strategyId);
                }
            } catch (Exception e) {
                log.error("Failed to create Java test generator", e);
                throw new RuntimeException("Failed to create test generator", e);
            }
        }

        throw new IllegalArgumentException("Expected JavaRunConfiguration, got: " + runConfiguration.getClass().getSimpleName());
    }

    private static String getApiKeyFromEnvironmentOrConfig(String providedApiKey) {
        // First try provided API key from command line
        if (providedApiKey != null && !providedApiKey.trim().isEmpty()) {
            return providedApiKey;
        }

        // Try Anthropic environment variable
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.trim().isEmpty()) {
            return anthropicKey;
        }

        // Return null to let the LLMConfiguration handle the missing API key
        // The configuration will throw an appropriate exception during validation
        return null;
    }
}
