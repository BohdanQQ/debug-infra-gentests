package cz.cuni.mff.d3s.autodebugger.analyzer.java;

import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for error handling scenarios in DiSLAnalyzer.
 * Focuses on validation logic for instrumentation inputs.
 */
class DiSLAnalyzerErrorHandlingTest {

    @TempDir
    Path tempDir;

    private Path instrumentationJar;
    private JavaRunConfiguration config;

    @BeforeEach
    void setUp() throws IOException {
        // Create mock instrumentation jar
        instrumentationJar = tempDir.resolve("instrumentation.jar");
        Files.createFile(instrumentationJar);

        // Create minimal configuration
        config = JavaRunConfiguration.builder()
            .outputDirectory(tempDir)
            .applicationPath(tempDir.resolve("app.jar"))
            .dislHomePath(tempDir.resolve("disl"))
            .build();
    }

    @Test
    void givenNullInstrumentationResult_whenValidateInstrumentation_thenThrowsIllegalArgumentException() {
        // given
        var analyzer = new DiSLAnalyzer(config);

        // when/then
        assertThrows(IllegalArgumentException.class, () -> {
            analyzer.validateInstrumentation(null);
        });
    }

    @Test
    void givenNullPrimaryArtifact_whenValidateInstrumentation_thenThrowsIllegalArgumentException() {
        // given
        var instrumentation = InstrumentationResult.builder()
            .primaryArtifact(null)
            .build();

        var analyzer = new DiSLAnalyzer(config);

        // when/then
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            analyzer.validateInstrumentation(instrumentation);
        });

        assertTrue(exception.getMessage().contains("cannot be null"),
            "Exception should mention null artifact, got: " + exception.getMessage());
    }

    @Test
    void givenNonExistentPrimaryArtifact_whenValidateInstrumentation_thenThrowsIllegalArgumentException() {
        // given
        Path nonExistentJar = tempDir.resolve("nonexistent.jar");
        var instrumentation = InstrumentationResult.builder()
            .primaryArtifact(nonExistentJar)
            .build();

        var analyzer = new DiSLAnalyzer(config);

        // when/then
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            analyzer.validateInstrumentation(instrumentation);
        });

        assertTrue(exception.getMessage().contains("does not exist"),
            "Exception should mention file doesn't exist, got: " + exception.getMessage());
    }

    @Test
    void givenNonJarPrimaryArtifact_whenValidateInstrumentation_thenThrowsIllegalArgumentException() throws IOException {
        // given
        Path nonJarFile = tempDir.resolve("instrumentation.txt");
        Files.createFile(nonJarFile);

        var instrumentation = InstrumentationResult.builder()
            .primaryArtifact(nonJarFile)
            .build();

        var analyzer = new DiSLAnalyzer(config);

        // when/then
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            analyzer.validateInstrumentation(instrumentation);
        });

        assertTrue(exception.getMessage().contains("JAR") || exception.getMessage().contains("jar"),
            "Exception should mention JAR file requirement, got: " + exception.getMessage());
    }

    @Test
    void givenValidInstrumentation_whenValidateInstrumentation_thenSucceeds() {
        // given
        var instrumentation = InstrumentationResult.builder()
            .primaryArtifact(instrumentationJar)
            .build();

        var analyzer = new DiSLAnalyzer(config);

        // when/then - should not throw
        assertDoesNotThrow(() -> analyzer.validateInstrumentation(instrumentation));
    }
}

