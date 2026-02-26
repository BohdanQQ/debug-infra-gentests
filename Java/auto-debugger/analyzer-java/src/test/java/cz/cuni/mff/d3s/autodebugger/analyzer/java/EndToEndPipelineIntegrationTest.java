package cz.cuni.mff.d3s.autodebugger.analyzer.java;

import cz.cuni.mff.d3s.autodebugger.instrumentor.java.DiSLInstrumentor;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.DiSLModel;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContext;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace.NaiveTraceBasedGenerator;
import cz.cuni.mff.d3s.autodebugger.testutils.StubResultsHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the complete pipeline from instrumentation to test generation.
 * 
 * This test validates the complete 4-stage workflow:
 * 1. Stage 1 (Instrumentation): DiSLInstrumentor generates instrumentation files and identifier mapping
 * 2. Stage 2 (Mock Trace): Create mock trace data simulating DiSL runtime output
 * 3. Stage 3 (Deserialization): Load trace and identifier mapping from files
 * 4. Stage 4 (Test Generation): Generate tests using NaiveTraceBasedGenerator
 */
class EndToEndPipelineIntegrationTest {

    @TempDir
    Path tempDir;

    private Path mockDislHome;
    private Path testOutputDirectory;
    private Path sourceCodePath;
    private Path outputDirectory;
    private JavaMethodIdentifier targetMethod;

    @BeforeEach
    void setUp() throws Exception {
        // Set up directory structure
        mockDislHome = tempDir.resolve("mock-disl");
        testOutputDirectory = tempDir.resolve("generated-tests");
        sourceCodePath = tempDir.resolve("src");
        outputDirectory = tempDir.resolve("output");

        Files.createDirectories(testOutputDirectory);
        Files.createDirectories(sourceCodePath);
        Files.createDirectories(outputDirectory);

        // Create mock DiSL structure
        createMockDislStructure();

        // Create target method identifier
        JavaPackageIdentifier packageIdentifier = new JavaPackageIdentifier("com.example");
        JavaClassIdentifier classIdentifier = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .packageIdentifier(packageIdentifier)
                        .className("Calculator")
                        .build()
        );

        targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(classIdentifier)
                        .methodName("add")
                        .returnType("int")
                        .parameterTypes(List.of("int", "int"))
                        .build()
        );
    }

    private void createMockDislStructure() throws Exception {
        Files.createDirectories(mockDislHome.resolve("bin"));
        Files.createDirectories(mockDislHome.resolve("output/lib"));
        Files.writeString(mockDislHome.resolve("bin/disl.py"), "# mock");
    }

    /**
     * Test Case: Complete Pipeline from Instrumentation to Test Generation
     * 
     * This test verifies that:
     * 1. Instrumentation generates correct artifacts (JAR, identifier mapping)
     * 2. Mock trace data can be created and serialized
     * 3. Identifier mapping can be loaded from the instrumentation output
     * 4. Test generator can use the trace and mapping to generate tests
     * 5. Generated tests contain the expected values from the trace
     */
    @Test
    void givenCompleteWorkflow_whenRunningFullPipeline_thenGeneratesTestsWithCorrectValues() throws Exception {
        // ===== STAGE 1: Generate Instrumentation =====
        
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
                .targetMethod(targetMethod)
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

        DiSLModel model = new DiSLModel(targetMethod, exportableValues);

        // When - Generate instrumentation
        InstrumentationResult instrumentationResult = instrumentor.generateInstrumentation(model);

        // Then - Verify instrumentation artifacts exist
        assertNotNull(instrumentationResult, "InstrumentationResult should not be null");
        assertTrue(Files.exists(instrumentationResult.getPrimaryArtifact()), "Instrumentation JAR should exist");
        assertTrue(Files.exists(instrumentationResult.getIdentifiersMappingPath()), "Identifier mapping should exist");

        // ===== STAGE 3: Load Identifier Mapping from Instrumentation =====

        // Load the actual identifier mapping that was created by the instrumentor
        Path identifierMappingPath = instrumentationResult.getIdentifiersMappingPath();
        Map<Integer, JavaValueIdentifier> loadedMapping;

        try (FileInputStream fileInput = new FileInputStream(identifierMappingPath.toFile());
             ObjectInputStream objectInput = new ObjectInputStream(fileInput)) {
            @SuppressWarnings("unchecked")
            Map<Integer, JavaValueIdentifier> mapping = (HashMap<Integer, JavaValueIdentifier>) objectInput.readObject();
            loadedMapping = mapping;
        }

        // Verify the mapping was loaded correctly
        assertNotNull(loadedMapping, "Identifier mapping should be loaded");
        assertFalse(loadedMapping.isEmpty(), "Identifier mapping should not be empty");
        assertEquals(2, loadedMapping.size(), "Should have 2 identifiers (one for each parameter)");

        // ===== STAGE 2: Create Mock Trace (simulating DiSL runtime output) =====

        // Get the actual slot IDs from the loaded mapping
        // Find the slot IDs for the two arguments
        Integer slot1 = null;
        Integer slot2 = null;
        for (Map.Entry<Integer, JavaValueIdentifier> entry : loadedMapping.entrySet()) {
            JavaValueIdentifier identifier = entry.getValue();
            if (identifier instanceof JavaArgumentIdentifier argId) {
                if (argId.getArgumentSlot() == 0) {
                    slot1 = entry.getKey();
                } else if (argId.getArgumentSlot() == 1) {
                    slot2 = entry.getKey();
                }
            }
        }

        assertNotNull(slot1, "Should find slot for first argument");
        assertNotNull(slot2, "Should find slot for second argument");

        // Create trace with known values: 42 and 17, using the actual slot IDs
        Trace mockTrace = new Trace();
        mockTrace.addIntValue(slot1, 42);  // First argument
        mockTrace.addIntValue(slot2, 17);  // Second argument

        // Serialize the trace to the expected location
        Path traceFilePath = instrumentationResult.getTraceFilePath();
        StubResultsHelper.writeSerializedTrace(traceFilePath, mockTrace);

        // ===== STAGE 4: Generate Tests =====

        // Create test generation context
        TestGenerationContext context = TestGenerationContext.builder()
                .targetMethod(targetMethod)
                .outputDirectory(testOutputDirectory)
                .build();

        // Create test generator with the loaded identifier mapping
        NaiveTraceBasedGenerator generator = new NaiveTraceBasedGenerator(loadedMapping);

        // Generate tests from the trace
        List<Path> generatedTestFiles = generator.generateTests(mockTrace, context);

        // ===== VERIFICATION: Ensure Generated Tests Contain Expected Values =====

        assertNotNull(generatedTestFiles, "Generated test files should not be null");
        assertFalse(generatedTestFiles.isEmpty(), "Should generate at least one test file");

        // Read the generated test file
        Path testFile = generatedTestFiles.get(0);
        assertTrue(Files.exists(testFile), "Generated test file should exist");

        String testContent = Files.readString(testFile);

        // Verify the test contains our known values (42 and 17)
        assertTrue(testContent.contains("42"),
                "Generated test should contain the value 42 from the trace");
        assertTrue(testContent.contains("17"),
                "Generated test should contain the value 17 from the trace");

        // Verify basic test structure
        assertTrue(testContent.contains("class"), "Generated test should contain a class declaration");
        assertTrue(testContent.contains("@Test"), "Generated test should contain @Test annotation");
        assertTrue(testContent.contains("add"), "Generated test should reference the target method 'add'");

        // Verify the test file name is correct
        assertTrue(testFile.getFileName().toString().endsWith("Test.java"),
                "Generated test file should end with 'Test.java'");
    }
}

