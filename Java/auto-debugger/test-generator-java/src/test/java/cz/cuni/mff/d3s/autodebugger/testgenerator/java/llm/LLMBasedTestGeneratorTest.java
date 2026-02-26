package cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm;

import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.AnthropicClient;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.LLMConfiguration;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContext;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMConfigurationException;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm.exceptions.TestGenerationWorkflowException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LLMBasedTestGeneratorTest {

    private LLMBasedTestGenerator generator;
    private Trace trace;
    private TestGenerationContext context;

    @Mock
    private AnthropicClient mockAnthropicClient;

    @TempDir
    Path tempDir;

    private Path mockSourceFile;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // Create dependencies for LLMBasedTestGenerator with mocked AnthropicClient
        PromptBuilder promptBuilder = new PromptBuilder();
        CodeValidator codeValidator = new CodeValidator();

        generator = new LLMBasedTestGenerator(mockAnthropicClient, promptBuilder, codeValidator);

        // Configure with mock model for testing
        LLMConfiguration mockConfig = LLMConfiguration.builder()
                .modelName("mock")
                .build();

        // Configure the generator (which will configure the client internally)
        generator.configure(mockConfig);

        // Create a simple trace for testing
        trace = new Trace();
        trace.addIntValue(0, 10);
        trace.addIntValue(1, 5);

        // Create a mock source file for testing
        mockSourceFile = tempDir.resolve("Calculator.java");
        Files.writeString(mockSourceFile, """
            package com.example;

            public class Calculator {
                public int add(int a, int b) {
                    return a + b;
                }
            }
            """);

        // Create structured identifiers for Calculator.add method
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

        JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
                .applicationPath(Path.of("test-app.jar"))
                .sourceCodePath(Path.of("src/main/java"))
                .targetMethod(methodIdentifier)
                .outputDirectory(tempDir)
                .build();

        // Create context from RunConfiguration for backward compatibility
        // TODO: Update tests to use RunConfiguration directly when LLM generator supports it
        context = (TestGenerationContext) runConfiguration.createTestGenerationContext();
    }

    @Test
    void givenValidConfiguration_whenConfiguring_thenSucceeds() {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey("test-key")
                .maxTokens(4000)
                .temperature(0.3)
                .build();

        assertDoesNotThrow(() -> generator.configure(config));
    }

    @Test
    void givenNullConfiguration_whenConfiguring_thenThrowsException() {
        assertThrows(Exception.class, () -> generator.configure((LLMConfiguration) null));
    }

    @Test
    void givenUnconfiguredGenerator_whenGeneratingTests_thenThrowsException() {
        // Create a new unconfigured generator
        AnthropicClient unconfiguredClient = new AnthropicClient();
        PromptBuilder promptBuilder = new PromptBuilder();
        CodeValidator codeValidator = new CodeValidator();
        LLMBasedTestGenerator unconfiguredGenerator = new LLMBasedTestGenerator(unconfiguredClient, promptBuilder, codeValidator);

        // Should throw exception if not configured
        assertThrows(RuntimeException.class, () ->
            unconfiguredGenerator.generateTests(trace, mockSourceFile, context));
    }

    @Test
    void givenMockConfiguration_whenGeneratingTests_thenCreatesValidTestFile() throws Exception {
        // Mock the AnthropicClient to return a valid test class
        String mockGeneratedTest = """
                package com.example.tests;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class CalculatorTest {
                    @Test
                    void testAdd() {
                        Calculator calculator = new Calculator();
                        int result = calculator.add(10, 5);
                        assertEquals(15, result);
                    }
                }
                """;

        when(mockAnthropicClient.generateCode(anyString())).thenReturn(mockGeneratedTest);

        // Generate tests
        List<Path> result = generator.generateTests(trace, mockSourceFile, context);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Should generate at least one test file");

        // Verify the AnthropicClient was called
        verify(mockAnthropicClient, atLeastOnce()).generateCode(anyString());

        // Verify the generated file exists and contains expected content
        Path generatedFile = result.getFirst();
        assertTrue(Files.exists(generatedFile), "Generated test file should exist");

        String fileContent = Files.readString(generatedFile);
        assertTrue(fileContent.contains("CalculatorTest"), "Should contain test class name");
        assertTrue(fileContent.contains("@Test"), "Should contain JUnit test annotation");
    }

    @Test
    void givenNullTrace_whenGeneratingTests_thenThrowsException() {
        // Generator is already configured in setUp
        assertThrows(TestGenerationWorkflowException.class, () ->
            generator.generateTests((Trace) null, mockSourceFile, context));
    }

    @Test
    void givenNullSourcePath_whenGeneratingTests_thenThrowsException() {
        // Generator is already configured in setUp
        assertThrows(RuntimeException.class, () ->
            generator.generateTests(trace, null, context));
    }

    @Test
    void givenDirectoryAsSourcePath_whenGeneratingTests_thenThrowsException() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("mock")
                .build();

        generator.configure(config);

        // Should throw exception when passing a directory instead of a file
        assertThrows(RuntimeException.class, () ->
            generator.generateTests(trace, tempDir, context));
    }

    @Test
    void givenNullContext_whenGeneratingTests_thenThrowsException() throws Exception {
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("mock")
                .build();

        generator.configure(config);

        assertThrows(RuntimeException.class, () ->
            generator.generateTests(trace, mockSourceFile, (TestGenerationContext) null));
    }

    @Test
    void givenDefaultConfiguration_whenConfiguring_thenUsesCorrectDefaults() {
        // Test that default configuration values are set correctly
        LLMConfiguration config = LLMConfiguration.builder()
                .apiKey("test-key")
                .build();

        assertEquals("claude-sonnet-4-5-20250929", config.getModelName());

        assertDoesNotThrow(() -> generator.configure(config));
    }

    @Test
    void givenTraceAndSourceCode_whenGeneratingTests_thenPromptContainsAllRequiredElements() throws Exception {
        // Create a new generator with a non-mock configuration to test prompt generation
        LLMConfiguration realConfig = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey("test-key")
                .build();

        // Configure the generator with a real model (not mock) so it calls the client
        generator.configure(realConfig);

        // Create a more detailed trace with multiple data points
        Trace detailedTrace = new Trace();
        detailedTrace.addIntValue(0, 10);
        detailedTrace.addIntValue(0, 20);
        detailedTrace.addIntValue(1, 5);
        detailedTrace.addIntValue(1, 7);

        // Create source file with Calculator class content
        String calculatorSource = """
                package com.example.math;

                public class Calculator {
                    private int executionCount = 0;

                    public int add(int a, int b) {
                        this.executionCount++;
                        return a + b;
                    }
                }
                """;

        Path sourceFile = tempDir.resolve("Calculator.java");
        Files.writeString(sourceFile, calculatorSource);

        // Mock the client to capture the prompt and return valid test code
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        String mockTestCode = """
                package com.example.tests;
                import org.junit.jupiter.api.Test;
                public class CalculatorTest {
                    @Test void testAdd() { }
                }
                """;
        when(mockAnthropicClient.generateCode(promptCaptor.capture())).thenReturn(mockTestCode);

        // Generate tests
        generator.generateTests(detailedTrace, sourceFile, context);

        // Verify the client was called and capture the prompt
        verify(mockAnthropicClient, atLeastOnce()).generateCode(anyString());
        String capturedPrompt = promptCaptor.getValue();
        assertNotNull(capturedPrompt, "Prompt should not be null");

        // Verify task definition - should contain test generation instruction
        assertTrue(capturedPrompt.contains("Generate") &&
                  (capturedPrompt.contains("JUnit") || capturedPrompt.contains("test")),
                  "Prompt should contain test generation task");

        // Verify requirements section
        assertTrue(capturedPrompt.contains("Requirements") || capturedPrompt.contains("requirements"),
                  "Prompt should contain requirements section");

        // Verify static context (source code)
        assertTrue(capturedPrompt.contains("Calculator"),
                  "Prompt should contain the Calculator class from source");
        assertTrue(capturedPrompt.contains("add"),
                  "Prompt should contain the target method");

        // Verify source code section
        assertTrue(capturedPrompt.contains("Source Code") || capturedPrompt.contains("```java"),
                  "Prompt should contain source code section");

        // Verify dynamic context (trace data)
        assertTrue(capturedPrompt.contains("10") && capturedPrompt.contains("5"),
                  "Prompt should contain trace values");

        // Verify instruction to return only code
        assertTrue(capturedPrompt.contains("only") && capturedPrompt.contains("Java"),
                  "Prompt should contain instruction to return only Java code");
    }

    @Test
    void givenAnthropicModels_whenConfiguring_thenAllModelsAreSupported() {
        // Test Anthropic-specific model configurations
        String[] claudeModels = {
            "claude-sonnet-4-5-20250929",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-20250219",
            "claude-3-5-haiku-20241022",
            "claude-3-haiku-20240307"
        };

        for (String model : claudeModels) {
            LLMConfiguration config = LLMConfiguration.builder()
                    .modelName(model)
                    .apiKey("test-key")
                    .maxTokens(4000)
                    .temperature(0.3)
                    .build();

            assertEquals(model, config.getModelName());
            assertFalse(config.isMockModel(), "Claude models should not be mock models");
            assertTrue(config.requiresApiKey(), "Claude models should require API key");
            assertEquals("test-key", config.getEffectiveApiKey());

            // Should not throw exception when configuring generator
            assertDoesNotThrow(() -> generator.configure(config));
        }
    }

    @Test
    void givenEnvironmentVariableApiKey_whenValidating_thenHandlesEnvironmentVariable() {
        // Test configuration without explicit API key (relies on environment variable)
        LLMConfiguration config = LLMConfiguration.builder()
                .modelName("claude-sonnet-4-20250514")
                .apiKey(null) // No explicit API key
                .build();

        // If ANTHROPIC_API_KEY is set, validation should pass
        // If not set, validation should fail
        String envApiKey = System.getenv("ANTHROPIC_API_KEY");
        if (envApiKey != null && !envApiKey.trim().isEmpty()) {
            assertDoesNotThrow(config::validate,
                "Configuration should be valid when ANTHROPIC_API_KEY environment variable is set");
            assertEquals(envApiKey, config.getEffectiveApiKey());
        } else {
            assertThrows(LLMConfigurationException.class, config::validate,
                "Configuration should be invalid when no API key is provided and ANTHROPIC_API_KEY is not set");
            assertNull(config.getEffectiveApiKey());
        }
    }

    @Test
    void givenMockModel_whenConfiguring_thenHandlesSpecialMockBehavior() {
        // Test that mock model doesn't require API key and has special behavior
        LLMConfiguration mockConfig = LLMConfiguration.builder()
                .modelName("mock")
                .apiKey(null) // No API key needed for mock
                .build();

        assertTrue(mockConfig.isMockModel(), "Should identify as mock model");
        assertFalse(mockConfig.requiresApiKey(), "Mock model should not require API key");
        assertNull(mockConfig.getEffectiveApiKey(), "Mock model should return null for effective API key");
        assertDoesNotThrow(mockConfig::validate, "Mock configuration should always be valid");

        // Should be able to configure generator with mock model
        assertDoesNotThrow(() -> generator.configure(mockConfig));
    }
}
