package cz.cuni.mff.d3s.autodebugger.runner.orchestrator;

import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.MethodIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.ArgumentIdentifierParameters;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaArgumentIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaValueIdentifier;
import cz.cuni.mff.d3s.autodebugger.runner.factories.TestGeneratorFactory;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContext;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerator;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm.LLMBasedTestGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the LLM (ai-assisted) test generation strategy
 * using the mock model to avoid consuming API credits.
 * 
 * These tests exercise the full pipeline:
 * TestGeneratorFactory → LLMBasedTestGenerator → AnthropicClient (mock mode) → Generated Tests
 */
@DisplayName("LLM Strategy End-to-End Tests (Mock Model)")
class LLMStrategyMockEndToEndTest {

    @TempDir
    Path tempDir;

    private Path sourceDir;
    private Path outputDir;
    private Path traceFile;
    private Path identifiersFile;

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = tempDir.resolve("src");
        outputDir = tempDir.resolve("output");
        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);

        // Create sample source file for context
        createSampleSourceCode();

        // Create mock trace
        traceFile = tempDir.resolve("trace.ser");
        createMockTrace();

        // Create identifier mapping
        identifiersFile = tempDir.resolve("identifiers.ser");
        createMockIdentifierMapping();
    }

    @Test
    @DisplayName("Full pipeline: Factory creates LLM generator with mock model and generates tests")
    void givenMockModel_whenFullPipelineExecuted_thenGeneratesTestsWithoutApiCalls() throws Exception {
        // given - create generator using factory with mock model
        JavaRunConfiguration config = JavaRunConfiguration.builder()
                .sourceCodePath(sourceDir)
                .outputDirectory(outputDir)
                .applicationPath(tempDir.resolve("app.jar"))
                .build();
        Files.createFile(config.getApplicationPath());

        // Use the new overload that accepts model name - "mock" triggers mock response
        TestGenerator generator = TestGeneratorFactory.createTestGenerator(
                config, "ai-assisted", null, identifiersFile, "mock");

        // Verify we got the right generator type
        assertInstanceOf(LLMBasedTestGenerator.class, generator);
        assertEquals("ai-assisted", generator.getGenerationTechnique());

        // when - generate tests using the mock model
        Trace trace = deserializeTrace(traceFile);
        TestGenerationContext context = TestGenerationContext.builder()
                .targetMethod(createMockMethodIdentifier())
                .outputDirectory(outputDir)
                .build();

        // LLM generator expects a source file, not directory
        Path sourceFile = sourceDir.resolve("com/example/Calculator.java");
        List<Path> generatedTests = generator.generateTests(trace, sourceFile, context);

        // then - verify tests were generated
        assertNotNull(generatedTests, "Generated tests list should not be null");
        assertFalse(generatedTests.isEmpty(), "Should generate at least one test file");

        // Verify the generated file exists and contains expected mock content
        Path testFile = generatedTests.get(0);
        assertTrue(Files.exists(testFile), "Generated test file should exist");

        String content = Files.readString(testFile);
        assertTrue(content.contains("@Test"), "Generated test should contain @Test annotation");
        assertTrue(content.contains("MockGeneratedTest") || content.contains("class"),
                "Generated test should contain a class definition");
    }

    @Test
    @DisplayName("Mock model returns consistent output for reproducible tests")
    void givenMockModel_whenGeneratingMultipleTimes_thenOutputIsConsistent() throws Exception {
        // given
        JavaRunConfiguration config = JavaRunConfiguration.builder()
                .sourceCodePath(sourceDir)
                .outputDirectory(outputDir)
                .applicationPath(tempDir.resolve("app.jar"))
                .build();
        Files.createFile(config.getApplicationPath());

        TestGenerator generator = TestGeneratorFactory.createTestGenerator(
                config, "ai-assisted", null, identifiersFile, "mock");

        Trace trace = deserializeTrace(traceFile);
        TestGenerationContext context = TestGenerationContext.builder()
                .targetMethod(createMockMethodIdentifier())
                .outputDirectory(outputDir)
                .build();

        // when - generate tests twice
        Path sourceFile = sourceDir.resolve("com/example/Calculator.java");
        List<Path> firstRun = generator.generateTests(trace, sourceFile, context);

        // Create new output dir for second run
        Path outputDir2 = tempDir.resolve("output2");
        Files.createDirectories(outputDir2);
        TestGenerationContext context2 = TestGenerationContext.builder()
                .targetMethod(createMockMethodIdentifier())
                .outputDirectory(outputDir2)
                .build();

        List<Path> secondRun = generator.generateTests(trace, sourceFile, context2);

        // then - both runs should produce files
        assertFalse(firstRun.isEmpty(), "First run should generate tests");
        assertFalse(secondRun.isEmpty(), "Second run should generate tests");

        // Content should be consistent (mock returns same response)
        String content1 = Files.readString(firstRun.get(0));
        String content2 = Files.readString(secondRun.get(0));
        assertEquals(content1, content2, "Mock model should produce consistent output");
    }

    @Test
    @DisplayName("Verify mock model does not require valid API key")
    void givenNoApiKey_whenUsingMockModel_thenSucceeds() throws Exception {
        // given - explicitly pass null API key
        JavaRunConfiguration config = JavaRunConfiguration.builder()
                .sourceCodePath(sourceDir)
                .outputDirectory(outputDir)
                .applicationPath(tempDir.resolve("app.jar"))
                .build();
        Files.createFile(config.getApplicationPath());

        // when - create generator with mock model and no API key
        // This should NOT throw, because mock model doesn't need a real key
        TestGenerator generator = TestGeneratorFactory.createTestGenerator(
                config, "ai-assisted", null, identifiersFile, "mock");

        // then - generator should be created successfully
        assertNotNull(generator);
        assertInstanceOf(LLMBasedTestGenerator.class, generator);
    }

    private void createSampleSourceCode() throws Exception {
        Path packageDir = sourceDir.resolve("com/example");
        Files.createDirectories(packageDir);

        String sourceCode = """
            package com.example;

            public class Calculator {
                public int add(int a, int b) {
                    return a + b;
                }
            }
            """;
        Files.writeString(packageDir.resolve("Calculator.java"), sourceCode);
    }

    private void createMockTrace() throws Exception {
        Trace trace = new Trace();
        trace.addIntValue(0, 5);
        trace.addIntValue(0, 10);
        trace.addIntValue(1, 3);
        trace.addIntValue(1, 7);

        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(traceFile))) {
            oos.writeObject(trace);
        }
    }

    private void createMockIdentifierMapping() throws Exception {
        Map<Integer, JavaValueIdentifier> mapping = new HashMap<>();
        mapping.put(0, new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("int")
                        .build()));
        mapping.put(1, new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(1)
                        .variableType("int")
                        .build()));

        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(identifiersFile))) {
            oos.writeObject(mapping);
        }
    }

    @SuppressWarnings("unchecked")
    private Trace deserializeTrace(Path path) throws Exception {
        try (var ois = new java.io.ObjectInputStream(Files.newInputStream(path))) {
            return (Trace) ois.readObject();
        }
    }

    private MethodIdentifier createMockMethodIdentifier() {
        return new MethodIdentifier("add", "int", List.of("int", "int")) {
            @Override
            public String getClassName() {
                return "Calculator";
            }

            @Override
            public String getPackageName() {
                return "com.example";
            }

            @Override
            public String getFullyQualifiedClassName() {
                return "com.example.Calculator";
            }

            @Override
            public String getFullyQualifiedSignature() {
                return "com.example.Calculator.add(int, int)";
            }
        };
    }
}

