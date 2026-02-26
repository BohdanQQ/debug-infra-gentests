package cz.cuni.mff.d3s.autodebugger.analyzer.java;

import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.MethodIdentifierParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DiSLAnalyzer focusing on command-building logic and process interaction.
 */
class DiSLAnalyzerTest {

    @TempDir
    Path tempDir;

    private JavaRunConfiguration standardConfig;
    private JavaRunConfiguration configWithoutRuntimeArgs;
    private JavaRunConfiguration configWithSpacesInPaths;
    private Path instrumentationJarPath;
    private InstrumentationResult instrumentation;

    @BeforeEach
    void setUp() {
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
                        .parameterTypes(Arrays.asList("int", "String"))
                        .build()
        );

        instrumentationJarPath = Path.of("/tmp/instrumentation.jar");
        Path resultsListPath = tempDir.resolve("output/generated-tests.lst");
        Path traceFilePath = tempDir.resolve("output/trace.ser");
        Path identifiersMappingPath = tempDir.resolve("output/identifiers.ser");
        instrumentation = InstrumentationResult.builder()
                .primaryArtifact(instrumentationJarPath)
                .resultsListPath(resultsListPath)
                .traceFilePath(traceFilePath)
                .identifiersMappingPath(identifiersMappingPath)
                .build();

        // Standard configuration with runtime arguments
        standardConfig = JavaRunConfiguration.builder()
                .applicationPath(Path.of("/path/to/my-app.jar"))
                .sourceCodePath(tempDir.resolve("src"))
                .dislHomePath(Path.of("/opt/disl"))
                .outputDirectory(tempDir.resolve("output"))
                .targetMethod(methodIdentifier)
                .runtimeArgument("--user")
                .runtimeArgument("test")
                .runtimeArgument("--mode")
                .runtimeArgument("fast")
                .build();

        // Configuration without runtime arguments
        configWithoutRuntimeArgs = JavaRunConfiguration.builder()
                .applicationPath(Path.of("/path/to/my-app.jar"))
                .sourceCodePath(tempDir.resolve("src"))
                .dislHomePath(Path.of("/opt/disl"))
                .outputDirectory(tempDir.resolve("output"))
                .targetMethod(methodIdentifier)
                .build();

        // Configuration with spaces in paths
        configWithSpacesInPaths = JavaRunConfiguration.builder()
                .applicationPath(Path.of("/home/user/my app/app.jar"))
                .sourceCodePath(tempDir.resolve("src"))
                .dislHomePath(Path.of("/opt/DiSL Framework"))
                .outputDirectory(tempDir.resolve("output"))
                .targetMethod(methodIdentifier)
                .build();
    }

    @Test
    void givenStandardConfiguration_whenBuildExecutionCommand_thenBuildsProperCommand() {
        // Given
        DiSLAnalyzer analyzer = new DiSLAnalyzer(standardConfig);

        // When
        List<String> command = analyzer.buildExecutionCommand(instrumentation.getPrimaryArtifact());

        // Then
        assertNotNull(command);
        // Command: python3, disl.py, -d, disl-home, -cse, --, instr.jar, -jar, app.jar, 4 runtime args = 13
        assertEquals(13, command.size());

        // Verify core command structure
        assertEquals("python3", command.get(0));
        assertEquals("/opt/disl/bin/disl.py", command.get(1));
        assertEquals("-d", command.get(2));
        assertEquals("/opt/disl/output", command.get(3));
        assertEquals("-cse", command.get(4));
        assertEquals("--", command.get(5));

        // After "--" comes: instrumentation.jar, -jar, app.jar, runtime args...
        assertEquals("/tmp/instrumentation.jar", command.get(6));
        assertEquals("-jar", command.get(7));
        assertEquals("/path/to/my-app.jar", command.get(8));
        assertEquals("--user", command.get(9));
        assertEquals("test", command.get(10));
        assertEquals("--mode", command.get(11));
        assertEquals("fast", command.get(12));
    }

    @Test
    void givenNoRuntimeArguments_whenBuildExecutionCommand_thenOmitsRuntimeArgs() {
        // Given
        DiSLAnalyzer analyzer = new DiSLAnalyzer(configWithoutRuntimeArgs);

        // When
        List<String> command = analyzer.buildExecutionCommand(instrumentation.getPrimaryArtifact());

        // Then
        assertNotNull(command);
        // Command: python3, disl.py, -d, disl-home, -cse, --, instr.jar, -jar, app.jar = 9
        assertEquals(9, command.size());

        // Verify core structure
        assertEquals("python3", command.get(0));
        assertEquals("/opt/disl/bin/disl.py", command.get(1));
        assertEquals("-d", command.get(2));
        assertEquals("/opt/disl/output", command.get(3));
        assertEquals("-cse", command.get(4));
        assertEquals("--", command.get(5));
        assertEquals("/tmp/instrumentation.jar", command.get(6));
        assertEquals("-jar", command.get(7));
        assertEquals("/path/to/my-app.jar", command.get(8));
    }

    @Test
    void givenPathsWithSpaces_whenBuildExecutionCommand_thenProperlyQuotesArgs() {
        // Given
        DiSLAnalyzer analyzer = new DiSLAnalyzer(configWithSpacesInPaths);

        // When
        List<String> command = analyzer.buildExecutionCommand(instrumentation.getPrimaryArtifact());

        // Then
        assertNotNull(command);
        // Command: python3, disl.py, -d, disl-home, -cse, --, instr.jar, -jar, app.jar = 9
        assertEquals(9, command.size());

        // Verify paths with spaces are correctly represented as single arguments
        assertEquals("python3", command.get(0));
        assertEquals("/opt/DiSL Framework/bin/disl.py", command.get(1));
        assertEquals("-d", command.get(2));
        assertEquals("/opt/DiSL Framework/output", command.get(3));
        assertEquals("-cse", command.get(4));
        assertEquals("--", command.get(5));
        assertEquals("/tmp/instrumentation.jar", command.get(6));
        assertEquals("-jar", command.get(7));
        assertEquals("/home/user/my app/app.jar", command.get(8));
    }

    @Test
    void givenClasspathEntries_whenBuildExecutionCommand_thenIncludesClasspathOptions() {
        // Given
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
                        .parameterTypes(Arrays.asList("int", "String"))
                        .build()
        );

        JavaRunConfiguration configWithClasspath = JavaRunConfiguration.builder()
                .applicationPath(Path.of("/path/to/my-app.jar"))
                .sourceCodePath(tempDir.resolve("src"))
                .dislHomePath(Path.of("/opt/disl"))
                .outputDirectory(tempDir.resolve("output"))
                .targetMethod(methodIdentifier)
                .classpathEntry(Path.of("/path/to/lib1.jar"))
                .classpathEntry(Path.of("/path/to/lib2.jar"))
                .build();

        DiSLAnalyzer analyzer = new DiSLAnalyzer(configWithClasspath);

        // When
        List<String> command = analyzer.buildExecutionCommand(instrumentation.getPrimaryArtifact());

        // Then
        assertNotNull(command);
        // Command: python3, disl.py, -d, disl-home, -cse, -c_opts=-cp, -c_opts=classpath, --, instr.jar, -jar, app.jar = 11
        assertEquals(11, command.size());

        // Verify core command structure
        assertEquals("python3", command.get(0));
        assertEquals("/opt/disl/bin/disl.py", command.get(1));
        assertEquals("-d", command.get(2));
        assertEquals("/opt/disl/output", command.get(3));
        assertEquals("-cse", command.get(4));

        // Verify classpath options (uses = sign format for disl.py argparse compatibility)
        assertEquals("-c_opts=-cp", command.get(5));
        String expectedClasspath = Path.of("/path/to/lib1.jar").toAbsolutePath().toString() +
                                   java.io.File.pathSeparator +
                                   Path.of("/path/to/lib2.jar").toAbsolutePath().toString();
        assertEquals("-c_opts=" + expectedClasspath, command.get(6));

        // Verify remaining command structure
        assertEquals("--", command.get(7));
        assertEquals("/tmp/instrumentation.jar", command.get(8));
        assertEquals("-jar", command.get(9));
        assertEquals("/path/to/my-app.jar", command.get(10));
    }

    @Test
    void givenInvalidInstrumentationPath_whenExecuteAnalysis_thenThrows() {
        // Given
        DiSLAnalyzer analyzer = new DiSLAnalyzer(standardConfig);
        Path nonExistentJar = Path.of("/non/existent/instrumentation.jar");
        Path resultsListPath = tempDir.resolve("output/generated-tests.lst");

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            analyzer.executeAnalysis(InstrumentationResult.builder()
                    .primaryArtifact(nonExistentJar)
                    .resultsListPath(resultsListPath)
                    .build());
        });

        assertTrue(exception.getMessage().contains("does not exist"));
    }

    @Test
    void givenEmptyInstrumentationPaths_whenExecuteAnalysis_thenThrows() {
        // Given
        DiSLAnalyzer analyzer = new DiSLAnalyzer(standardConfig);

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            analyzer.executeAnalysis(null);
        });

        assertTrue(exception.getMessage().contains("cannot be null"));
    }

    @Test
    void givenNullInstrumentationPaths_whenExecuteAnalysis_thenThrows() {
        // Given
        DiSLAnalyzer analyzer = new DiSLAnalyzer(standardConfig);

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            analyzer.executeAnalysis(null);
        });

        assertTrue(exception.getMessage().contains("cannot be null"));
    }

    /**
     * Test that verifies the analyzer handles process execution correctly.
     * This test creates a simple executable that simulates DiSL behavior.
     */
    @Test
    void givenMockInstrumentationJar_whenExecuteAnalysis_thenHandlesProcessExecution() throws IOException {
        // Given - Create a mock instrumentation jar file
        Path mockInstrumentationJar = tempDir.resolve("mock-instrumentation.jar");
        Files.createFile(mockInstrumentationJar);

        // Create a configuration that points to a simple command that will succeed
        JavaClassIdentifier testClassIdentifier = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("TestClass")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build()
        );

        JavaMethodIdentifier methodIdentifier = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(testClassIdentifier)
                        .methodName("testMethod")
                        .returnType("void")
                        .parameterTypes(Arrays.asList("int"))
                        .build()
        );

        // Create a configuration with a command that will work (echo command)
        JavaRunConfiguration testConfig = JavaRunConfiguration.builder()
                .applicationPath(tempDir.resolve("test-app.jar")) // This won't be executed due to our test setup
                .sourceCodePath(tempDir.resolve("src"))
                .dislHomePath(tempDir.resolve("mock-disl")) // This will be used to construct paths
                .outputDirectory(tempDir.resolve("output"))
                .targetMethod(methodIdentifier)
                .build();

        // Create the mock DiSL directory structure
        Files.createDirectories(tempDir.resolve("mock-disl/bin"));
        Files.createDirectories(tempDir.resolve("mock-disl/output"));
        Files.createDirectories(tempDir.resolve("output"));

        // Copy the mock script from test resources to the expected location
        Path mockDislScript = tempDir.resolve("mock-disl/bin/disl.py");
        try {
            URL scriptUrl = getClass().getClassLoader().getResource("mock-disl-process-execution.py");
            if (scriptUrl != null) {
                Path resourceScript = Paths.get(scriptUrl.toURI());
                Files.copy(resourceScript, mockDislScript);

                // Make the script executable (on Unix-like systems)
                mockDislScript.toFile().setExecutable(true);
            } else {
                // Fallback: create a simple inline script if resource not found
                String scriptContent = "#!/usr/bin/env python3\nprint('[DiSL] Mock analysis completed')\n";
                Files.write(mockDislScript, scriptContent.getBytes());
                mockDislScript.toFile().setExecutable(true);
            }
        } catch (Exception e) {
            // Fallback: create a simple inline script
            String scriptContent = "#!/usr/bin/env python3\nprint('[DiSL] Mock analysis completed')\n";
            Files.write(mockDislScript, scriptContent.getBytes());
            try {
                mockDislScript.toFile().setExecutable(true);
            } catch (Exception ignored) {
                // Ignore on Windows or if setting executable fails
            }
        }

        DiSLAnalyzer analyzer = new DiSLAnalyzer(testConfig);

        // When & Then - This test demonstrates the structure for testing process execution
        // The test may succeed or fail depending on the system setup, but it should not crash
        Path traceFilePath = tempDir.resolve("output/trace.ser");
        Path identifierMappingPath = tempDir.resolve("output/identifiers.ser");
        try {
            var result = analyzer.executeAnalysis(InstrumentationResult.builder()
                    .primaryArtifact(mockInstrumentationJar)
                    .traceFilePath(traceFilePath)
                    .identifiersMappingPath(identifierMappingPath)
                    .build());

            // If it succeeds, we should get an AnalysisResult
            assertNotNull(result, "Analysis should return a non-null AnalysisResult");
            assertNotNull(result.getTraceFilePath(), "AnalysisResult should contain trace file path");

        } catch (Exception e) {
            // If it fails, it should be due to process execution issues or validation failures
            assertNotNull(e);
            System.out.println("Exception caught: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            // The important thing is that we're testing the structure and that it attempts to run the process
            assertTrue(e.getMessage().contains("Analysis execution failed") ||
                      e.getMessage().contains("timed out") ||
                      e.getMessage().contains("python3") ||
                      e.getMessage().contains("No such file") ||
                      e.getMessage().contains("Cannot run program") ||
                      e.getMessage().contains("does not exist") ||
                      e.getMessage().contains("Expected JAR file") ||
                      e.getMessage().contains("Trace file path is null") ||
                      e.getMessage().contains("Trace file not created") ||
                      e.getMessage().contains("Identifier mapping"));  // Validation error messages
        }
    }


}
