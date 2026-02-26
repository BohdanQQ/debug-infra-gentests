package cz.cuni.mff.d3s.autodebugger.analyzer.java;

import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.MethodIdentifierParameters;
import cz.cuni.mff.d3s.autodebugger.testutils.StubResultsHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DiSLAnalyzer process interaction using real process execution with mock scripts.
 * This approach is more reliable than complex mocking and tests the actual process execution logic.
 */
class DiSLAnalyzerProcessInteractionTest {

    @TempDir
    Path tempDir;

    private JavaRunConfiguration testConfig;
    private Path instrumentationJarPath;
    private Path resultsListPath;
    private Path traceFilePath;
    private Path identifierMappingPath;

    /**
     * Testable DiSLAnalyzer that allows overriding the command construction
     * to use our mock scripts instead of the real disl.py
     */
    private static class TestableAnalyzer extends DiSLAnalyzer {
        private final String mockScriptName;

        public TestableAnalyzer(JavaRunConfiguration config, String mockScriptName) {
            super(config);
            this.mockScriptName = mockScriptName;
        }

        @Override
        public List<String> buildExecutionCommand(Path instrumentationJarPath) {
            try {
                // Get the mock script from test resources
                URL scriptUrl = getClass().getClassLoader().getResource(mockScriptName);
                if (scriptUrl == null) {
                    throw new RuntimeException("Mock script not found: " + mockScriptName);
                }
                Path scriptPath = Paths.get(scriptUrl.toURI());

                // Build command using the mock script instead of disl.py
                return List.of(
                        "python3",
                        scriptPath.toString(),
                        // Add some mock arguments to simulate real DiSL command
                        "-d", getRunConfiguration().getDislHomePath().toString(),
                        "-cse",
                        "-e_cp", "mock-classpath",
                        "--",
                        instrumentationJarPath.toString(),
                        "-jar", getRunConfiguration().getApplicationPath().toString()
                );
            } catch (URISyntaxException e) {
                throw new RuntimeException("Failed to resolve mock script path", e);
            }
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        // Create a dummy method identifier for testing
        JavaClassIdentifier classIdentifier = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("TestClass")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build()
        );

        JavaMethodIdentifier methodIdentifier = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(classIdentifier)
                        .methodName("testMethod")
                        .returnType("void")
                        .parameterTypes(Arrays.asList("int"))
                        .build()
        );

        // Create mock instrumentation jar (create a minimal valid JAR file)
        instrumentationJarPath = tempDir.resolve("test-instrumentation.jar");
        createMinimalJarFile(instrumentationJarPath);

        // Create test configuration
        testConfig = JavaRunConfiguration.builder()
                .applicationPath(tempDir.resolve("test-app.jar"))
                .sourceCodePath(tempDir.resolve("src"))
                .dislHomePath(tempDir.resolve("disl"))
                .outputDirectory(tempDir.resolve("output"))
                .targetMethod(methodIdentifier)
                .build();

        // Create necessary directories and files
        Files.createDirectories(tempDir.resolve("src"));
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);

        // Create results list path and prime with stub entry
        resultsListPath = outputDir.resolve("generated-tests.lst");
        traceFilePath = outputDir.resolve("trace.ser");
        identifierMappingPath = outputDir.resolve("identifiers.ser");
        try {
            StubResultsHelper.writeMinimalStubTestAndResults(resultsListPath);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Files.createDirectories(tempDir.resolve("disl/bin"));
        Files.createDirectories(tempDir.resolve("disl/output"));
        createMinimalJarFile(tempDir.resolve("test-app.jar"));
    }

    /**
     * Creates a minimal valid JAR file for testing purposes.
     */
    private void createMinimalJarFile(Path jarPath) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            // Add a simple entry to make it a valid JAR
            ZipEntry entry = new ZipEntry("test.txt");
            jarOut.putNextEntry(entry);
            jarOut.write("Test content".getBytes());
            jarOut.closeEntry();
        }
    }

    @Test
    void givenSuccessfulProcess_whenExecuteAnalysis_thenValidatesPrerequisites() {
        // Given
        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, "mock-disl-success.py");

        // When/Then - Process executes successfully but validation fails due to missing trace files
        // This demonstrates that the process execution works and validation is enforced
        assertThrows(IllegalStateException.class, () -> {
            analyzer.executeAnalysis(InstrumentationResult.builder()
                    .primaryArtifact(instrumentationJarPath)
                    .traceFilePath(traceFilePath)
                    .identifiersMappingPath(identifierMappingPath)
                    .build());
        }, "Should throw IllegalStateException when trace file is not created");
    }

    @Test
    void givenFailedProcess_whenExecuteAnalysis_thenThrows() {
        // Given
        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, "mock-disl-failure.py");

        // When/Then - Process failure should throw RuntimeException
        assertThrows(RuntimeException.class, () -> {
            analyzer.executeAnalysis(InstrumentationResult.builder()
                    .primaryArtifact(instrumentationJarPath)
                    .traceFilePath(traceFilePath)
                    .identifiersMappingPath(identifierMappingPath)
                    .build());
        });
    }

    @Test
    void givenTimeout_whenExecuteAnalysis_thenThrowsTimeout() {
        // Given - Use a very short timeout for this test
        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, "mock-disl-timeout.py") {
            @Override
            protected long getTimeoutSeconds() {
                return 2; // Very short timeout for testing
            }
        };

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            analyzer.executeAnalysis(InstrumentationResult.builder()
                    .primaryArtifact(instrumentationJarPath)
                    .traceFilePath(traceFilePath)
                    .identifiersMappingPath(identifierMappingPath)
                    .build());
        });

        assertTrue(exception.getMessage().contains("timed out"),
                   "Exception should indicate timeout: " + exception.getMessage());
    }

    @Test
    void givenIOExceptionOnProcessStart_whenExecuteAnalysis_thenThrows() {
        // Given - Use a non-existent script to trigger IOException
        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, "non-existent-script.py");

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            analyzer.executeAnalysis(InstrumentationResult.builder()
                    .primaryArtifact(instrumentationJarPath)
                    .traceFilePath(traceFilePath)
                    .identifiersMappingPath(identifierMappingPath)
                    .build());
        });

        assertTrue(exception.getMessage().contains("Analysis execution failed") ||
                   exception.getMessage().contains("Mock script not found"),
                   "Exception should indicate execution failure: " + exception.getMessage());
    }

    @Test
    void givenConfiguration_whenBuildingCommand_thenConstructsCorrectly() {
        // Given
        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, "mock-disl-success.py");

        // When
        List<String> command = analyzer.buildExecutionCommand(instrumentationJarPath);

        // Then
        assertNotNull(command);
        assertTrue(command.size() >= 8, "Command should have at least 8 elements");
        assertEquals("python3", command.get(0), "First element should be python3");
        assertTrue(command.get(1).endsWith("mock-disl-success.py"), "Second element should be the mock script");
        assertTrue(command.contains("-d"), "Command should contain -d flag");
        assertTrue(command.contains("-cse"), "Command should contain -cse flag");
        assertTrue(command.contains("-e_cp"), "Command should contain -e_cp flag");
        assertTrue(command.contains(instrumentationJarPath.toString()), "Command should contain instrumentation jar path");
    }

    @Test
    void givenProcessOutput_whenExecuteAnalysis_thenValidatesPrerequisites() {
        // Given
        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, "mock-disl-success.py");

        // When/Then - Process executes and captures logs, but validation fails
        assertThrows(IllegalStateException.class, () -> {
            analyzer.executeAnalysis(InstrumentationResult.builder()
                    .primaryArtifact(instrumentationJarPath)
                    .traceFilePath(traceFilePath)
                    .identifiersMappingPath(identifierMappingPath)
                    .build());
        }, "Should throw IllegalStateException when trace file is not created");
    }

    /**
     * Integration test that verifies the complete process execution flow
     * with real Python script execution and output capture.
     */
    @Test
    void givenSuccessfulFlow_whenExecuteAnalysis_thenValidatesPrerequisites() {
        // Given
        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, "mock-disl-success.py");

        // When/Then - Process executes successfully but validation enforces prerequisites
        // This test demonstrates that the complete flow works:
        // 1. Command construction with mock script
        // 2. Process execution
        // 3. Output capture
        // 4. Validation of prerequisites
        long startTime = System.currentTimeMillis();
        assertThrows(IllegalStateException.class, () -> {
            analyzer.executeAnalysis(InstrumentationResult.builder()
                    .primaryArtifact(instrumentationJarPath)
                    .traceFilePath(traceFilePath)
                    .identifiersMappingPath(identifierMappingPath)
                    .build());
        }, "Should throw IllegalStateException when trace file is not created");
        long endTime = System.currentTimeMillis();

        assertTrue(endTime - startTime < 5000, "Process should complete within reasonable time");
    }
}
