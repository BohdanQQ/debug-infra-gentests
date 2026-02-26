package cz.cuni.mff.d3s.autodebugger.instrumentor.java.e2e.llm;

import cz.cuni.mff.d3s.autodebugger.instrumentor.java.e2e.DiSLEndToEndTestBase;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.runner.factories.TestGeneratorFactory;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContext;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContextFactory;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for LLM-based end-to-end tests.
 * Extends DiSLEndToEndTestBase to inherit DiSL setup and provides helper methods
 * for LLM test generation verification.
 *
 * These tests use the "mock" model to avoid consuming API credits and ensure
 * deterministic test behavior.
 */
public abstract class LLMEndToEndTestBase extends DiSLEndToEndTestBase {

    protected static final String MOCK_MODEL = "mock";
    protected static final String AI_ASSISTED_STRATEGY = "ai-assisted";

    /**
     * Creates a test generator configured with the mock model.
     *
     * @param runConfiguration The run configuration for the test
     * @param identifierMappingPath Path to the identifier mapping file from instrumentation
     * @return A configured TestGenerator using the mock LLM model
     */
    protected TestGenerator createMockLLMTestGenerator(
            JavaRunConfiguration runConfiguration,
            Path identifierMappingPath) {
        return TestGeneratorFactory.createTestGenerator(
                runConfiguration,
                AI_ASSISTED_STRATEGY,
                null,  // apiKey - not needed for mock
                identifierMappingPath,
                MOCK_MODEL);
    }

    /**
     * Generates tests using the LLM generator with explicit source file path.
     * Note: LLMBasedTestGenerator requires a file path, not directory, so we resolve
     * the source file from the class name provided in the run configuration.
     *
     * @param generator The configured test generator
     * @param trace The trace collected from analysis
     * @param sourceFilePath Path to the actual source file (not directory)
     * @param runConfiguration The run configuration (used for context creation)
     * @return List of paths to generated test files
     */
    protected List<Path> generateLLMTests(
            TestGenerator generator,
            Trace trace,
            Path sourceFilePath,
            JavaRunConfiguration runConfiguration) {
        // Create context from the run configuration
        TestGenerationContext context = TestGenerationContextFactory.createFromRunConfiguration(runConfiguration);
        // Use the 3-argument version that takes a file path
        return generator.generateTests(trace, sourceFilePath, context);
    }

    /**
     * Verifies that a generated test file exists and contains expected content.
     *
     * @param testFilePath Path to the generated test file
     */
    protected void assertGeneratedTestIsValid(Path testFilePath) {
        assertTrue(Files.exists(testFilePath), 
                "Generated test file should exist: " + testFilePath);
        
        try {
            String content = Files.readString(testFilePath);
            assertFalse(content.trim().isEmpty(), 
                    "Generated test file should not be empty");
            assertTrue(content.contains("@Test"), 
                    "Generated test should contain @Test annotation");
            assertTrue(content.contains("class"), 
                    "Generated test should contain class definition");
        } catch (Exception e) {
            fail("Failed to read generated test file: " + e.getMessage());
        }
    }

    /**
     * Verifies that the generated test files list is not empty and all files exist.
     *
     * @param generatedTests List of paths to generated test files
     */
    protected void assertTestsGenerated(List<Path> generatedTests) {
        assertNotNull(generatedTests, "Generated tests list should not be null");
        assertFalse(generatedTests.isEmpty(), "Should generate at least one test file");
        
        for (Path testPath : generatedTests) {
            assertTrue(Files.exists(testPath), 
                    "Generated test file should exist: " + testPath);
        }
    }

    /**
     * Verifies that mock model output contains expected mock content.
     *
     * @param testFilePath Path to the generated test file
     */
    protected void assertMockGeneratedContent(Path testFilePath) {
        try {
            String content = Files.readString(testFilePath);
            // Mock model returns a specific mock response - verify it's present
            assertTrue(content.contains("MockGeneratedTest") || content.contains("@Test"),
                    "Mock output should contain expected test structure");
        } catch (Exception e) {
            fail("Failed to read generated test file: " + e.getMessage());
        }
    }
}

