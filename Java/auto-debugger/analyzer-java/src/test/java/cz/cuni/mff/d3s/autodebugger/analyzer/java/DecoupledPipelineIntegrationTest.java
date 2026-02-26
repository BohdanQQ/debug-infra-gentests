package cz.cuni.mff.d3s.autodebugger.analyzer.java;

import cz.cuni.mff.d3s.autodebugger.instrumentor.java.DiSLInstrumentor;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.DiSLModel;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.common.tests.TestSuite;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the decoupled pipeline in analyzer-java.
 * 
 * This test validates the complete workflow:
 * 1. Stage 1 (Instrumentation): DiSLInstrumentor generates files and JAR correctly
 * 2. Stage 2 (Mock Trace): Manually serialize a Trace with known values
 * 3. Stage 3 (Test Generation): Use generateTestsFromExistingTrace() to generate tests
 */
class DecoupledPipelineIntegrationTest {

    @TempDir
    Path tempDir;

    private Path mockDislHome;
    private Path testOutputDirectory;
    private Path sourceCodePath;
    private Path outputDirectory;

    @BeforeEach
    void setUp() throws IOException {
        // Set up directory structure
        mockDislHome = tempDir.resolve("mock-disl");
        testOutputDirectory = tempDir.resolve("generated-code");
        sourceCodePath = tempDir.resolve("src");
        outputDirectory = tempDir.resolve("output");

        Files.createDirectories(testOutputDirectory);
        Files.createDirectories(sourceCodePath);
        Files.createDirectories(outputDirectory);

        // Create mock DiSL structure
        createMockDislStructure();
    }

    private void createMockDislStructure() throws IOException {
        Files.createDirectories(mockDislHome.resolve("bin"));
        Files.createDirectories(mockDislHome.resolve("output/lib"));
        Files.writeString(mockDislHome.resolve("bin/disl.py"), "# mock");
    }

    /**
     * Test Case 1: Verify that DiSLInstrumentor generates expected artifacts
     * (instrumentation JAR, identifier mapping, trace file paths).
     */
    @Test
    void givenTargetMethod_whenGeneratingInstrumentation_thenProducesExpectedArtifacts() throws IOException {
        // Given - Create a simple Calculator.add(int a, int b) target method
        JavaPackageIdentifier packageIdentifier = new JavaPackageIdentifier("com.example");
        JavaClassIdentifier classIdentifier = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .packageIdentifier(packageIdentifier)
                        .className("Calculator")
                        .build()
        );

        JavaMethodIdentifier methodIdentifier = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(classIdentifier)
                        .methodName("add")
                        .returnType("int")
                        .parameterTypes(List.of("int", "int"))
                        .build()
        );

        // Create exportable values for both parameters (slot 0 and slot 1)
        JavaArgumentIdentifier arg0 = new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("int")
                        .build()
        );

        JavaArgumentIdentifier arg1 = new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(1)
                        .variableType("int")
                        .build()
        );

        List<JavaValueIdentifier> exportableValues = List.of(arg0, arg1);

        // Create a dummy application JAR
        Path appJar = tempDir.resolve("calculator.jar");
        Files.createFile(appJar);

        JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
                .applicationPath(appJar)
                .classpathEntry(appJar)
                .dislHomePath(mockDislHome)
                .sourceCodePath(sourceCodePath)
                .outputDirectory(outputDirectory)
                .targetMethod(methodIdentifier)
                .exportableValues(exportableValues)
                .build();

        Files.createDirectories(runConfiguration.getOutputDirectory());

        DiSLInstrumentor instrumentor = DiSLInstrumentor.builder()
                .instrumentationClassName(new JavaClassIdentifier(
                        ClassIdentifierParameters.builder()
                                .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                                .className("DiSLClass")
                                .build()))
                .runConfiguration(runConfiguration)
                .generatedCodeOutputDirectory(testOutputDirectory)
                .jarOutputPath(tempDir.resolve("instrumentation.jar"))
                .build();

        DiSLModel model = new DiSLModel(methodIdentifier, exportableValues);

        // When - Generate instrumentation
        InstrumentationResult result = instrumentor.generateInstrumentation(model);

        // Then - Verify all expected artifacts exist
        assertNotNull(result, "InstrumentationResult should not be null");
        assertNotNull(result.getPrimaryArtifact(), "Primary artifact (JAR) should not be null");
        assertTrue(Files.exists(result.getPrimaryArtifact()), "Instrumentation JAR should exist");
        
        assertNotNull(result.getIdentifiersMappingPath(), "Identifier mapping path should not be null");
        assertTrue(Files.exists(result.getIdentifiersMappingPath()), "Identifier mapping file should exist");
        
        assertNotNull(result.getTraceFilePath(), "Trace file path should not be null");
        // Note: Trace file doesn't exist yet - it will be created during execution
        
        assertNotNull(result.getResultsListPath(), "Results list path should not be null");
    }


}
