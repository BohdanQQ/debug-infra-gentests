package cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm;

import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestNamingStrategy;
import lombok.Builder;
import lombok.Getter;

/**
 * Context information for building LLM prompts for test generation.
 */
@Builder
@Getter
public class LLMPromptContext {
    
    /**
     * Relevant information about source code of the class containing the target method.
     */
    private final String sourceCodeInfo;
    
    /**
     * Target method signature for which tests are being generated.
     */
    private final String targetMethodSignature;
    
    /**
     * Fully qualified class name containing the target method.
     */
    private final String targetClassName;
    
    /**
     * Package name of the target class.
     */
    private final String packageName;
    
    /**
     * Test framework to use (e.g., "junit5", "junit4", "testng").
     */
    private final String testFramework;
    
    /**
     * Formatted trace data from runtime execution.
     */
    private final String traceData;
    
    /**
     * Maximum number of tests to generate.
     */
    private final int maxTestCount;
    
    /**
     * Whether to generate edge case tests.
     */
    private final boolean generateEdgeCases;
    
    /**
     * Whether to generate negative test cases.
     */
    private final boolean generateNegativeTests;
    
    /**
     * Test naming strategy to use.
     */
    private final TestNamingStrategy namingStrategy;
    
    /**
     * Additional context or instructions for the LLM.
     */
    private final String additionalInstructions;
    
    /**
     * Whether to include performance-related tests.
     */
    @Builder.Default
    private final boolean includePerformanceTests = false;
    
    /**
     * Whether to generate parameterized tests.
     */
    @Builder.Default
    private final boolean generateParameterizedTests = true;
    
    /**
     * Whether to include setup and teardown methods.
     */
    @Builder.Default
    private final boolean includeSetupTeardown = true;
}
