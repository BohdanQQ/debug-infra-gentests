package cz.cuni.mff.d3s.autodebugger.analyzer.java;

import cz.cuni.mff.d3s.autodebugger.analyzer.common.AnalysisResult;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for Phase 2→3 (Instrumentation→Analysis).
 * Verifies that paths and data are correctly propagated from InstrumentationResult to AnalysisResult.
 */
class InstrumentationToAnalysisContractTest {

    @TempDir
    Path tempDir;

    private JavaRunConfiguration testConfig;
    private Path instrumentationJarPath;
    private Path traceFilePath;
    private Path identifierMappingPath;
    private Path outputDirectory;

    /**
     * Testable DiSLAnalyzer that simulates successful DiSL execution
     * by creating the expected trace files.
     */
    private static class TestableAnalyzer extends DiSLAnalyzer {
        private final Runnable preExecutionHook;

        public TestableAnalyzer(JavaRunConfiguration config, Runnable preExecutionHook) {
            super(config);
            this.preExecutionHook = preExecutionHook;
        }

        @Override
        public java.util.List<String> buildExecutionCommand(Path instrumentationJarPath) {
            // Execute the hook before returning a simple command
            preExecutionHook.run();

            // Return a simple command that will succeed (echo command)
            return java.util.List.of("echo", "Mock DiSL execution");
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

        // Create mock instrumentation jar
        instrumentationJarPath = tempDir.resolve("test-instrumentation.jar");
        Files.createFile(instrumentationJarPath);

        // Set up output directory and file paths
        outputDirectory = tempDir.resolve("output");
        Files.createDirectories(outputDirectory);

        traceFilePath = outputDirectory.resolve("trace.ser");
        identifierMappingPath = outputDirectory.resolve("identifiers.ser");

        // Create test configuration
        testConfig = JavaRunConfiguration.builder()
                .applicationPath(tempDir.resolve("test-app.jar"))
                .sourceCodePath(tempDir.resolve("src"))
                .dislHomePath(tempDir.resolve("disl"))
                .outputDirectory(outputDirectory)
                .targetMethod(methodIdentifier)
                .build();

        // Create necessary directories and files
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("disl"));
        Files.createFile(tempDir.resolve("test-app.jar"));
    }

    // ========== Path Propagation Tests ==========

    @Test
    void givenValidInstrumentationResult_whenExecuteAnalysis_thenProducesAnalysisResultWithCorrectTraceFilePath() throws IOException {
        // Given
        Files.createFile(traceFilePath);
        Files.createFile(identifierMappingPath);

        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, () -> {
            // Simulate DiSL creating the trace files
            try {
                if (!Files.exists(traceFilePath)) {
                    Files.createFile(traceFilePath);
                }
                if (!Files.exists(identifierMappingPath)) {
                    Files.createFile(identifierMappingPath);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        InstrumentationResult instrumentation = InstrumentationResult.builder()
                .primaryArtifact(instrumentationJarPath)
                .traceFilePath(traceFilePath)
                .identifiersMappingPath(identifierMappingPath)
                .build();

        // When
        AnalysisResult result = analyzer.executeAnalysis(instrumentation);

        // Then
        assertNotNull(result);
        assertEquals(instrumentation.getTraceFilePath(), result.getTraceFilePath(),
                "TraceFilePath should be propagated from InstrumentationResult to AnalysisResult");
    }

    @Test
    void givenValidInstrumentationResult_whenExecuteAnalysis_thenIdentifiersMappingPathIsCorrectlyPropagated() throws IOException {
        // Given
        Files.createFile(traceFilePath);
        Files.createFile(identifierMappingPath);

        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, () -> {
            // Simulate DiSL creating the trace files
            try {
                if (!Files.exists(traceFilePath)) {
                    Files.createFile(traceFilePath);
                }
                if (!Files.exists(identifierMappingPath)) {
                    Files.createFile(identifierMappingPath);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        InstrumentationResult instrumentation = InstrumentationResult.builder()
                .primaryArtifact(instrumentationJarPath)
                .traceFilePath(traceFilePath)
                .identifiersMappingPath(identifierMappingPath)
                .build();

        // When
        AnalysisResult result = analyzer.executeAnalysis(instrumentation);

        // Then
        assertNotNull(result);
        assertEquals(instrumentation.getIdentifiersMappingPath(), result.getIdentifiersMappingPath(),
                "IdentifiersMappingPath should be propagated from InstrumentationResult to AnalysisResult");
    }

    @Test
    void givenRunConfiguration_whenExecuteAnalysis_thenOutputDirectoryIsCorrectlySetInAnalysisResult() throws IOException {
        // Given
        Files.createFile(traceFilePath);
        Files.createFile(identifierMappingPath);

        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, () -> {
            // Simulate DiSL creating the trace files
            try {
                if (!Files.exists(traceFilePath)) {
                    Files.createFile(traceFilePath);
                }
                if (!Files.exists(identifierMappingPath)) {
                    Files.createFile(identifierMappingPath);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        InstrumentationResult instrumentation = InstrumentationResult.builder()
                .primaryArtifact(instrumentationJarPath)
                .traceFilePath(traceFilePath)
                .identifiersMappingPath(identifierMappingPath)
                .build();

        // When
        AnalysisResult result = analyzer.executeAnalysis(instrumentation);

        // Then
        assertNotNull(result);
        assertEquals(testConfig.getOutputDirectory(), result.getOutputDirectory(),
                "OutputDirectory from RunConfiguration should be set in AnalysisResult");
    }

    // ========== Contract Verification Tests ==========

    @Test
    void givenInstrumentationResult_whenExecuteAnalysis_thenAllRequiredFieldsAppearInAnalysisResult() throws IOException {
        // Given
        Files.createFile(traceFilePath);
        Files.createFile(identifierMappingPath);

        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, () -> {
            // Simulate DiSL creating the trace files
            try {
                if (!Files.exists(traceFilePath)) {
                    Files.createFile(traceFilePath);
                }
                if (!Files.exists(identifierMappingPath)) {
                    Files.createFile(identifierMappingPath);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        InstrumentationResult instrumentation = InstrumentationResult.builder()
                .primaryArtifact(instrumentationJarPath)
                .traceFilePath(traceFilePath)
                .identifiersMappingPath(identifierMappingPath)
                .build();

        // When
        AnalysisResult result = analyzer.executeAnalysis(instrumentation);

        // Then
        assertNotNull(result, "AnalysisResult should not be null");
        assertNotNull(result.getTraceFilePath(), "TraceFilePath should be present in AnalysisResult");
        assertNotNull(result.getIdentifiersMappingPath(), "IdentifiersMappingPath should be present in AnalysisResult");
        assertNotNull(result.getOutputDirectory(), "OutputDirectory should be present in AnalysisResult");

        // Verify all fields match expected values
        assertEquals(instrumentation.getTraceFilePath(), result.getTraceFilePath());
        assertEquals(instrumentation.getIdentifiersMappingPath(), result.getIdentifiersMappingPath());
        assertEquals(testConfig.getOutputDirectory(), result.getOutputDirectory());
    }

    @Test
    void givenAbsolutePaths_whenExecuteAnalysis_thenPathValuesAreNotTransformed() throws IOException {
        // Given - Create absolute paths
        Path absoluteTracePath = traceFilePath.toAbsolutePath();
        Path absoluteIdentifierPath = identifierMappingPath.toAbsolutePath();
        Path absoluteOutputDir = outputDirectory.toAbsolutePath();

        Files.createFile(absoluteTracePath);
        Files.createFile(absoluteIdentifierPath);

        JavaRunConfiguration configWithAbsolutePaths = JavaRunConfiguration.builder()
                .applicationPath(tempDir.resolve("test-app.jar"))
                .sourceCodePath(tempDir.resolve("src"))
                .dislHomePath(tempDir.resolve("disl"))
                .outputDirectory(absoluteOutputDir)
                .targetMethod(testConfig.getTargetMethod())
                .build();

        TestableAnalyzer analyzer = new TestableAnalyzer(configWithAbsolutePaths, () -> {
            // Simulate DiSL creating the trace files
            try {
                if (!Files.exists(absoluteTracePath)) {
                    Files.createFile(absoluteTracePath);
                }
                if (!Files.exists(absoluteIdentifierPath)) {
                    Files.createFile(absoluteIdentifierPath);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        InstrumentationResult instrumentation = InstrumentationResult.builder()
                .primaryArtifact(instrumentationJarPath)
                .traceFilePath(absoluteTracePath)
                .identifiersMappingPath(absoluteIdentifierPath)
                .build();

        // When
        AnalysisResult result = analyzer.executeAnalysis(instrumentation);

        // Then - Verify paths are not transformed (absolute paths stay absolute)
        assertTrue(result.getTraceFilePath().isAbsolute(),
                "TraceFilePath should remain absolute");
        assertTrue(result.getIdentifiersMappingPath().isAbsolute(),
                "IdentifiersMappingPath should remain absolute");
        assertTrue(result.getOutputDirectory().isAbsolute(),
                "OutputDirectory should remain absolute");

        assertEquals(absoluteTracePath, result.getTraceFilePath(),
                "Absolute trace path should not be transformed");
        assertEquals(absoluteIdentifierPath, result.getIdentifiersMappingPath(),
                "Absolute identifier mapping path should not be transformed");
        assertEquals(absoluteOutputDir, result.getOutputDirectory(),
                "Absolute output directory should not be transformed");
    }

    // ========== Error Boundary Tests ==========

    @Test
    void givenMissingTraceFilePath_whenExecuteAnalysis_thenThrowsIllegalStateException() {
        // Given - InstrumentationResult with null traceFilePath
        InstrumentationResult instrumentation = InstrumentationResult.builder()
                .primaryArtifact(instrumentationJarPath)
                .traceFilePath(null)
                .identifiersMappingPath(identifierMappingPath)
                .build();

        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, () -> {
            // Do nothing - simulate successful execution but no trace file
        });

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            analyzer.executeAnalysis(instrumentation);
        });

        assertTrue(exception.getMessage().contains("Trace file path is null"),
                "Exception should indicate trace file path is null");
    }

    @Test
    void givenTraceFileNotCreated_whenExecuteAnalysis_thenThrowsIllegalStateException() throws IOException {
        // Given - InstrumentationResult with traceFilePath but file is not created
        Files.createFile(identifierMappingPath);

        InstrumentationResult instrumentation = InstrumentationResult.builder()
                .primaryArtifact(instrumentationJarPath)
                .traceFilePath(traceFilePath)
                .identifiersMappingPath(identifierMappingPath)
                .build();

        TestableAnalyzer analyzer = new TestableAnalyzer(testConfig, () -> {
            // Simulate DiSL execution that doesn't create the trace file
            try {
                if (!Files.exists(identifierMappingPath)) {
                    Files.createFile(identifierMappingPath);
                }
                // Intentionally NOT creating traceFilePath
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            analyzer.executeAnalysis(instrumentation);
        });

        assertTrue(exception.getMessage().contains("Trace file not created"),
                "Exception should indicate trace file was not created");
        assertTrue(exception.getMessage().contains(traceFilePath.toString()),
                "Exception should include the expected trace file path");
    }
}

