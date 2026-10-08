package cz.cuni.mff.d3s.autodebugger.testgenerator.common;

import lombok.Builder;
import lombok.Getter;

/**
 * Configuration settings for test generation that can be customized
 * independently of the RunConfiguration. This allows for flexible
 * test generation behavior while leveraging structured identifiers.
 */
@Builder
@Getter
public class TestGenerationSettings {
    
    /**
     * Test framework to use (e.g., "junit5", "junit4", "testng").
     */
    @Builder.Default
    private final String testFramework = "junit5";
    
    /**
     * Maximum number of tests to generate.
     */
    @Builder.Default
    private final int maxTestCount = Integer.MAX_VALUE;
    
    /**
     * Whether to generate edge case tests.
     */
    @Builder.Default
    private final boolean generateEdgeCases = true;
    
    /**
     * Whether to generate negative test cases (testing error conditions).
     */
    @Builder.Default
    private final boolean generateNegativeTests = false;
    
    /**
     * Test naming strategy to use.
     */
    @Builder.Default
    private final TestNamingStrategy namingStrategy = TestNamingStrategy.DESCRIPTIVE;
    
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
    
    /**
     * Creates default test generation settings.
     * 
     * @return Default settings for test generation
     */
    public static TestGenerationSettings defaultSettings() {
        return TestGenerationSettings.builder().build();
    }
    
    /**
     * Creates settings optimized for fast test generation with minimal features.
     * 
     * @return Settings for fast, minimal test generation
     */
    public static TestGenerationSettings fastSettings() {
        return TestGenerationSettings.builder()
                .maxTestCount(10)
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .generateParameterizedTests(false)
                .includePerformanceAssertions(false)
                .namingStrategy(TestNamingStrategy.SIMPLE)
                .build();
    }
    
    /**
     * Creates settings optimized for comprehensive test generation.
     * 
     * @return Settings for comprehensive test generation
     */
    public static TestGenerationSettings comprehensiveSettings() {
        return TestGenerationSettings.builder()
                .maxTestCount(100)
                .generateEdgeCases(true)
                .generateNegativeTests(true)
                .generateParameterizedTests(true)
                .includePerformanceAssertions(true)
                .namingStrategy(TestNamingStrategy.DESCRIPTIVE)
                .build();
    }
}
