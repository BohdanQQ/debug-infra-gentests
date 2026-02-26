package cz.cuni.mff.d3s.autodebugger.runner.factories;

import cz.cuni.mff.d3s.autodebugger.model.common.TraceMode;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm.LLMBasedTestGenerator;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace.NaiveTraceBasedGenerator;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace.TemporalTraceBasedGenerator;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for TestGeneratorFactory functionality.
 * Tests the creation of different test generators with updated LLM configuration.
 */
class TestGeneratorFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void givenAiAssistedStrategy_whenCreatingTestGenerator_thenReturnsLLMBasedGenerator() throws Exception {
        // given
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("output");
        Path appJar = tempDir.resolve("app.jar");

        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);
        Files.createFile(appJar);

        JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
                .sourceCodePath(sourceDir)
                .outputDirectory(outputDir)
                .applicationPath(appJar)
                .build();

        // when
        TestGenerator generator = TestGeneratorFactory.createTestGenerator(
                runConfiguration, "ai-assisted", "mock-api-key");

        // then
        assertNotNull(generator);
        assertInstanceOf(LLMBasedTestGenerator.class, generator);
        assertEquals("ai-assisted", generator.getGenerationTechnique());
    }

    @Test
    void givenInvalidStrategy_whenCreatingTestGenerator_thenThrowsException() throws Exception {
        // given
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("output");
        Path appJar = tempDir.resolve("app.jar");

        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);
        Files.createFile(appJar);

        JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
                .sourceCodePath(sourceDir)
                .outputDirectory(outputDir)
                .applicationPath(appJar)
                .build();

        // when/then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            TestGeneratorFactory.createTestGenerator(runConfiguration, "invalid-strategy");
        });

        assertTrue(exception.getMessage().contains("Unknown test generation strategy"));
    }

    @Test
    void givenAiAssistedStrategyWithMockApiKey_whenCreatingTestGenerator_thenConfiguresCorrectly() throws Exception {
        // given
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("output");
        Path appJar = tempDir.resolve("app.jar");

        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);
        Files.createFile(appJar);

        JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
                .sourceCodePath(sourceDir)
                .outputDirectory(outputDir)
                .applicationPath(appJar)
                .build();

        // when
        TestGenerator generator = TestGeneratorFactory.createTestGenerator(
                runConfiguration, "ai-assisted", "test-mock-key");

        // then
        assertNotNull(generator);
        assertInstanceOf(LLMBasedTestGenerator.class, generator);

        // Verify the generator was configured properly by checking it doesn't throw on basic operations
        assertDoesNotThrow(() -> {
            assertEquals("ai-assisted", generator.getGenerationTechnique());
        });
    }

    @Test
    void givenTemporalTraceMode_whenCreatingTraceBasedGenerator_thenReturnsTemporalTraceBasedGenerator() throws Exception {
        // given
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("output");
        Path appJar = tempDir.resolve("app.jar");

        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);
        Files.createFile(appJar);

        JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
                .sourceCodePath(sourceDir)
                .outputDirectory(outputDir)
                .applicationPath(appJar)
                .traceMode(TraceMode.TEMPORAL)
                .build();

        // when
        TestGenerator generator = TestGeneratorFactory.createTestGenerator(
                runConfiguration, "trace-based-advanced");

        // then
        assertNotNull(generator);
        assertInstanceOf(TemporalTraceBasedGenerator.class, generator);
        assertEquals("Enhanced Temporal Trace-Based", generator.getGenerationTechnique());
    }

    @Test
    void givenNaiveTraceMode_whenCreatingTraceBasedGenerator_thenReturnsNaiveTraceBasedGenerator() throws Exception {
        // given
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("output");
        Path appJar = tempDir.resolve("app.jar");
        Path identifiersPath = tempDir.resolve("identifiers.ser");

        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);
        Files.createFile(appJar);

        // Create a mock identifier mapping file
        java.util.HashMap<Integer, Object> emptyMapping = new java.util.HashMap<>();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(
                Files.newOutputStream(identifiersPath))) {
            oos.writeObject(emptyMapping);
        }

        JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
                .sourceCodePath(sourceDir)
                .outputDirectory(outputDir)
                .applicationPath(appJar)
                .traceMode(TraceMode.NAIVE)
                .build();

        // when
        TestGenerator generator = TestGeneratorFactory.createTestGenerator(
                runConfiguration, "trace-based-basic", null, identifiersPath);

        // then
        assertNotNull(generator);
        assertInstanceOf(NaiveTraceBasedGenerator.class, generator);
    }

    @Test
    void givenDefaultTraceMode_whenCreatingTraceBasedGenerator_thenReturnsNaiveTraceBasedGenerator() throws Exception {
        // given - no explicit TraceMode set (defaults to NAIVE)
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("output");
        Path appJar = tempDir.resolve("app.jar");
        Path identifiersPath = tempDir.resolve("identifiers.ser");

        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);
        Files.createFile(appJar);

        // Create a mock identifier mapping file
        java.util.HashMap<Integer, Object> emptyMapping = new java.util.HashMap<>();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(
                Files.newOutputStream(identifiersPath))) {
            oos.writeObject(emptyMapping);
        }

        JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
                .sourceCodePath(sourceDir)
                .outputDirectory(outputDir)
                .applicationPath(appJar)
                .build();

        // when
        TestGenerator generator = TestGeneratorFactory.createTestGenerator(
                runConfiguration, "trace-based-basic", null, identifiersPath);

        // then
        assertNotNull(generator);
        assertInstanceOf(NaiveTraceBasedGenerator.class, generator);
    }
}
