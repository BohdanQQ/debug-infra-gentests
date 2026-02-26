package cz.cuni.mff.d3s.autodebugger.testgenerator.java;

import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaMethodIdentifier;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContext;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContextFactory;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationSettings;

/**
 * Java-specific factory for creating TestGenerationContext instances from JavaRunConfiguration.
 * This factory leverages the Java-specific utility methods in JavaMethodIdentifier to provide
 * accurate method signatures, class names, and package names.
 */
public class JavaTestGenerationContextFactory extends TestGenerationContextFactory {

    /**
     * Creates a TestGenerationContext from a JavaRunConfiguration with default settings.
     * Uses Java-specific identifier methods for accurate information extraction.
     * 
     * @param javaConfig The Java run configuration
     * @return A TestGenerationContext with default test generation settings
     */
    public static TestGenerationContext createFromJavaRunConfiguration(JavaRunConfiguration javaConfig) {
        return createFromJavaRunConfiguration(javaConfig, TestGenerationSettings.defaultSettings());
    }

    /**
     * Creates a TestGenerationContext from a JavaRunConfiguration with custom settings.
     * Uses Java-specific identifier methods for accurate information extraction.
     * 
     * @param javaConfig The Java run configuration
     * @param settings Custom test generation settings
     * @return A TestGenerationContext with the specified settings
     */
    public static TestGenerationContext createFromJavaRunConfiguration(
            JavaRunConfiguration javaConfig, 
            TestGenerationSettings settings) {
        
        if (javaConfig == null) {
            throw new IllegalArgumentException("JavaRunConfiguration cannot be null");
        }
        
        if (settings == null) {
            settings = TestGenerationSettings.defaultSettings();
        }

        JavaMethodIdentifier targetMethod = javaConfig.getTargetMethod();
        
        TestGenerationContext.TestGenerationContextBuilder builder = TestGenerationContext.builder()
                .outputDirectory(javaConfig.getOutputDirectory())
                .testFramework(settings.getTestFramework())
                .maxTestCount(settings.getMaxTestCount())
                .generateEdgeCases(settings.isGenerateEdgeCases())
                .generateNegativeTests(settings.isGenerateNegativeTests())
                .namingStrategy(settings.getNamingStrategy())
                .includePerformanceAssertions(settings.isIncludePerformanceAssertions())
                .testTimeoutMs(settings.getTestTimeoutMs())
                .objectCreationStrategy(settings.getObjectCreationStrategy())
                .generateParameterizedTests(settings.isGenerateParameterizedTests());

        // Add classpath entries from the configuration
        if (javaConfig.getClasspathEntries() != null) {
            builder.classpathEntries(javaConfig.getClasspathEntries());
        }

        // Use structured identifiers with Java-specific methods
        if (targetMethod != null) {
            builder.targetMethod(targetMethod);
        } else {
            // No target method available; leave unset (callers should ensure it is provided where required)
        }

        // Apply maxArgumentCombinations if specified in the configuration
        if (javaConfig.getMaxArgumentCombinations() != null) {
            builder.maxArgumentCombinations(javaConfig.getMaxArgumentCombinations());
        }
        // Otherwise, uses the default Integer.MAX_VALUE from TestGenerationContext

        return builder.build();
    }

    /**
     * Creates a TestGenerationContext optimized for trace-based test generation.
     * Uses settings that work well with trace data analysis.
     * 
     * @param javaConfig The Java run configuration
     * @return A TestGenerationContext optimized for trace-based generation
     */
    public static TestGenerationContext createForTraceBasedGeneration(JavaRunConfiguration javaConfig) {
        TestGenerationSettings traceSettings = TestGenerationSettings.builder()
                .testFramework("junit5")
                .maxTestCount(20)
                .generateEdgeCases(true)
                .generateNegativeTests(false) // Trace data typically doesn't include error cases
                .namingStrategy(cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestNamingStrategy.DESCRIPTIVE)
                .generateParameterizedTests(false) // Trace-based tests are usually scenario-specific
                .build();
        
        return createFromJavaRunConfiguration(javaConfig, traceSettings);
    }

    /**
     * Creates a TestGenerationContext optimized for LLM-based test generation.
     * Uses settings that work well with AI-generated tests.
     * 
     * @param javaConfig The Java run configuration
     * @return A TestGenerationContext optimized for LLM-based generation
     */
    public static TestGenerationContext createForLLMBasedGeneration(JavaRunConfiguration javaConfig) {
        TestGenerationSettings llmSettings = TestGenerationSettings.builder()
                .testFramework("junit5")
                .maxTestCount(10)
                .generateEdgeCases(true)
                .generateNegativeTests(true)
                .namingStrategy(cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestNamingStrategy.DESCRIPTIVE)
                .generateParameterizedTests(true)
                .includePerformanceAssertions(false)
                .build();
        
        return createFromJavaRunConfiguration(javaConfig, llmSettings);
    }
}
