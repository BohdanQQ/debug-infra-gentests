package cz.cuni.mff.d3s.autodebugger.testgenerator.common;

import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.MethodIdentifier;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Context information for test generation, providing additional metadata
 * and configuration options for different test generation strategies.
 */
@Builder
@Getter
public class TestGenerationContext {
    
    /**
     * Structured identifier for the target method.
     * This provides type-safe access to method information without string parsing.
     * Preferred over the deprecated string-based fields.
     */
    private final MethodIdentifier targetMethod;
    
    /**
     * Output directory where generated tests should be placed.
     */
    private final Path outputDirectory;
    
    /**
     * Test framework to use (e.g., "junit5", "junit4", "testng").
     */
    @Builder.Default
    private final String testFramework = "junit5";
    
    /**
     * Additional classpath entries needed for test compilation.
     */
    @Singular
    private final List<Path> classpathEntries;
    
    /**
     * Environment variables that may affect test generation.
     */
    @Singular
    private final Map<String, String> environmentVariables;
    
    /**
     * Maximum number of tests to generate.
     */
    @Builder.Default
    private final int maxTestCount = 50;
    
    /**
     * Whether to generate edge case tests.
     */
    @Builder.Default
    private final boolean generateEdgeCases = true;
    
    /**
     * Whether to generate negative test cases (testing error conditions).
     */
    @Builder.Default
    private final boolean generateNegativeTests = true;
    
    /**
     * Test naming strategy to use.
     */
    @Builder.Default
    private final TestNamingStrategy namingStrategy = TestNamingStrategy.DESCRIPTIVE;
    
    /**
     * Additional metadata for test generation.
     */
    @Singular("metadataEntry")
    private final Map<String, Object> metadata;
    
    /**
     * Whether to include performance assertions in generated tests.
     */
    @Builder.Default
    private final boolean includePerformanceAssertions = false;
    
    /**
     * Timeout for individual test execution (in milliseconds).
     */
    @Builder.Default
    private final long testTimeoutMs = 5000;
    
    /**
     * Strategy for handling complex object creation in tests.
     */
    @Builder.Default
    private final ObjectCreationStrategy objectCreationStrategy = ObjectCreationStrategy.SIMPLE;
    
    /**
     * Whether to generate parameterized tests when applicable.
     */
    @Builder.Default
    private final boolean generateParameterizedTests = true;

    // === Configurable limits for test generation strategies ===
    // By default, all limits are set to Integer.MAX_VALUE (no limits) to ensure
    // no test inputs are filtered out. Users can set lower values via CLI if needed.

    /**
     * Maximum number of argument value combinations to generate.
     * Used by NaiveTraceBasedGenerator when creating test scenarios from argument values.
     * Default: Integer.MAX_VALUE (no limit - include all captured combinations).
     */
    @Builder.Default
    private final int maxArgumentCombinations = Integer.MAX_VALUE;

    /**
     * Maximum number of field value combinations to generate.
     * Used by NaiveTraceBasedGenerator when creating test scenarios from field values.
     * Default: Integer.MAX_VALUE (no limit - include all captured combinations).
     */
    @Builder.Default
    private final int maxFieldCombinations = Integer.MAX_VALUE;

    /**
     * Maximum number of state change samples to capture.
     * Used by TemporalTraceBasedGenerator when creating scenarios from state changes.
     * Default: Integer.MAX_VALUE (no limit - include all state changes).
     */
    @Builder.Default
    private final int maxStateChangeSamples = Integer.MAX_VALUE;

    /**
     * Maximum number of values per variable to include in LLM prompts.
     * Used by LLMBasedTestGenerator when formatting trace data for the LLM.
     * Default: Integer.MAX_VALUE (no limit - include all values).
     */
    @Builder.Default
    private final int maxValuesPerVariable = Integer.MAX_VALUE;

    /**
     * Maximum number of execution scenarios to include in LLM prompts.
     * Used by LLMBasedTestGenerator when formatting temporal trace data.
     * Default: Integer.MAX_VALUE (no limit - include all scenarios).
     */
    @Builder.Default
    private final int maxExecutionScenarios = Integer.MAX_VALUE;

    // Convenience computed getters

    /**
     * Returns the fully-qualified method signature from the structured identifier.
     * @throws IllegalStateException if targetMethod is null
     */
    public String getTargetMethodSignature() {
        if (targetMethod == null) {
            throw new IllegalStateException("targetMethod is not set in TestGenerationContext");
        }
        return targetMethod.getFullyQualifiedSignature();
    }

    /**
     * Returns the fully qualified class name containing the target method.
     * @throws IllegalStateException if targetMethod is null
     */
    public String getTargetClassName() {
        if (targetMethod == null) {
            throw new IllegalStateException("targetMethod is not set in TestGenerationContext");
        }
        return targetMethod.getFullyQualifiedClassName();
    }

    /**
     * Returns the package name of the target method's class.
     * @throws IllegalStateException if targetMethod is null
     */
    public String getPackageName() {
        if (targetMethod == null) {
            throw new IllegalStateException("targetMethod is not set in TestGenerationContext");
        }
        return targetMethod.getPackageName();
    }
}
