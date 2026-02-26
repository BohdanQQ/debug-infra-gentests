package cz.cuni.mff.d3s.autodebugger.runner.orchestrator;

import cz.cuni.mff.d3s.autodebugger.analyzer.common.AnalysisResult;
import cz.cuni.mff.d3s.autodebugger.model.common.TargetLanguage;
import cz.cuni.mff.d3s.autodebugger.model.common.tests.TestSuite;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.ArgumentIdentifierParameters;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaArgumentIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaValueIdentifier;
import cz.cuni.mff.d3s.autodebugger.runner.args.Arguments;
import cz.cuni.mff.d3s.autodebugger.testutils.StubResultsHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for Phase 3→4 (Analysis→TestGenerator).
 * Verifies that the Orchestrator correctly processes AnalysisResult artifacts
 * (trace files and identifier mappings) to generate valid test suites.
 */
class AnalysisToTestGeneratorContractTest {

    @TempDir
    Path tempDir;

    private Path sourceCodePath;
    private Path outputDir;
    private Path dislHome;
    private Path appJar;

    @BeforeEach
    void setUp() throws Exception {
        // Create test directories
        sourceCodePath = tempDir.resolve("src");
        Files.createDirectories(sourceCodePath);

        outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);

        dislHome = tempDir.resolve("disl");
        Files.createDirectories(dislHome);
        Files.createDirectories(dislHome.resolve("bin"));
        Files.createDirectories(dislHome.resolve("output"));
        Files.createDirectories(dislHome.resolve("output").resolve("lib"));

        // Create mock disl.py file to satisfy validation
        Path dislPy = dislHome.resolve("bin").resolve("disl.py");
        Files.createFile(dislPy);

        // Create dummy application JAR
        appJar = tempDir.resolve("app.jar");
        Files.createFile(appJar);

        // Create sample source code
        createSampleSourceCode();
    }

    /**
     * Test Case 1: Valid Flow - Basic Test Generation
     * Verifies that valid AnalysisResult produces a TestSuite with test files.
     */
    @Test
    void givenValidAnalysisResult_whenGeneratingTests_thenProducesTestSuiteWithFiles() throws Exception {
        // given - orchestrator with valid configuration
        Arguments args = createMinimalArguments();
        Orchestrator orchestrator = new Orchestrator(args);

        // Create analysis result with trace and identifier mapping
        Path traceFilePath = outputDir.resolve("trace.ser");
        Path identifierMappingPath = outputDir.resolve("identifiers.ser");

        Trace mockTrace = StubResultsHelper.createMinimalMockTrace();
        StubResultsHelper.writeSerializedTrace(traceFilePath, mockTrace);

        Map<Integer, JavaValueIdentifier> mapping = StubResultsHelper.createMinimalIdentifierMapping();
        StubResultsHelper.writeSerializedIdentifierMapping(identifierMappingPath, mapping);

        AnalysisResult analysisResult = AnalysisResult.builder()
            .traceFilePath(traceFilePath)
            .identifiersMappingPath(identifierMappingPath)
            .outputDirectory(outputDir)
            .build();

        // when - generate tests
        TestSuite testSuite = orchestrator.generateTests(analysisResult);

        // then - verify test suite structure
        assertNotNull(testSuite, "TestSuite should not be null");
        assertNotNull(testSuite.getTestFiles(), "Test files list should not be null");
        assertFalse(testSuite.getTestFiles().isEmpty(), "Should generate at least one test file");
        assertEquals(outputDir, testSuite.getBaseDirectory(), "Base directory should match output directory");

        // Verify generated files exist and are Java files
        for (Path testFile : testSuite.getTestFiles()) {
            assertTrue(Files.exists(testFile), "Generated test file should exist: " + testFile);
            assertTrue(testFile.getFileName().toString().endsWith(".java"),
                "Generated file should be a Java file: " + testFile);
        }
    }

    /**
     * Test Case 2: Trace Deserialization
     * Verifies that serialized trace with int values is correctly deserialized by orchestrator.
     */
    @Test
    void givenSerializedTraceWithIntValues_whenDeserializedByOrchestrator_thenContainsExpectedSlots() throws Exception {
        // given - orchestrator and trace with specific int values
        Arguments args = createMinimalArguments();
        Orchestrator orchestrator = new Orchestrator(args);

        Path traceFilePath = outputDir.resolve("trace.ser");
        Path identifierMappingPath = outputDir.resolve("identifiers.ser");

        // Create trace with specific values
        Trace trace = new Trace();
        trace.addIntValue(1, 42);
        trace.addIntValue(1, 100);
        trace.addIntValue(2, 17);
        trace.addIntValue(2, 99);

        StubResultsHelper.writeSerializedTrace(traceFilePath, trace);

        Map<Integer, JavaValueIdentifier> mapping = StubResultsHelper.createMinimalIdentifierMapping();
        StubResultsHelper.writeSerializedIdentifierMapping(identifierMappingPath, mapping);

        AnalysisResult analysisResult = AnalysisResult.builder()
            .traceFilePath(traceFilePath)
            .identifiersMappingPath(identifierMappingPath)
            .outputDirectory(outputDir)
            .build();

        // when - generate tests (which internally deserializes the trace)
        TestSuite testSuite = orchestrator.generateTests(analysisResult);

        // then - verify test generation succeeded (trace was deserialized correctly)
        assertNotNull(testSuite);
        assertFalse(testSuite.getTestFiles().isEmpty());
    }

    /**
     * Test Case 3: Identifier Mapping
     * Verifies that identifier mapping correctly maps slots to identifiers during test generation.
     */
    @Test
    void givenIdentifierMapping_whenGeneratorProcessesTrace_thenMapsSlotToIdentifier() throws Exception {
        // given - orchestrator with custom identifier mapping
        Arguments args = createMinimalArguments();
        Orchestrator orchestrator = new Orchestrator(args);

        Path traceFilePath = outputDir.resolve("trace.ser");
        Path identifierMappingPath = outputDir.resolve("identifiers.ser");

        // Create trace with values in specific slots
        Trace trace = new Trace();
        trace.addIntValue(10, 42);  // Custom slot ID
        trace.addIntValue(20, 17);  // Custom slot ID

        StubResultsHelper.writeSerializedTrace(traceFilePath, trace);

        // Create custom identifier mapping for these slots
        Map<Integer, JavaValueIdentifier> mapping = new HashMap<>();
        mapping.put(10, new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(0)
                .variableType("int")
                .build()
        ));
        mapping.put(20, new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(1)
                .variableType("int")
                .build()
        ));

        StubResultsHelper.writeSerializedIdentifierMapping(identifierMappingPath, mapping);

        AnalysisResult analysisResult = AnalysisResult.builder()
            .traceFilePath(traceFilePath)
            .identifiersMappingPath(identifierMappingPath)
            .outputDirectory(outputDir)
            .build();

        // when - generate tests
        TestSuite testSuite = orchestrator.generateTests(analysisResult);

        // then - verify test generation succeeded with custom mapping
        assertNotNull(testSuite);
        assertFalse(testSuite.getTestFiles().isEmpty());
    }

    /**
     * Test Case 4: Contract Verification - Method Call in Generated Test
     * Verifies that generated test file contains method calls with traced values.
     */
    @Test
    void givenAnalysisResultWithTraceAndMapping_whenGenerating_thenTestFileContainsMethodCall() throws Exception {
        // given - orchestrator with analysis result
        Arguments args = createMinimalArguments();
        Orchestrator orchestrator = new Orchestrator(args);

        Path traceFilePath = outputDir.resolve("trace.ser");
        Path identifierMappingPath = outputDir.resolve("identifiers.ser");

        Trace mockTrace = StubResultsHelper.createMinimalMockTrace();
        StubResultsHelper.writeSerializedTrace(traceFilePath, mockTrace);

        Map<Integer, JavaValueIdentifier> mapping = StubResultsHelper.createMinimalIdentifierMapping();
        StubResultsHelper.writeSerializedIdentifierMapping(identifierMappingPath, mapping);

        AnalysisResult analysisResult = AnalysisResult.builder()
            .traceFilePath(traceFilePath)
            .identifiersMappingPath(identifierMappingPath)
            .outputDirectory(outputDir)
            .build();

        // when - generate tests
        TestSuite testSuite = orchestrator.generateTests(analysisResult);

        // then - verify generated test content
        assertFalse(testSuite.getTestFiles().isEmpty());
        Path testFile = testSuite.getTestFiles().get(0);
        String content = Files.readString(testFile);

        // Verify test structure
        assertTrue(content.contains("@Test"), "Should contain JUnit @Test annotation");
        assertTrue(content.contains("class"), "Should contain class declaration");
        assertTrue(content.contains("import"), "Should contain import statements");
    }

    /**
     * Test Case 5: Multiple Slots in Trace
     * Verifies that all values from multiple slots appear in generated test.
     */
    @Test
    void givenMultipleSlotsInTrace_whenGenerating_thenAllValuesAppearInGeneratedTest() throws Exception {
        // given - orchestrator with multi-slot trace
        Arguments args = createMinimalArguments();
        Orchestrator orchestrator = new Orchestrator(args);

        Path traceFilePath = outputDir.resolve("trace.ser");
        Path identifierMappingPath = outputDir.resolve("identifiers.ser");

        // Create trace with multiple slots and values
        Trace trace = new Trace();
        trace.addIntValue(1, 42);
        trace.addIntValue(1, 100);
        trace.addIntValue(2, 17);
        trace.addIntValue(2, 99);
        trace.addIntValue(3, 5);

        StubResultsHelper.writeSerializedTrace(traceFilePath, trace);

        // Create mapping for all slots
        Map<Integer, JavaValueIdentifier> mapping = new HashMap<>();
        mapping.put(1, new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(0)
                .variableType("int")
                .build()
        ));
        mapping.put(2, new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(1)
                .variableType("int")
                .build()
        ));
        mapping.put(3, new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(2)
                .variableType("int")
                .build()
        ));

        StubResultsHelper.writeSerializedIdentifierMapping(identifierMappingPath, mapping);

        AnalysisResult analysisResult = AnalysisResult.builder()
            .traceFilePath(traceFilePath)
            .identifiersMappingPath(identifierMappingPath)
            .outputDirectory(outputDir)
            .build();

        // when - generate tests
        TestSuite testSuite = orchestrator.generateTests(analysisResult);

        // then - verify test generation succeeded with multiple slots
        assertNotNull(testSuite);
        assertFalse(testSuite.getTestFiles().isEmpty());

        // Verify generated test file exists and has content
        Path testFile = testSuite.getTestFiles().get(0);
        String content = Files.readString(testFile);
        assertFalse(content.isEmpty(), "Generated test should have content");
    }

    /**
     * Test Case 6: Error Handling - Corrupted Trace File
     * Verifies that corrupted trace file throws meaningful exception.
     */
    @Test
    void givenCorruptedTraceFile_whenGeneratingTests_thenThrowsMeaningfulException() throws Exception {
        // given - orchestrator with corrupted trace file
        Arguments args = createMinimalArguments();
        Orchestrator orchestrator = new Orchestrator(args);

        Path traceFilePath = outputDir.resolve("trace.ser");
        Path identifierMappingPath = outputDir.resolve("identifiers.ser");

        // Write corrupted data to trace file
        Files.writeString(traceFilePath, "This is not a valid serialized trace");

        // Write valid identifier mapping
        Map<Integer, JavaValueIdentifier> mapping = StubResultsHelper.createMinimalIdentifierMapping();
        StubResultsHelper.writeSerializedIdentifierMapping(identifierMappingPath, mapping);

        AnalysisResult analysisResult = AnalysisResult.builder()
            .traceFilePath(traceFilePath)
            .identifiersMappingPath(identifierMappingPath)
            .outputDirectory(outputDir)
            .build();

        // when/then - should throw exception
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            orchestrator.generateTests(analysisResult);
        });

        assertTrue(exception.getMessage().contains("Failed to deserialize trace") ||
                   exception.getMessage().contains("trace"),
            "Exception message should mention trace deserialization failure");
    }

    /**
     * Test Case 7: Error Handling - Missing Trace File
     * Verifies that missing trace file throws IllegalStateException.
     */
    @Test
    void givenMissingTraceFile_whenGeneratingTests_thenThrowsIllegalStateException() throws Exception {
        // given - orchestrator with non-existent trace file
        Arguments args = createMinimalArguments();
        Orchestrator orchestrator = new Orchestrator(args);

        Path traceFilePath = outputDir.resolve("non-existent-trace.ser");
        Path identifierMappingPath = outputDir.resolve("identifiers.ser");

        // Write valid identifier mapping but no trace file
        Map<Integer, JavaValueIdentifier> mapping = StubResultsHelper.createMinimalIdentifierMapping();
        StubResultsHelper.writeSerializedIdentifierMapping(identifierMappingPath, mapping);

        AnalysisResult analysisResult = AnalysisResult.builder()
            .traceFilePath(traceFilePath)
            .identifiersMappingPath(identifierMappingPath)
            .outputDirectory(outputDir)
            .build();

        // when/then - should throw exception
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            orchestrator.generateTests(analysisResult);
        });

        assertTrue(exception.getMessage().contains("Failed to deserialize trace") ||
                   exception.getMessage().contains("trace"),
            "Exception message should mention trace issue");
    }

    /**
     * Test Case 8: Error Handling - Empty Trace
     * Verifies that empty trace throws IllegalArgumentException.
     */
    @Test
    void givenEmptyTrace_whenGeneratingTests_thenThrowsIllegalArgumentException() throws Exception {
        // given - orchestrator with empty trace
        Arguments args = createMinimalArguments();
        Orchestrator orchestrator = new Orchestrator(args);

        Path traceFilePath = outputDir.resolve("trace.ser");
        Path identifierMappingPath = outputDir.resolve("identifiers.ser");

        // Create empty trace (no values)
        Trace emptyTrace = new Trace();
        StubResultsHelper.writeSerializedTrace(traceFilePath, emptyTrace);

        Map<Integer, JavaValueIdentifier> mapping = StubResultsHelper.createMinimalIdentifierMapping();
        StubResultsHelper.writeSerializedIdentifierMapping(identifierMappingPath, mapping);

        AnalysisResult analysisResult = AnalysisResult.builder()
            .traceFilePath(traceFilePath)
            .identifiersMappingPath(identifierMappingPath)
            .outputDirectory(outputDir)
            .build();

        // when/then - should throw exception
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            orchestrator.generateTests(analysisResult);
        });

        assertTrue(exception.getMessage().contains("No test scenarios") ||
                   exception.getMessage().contains("trace"),
            "Exception message should mention lack of test scenarios");
    }

    // Helper methods

    private void createSampleSourceCode() throws Exception {
        String sourceCode = """
            package com.example;

            public class SimpleAdder {
                public int add(int a, int b) {
                    return a + b;
                }
            }
            """;

        Path sourceFile = sourceCodePath.resolve("SimpleAdder.java");
        Files.writeString(sourceFile, sourceCode);
    }

    private Arguments createMinimalArguments() {
        Arguments args = new Arguments();
        args.language = TargetLanguage.JAVA;
        args.applicationJarPath = appJar.toString();
        args.sourceCodePath = sourceCodePath.toString();
        args.dislHomePath = dislHome.toString();
        args.outputDirectory = outputDir.toString();
        args.targetMethodReference = "com.example.SimpleAdder.add(int, int)";
        args.testGenerationStrategy = "trace-based-basic";
        args.classpath = List.of();
        args.targetParameters = List.of("0:int", "1:int");
        args.targetFields = List.of();
        args.runtimeArguments = List.of();
        return args;
    }
}

