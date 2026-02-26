package cz.cuni.mff.d3s.autodebugger.runner.args;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Arguments validation logic.
 */
class ArgumentsValidationTest {

    @TempDir
    Path tempDir;

    private Path validJar;
    private Path validSourceDir;

    @BeforeEach
    void setUp() throws IOException {
        // Create a valid JAR file and source directory
        validJar = tempDir.resolve("test.jar");
        Files.createFile(validJar);

        validSourceDir = tempDir.resolve("src");
        Files.createDirectory(validSourceDir);
    }

    @Test
    void givenValidArguments_whenValidate_thenNoErrors() {
        Arguments args = new Arguments();
        args.applicationJarPath = validJar.toString();
        args.sourceCodePath = validSourceDir.toString();
        args.targetMethodReference = "Calculator.add(int,int)";
        args.targetParameters = List.of("0:int", "1:int");
        args.testGenerationStrategy = "trace-based-basic";

        List<String> errors = args.validate();

        assertTrue(errors.isEmpty(), "Expected no validation errors but got: " + errors);
    }

    @Test
    void givenMissingJar_whenValidate_thenReturnsError() {
        Arguments args = new Arguments();
        args.applicationJarPath = "/nonexistent/path/app.jar";
        args.sourceCodePath = validSourceDir.toString();
        args.targetMethodReference = "Calculator.add(int,int)";
        args.targetParameters = List.of("0:int");

        List<String> errors = args.validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("Application path not found")));
    }

    @Test
    void givenMissingSourcePath_whenValidate_thenReturnsError() {
        Arguments args = new Arguments();
        args.applicationJarPath = validJar.toString();
        args.sourceCodePath = "/nonexistent/path/src";
        args.targetMethodReference = "Calculator.add(int,int)";
        args.targetParameters = List.of("0:int");

        List<String> errors = args.validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("Source code path not found")));
    }

    @Test
    void givenInvalidMethodReference_whenValidate_thenReturnsError() {
        Arguments args = new Arguments();
        args.applicationJarPath = validJar.toString();
        args.sourceCodePath = validSourceDir.toString();
        args.targetMethodReference = "invalidMethodRef"; // Missing . and ()
        args.targetParameters = List.of("0:int");

        List<String> errors = args.validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("Invalid method reference format")));
    }

    @Test
    void givenUnknownStrategy_whenValidate_thenReturnsError() {
        Arguments args = new Arguments();
        args.applicationJarPath = validJar.toString();
        args.sourceCodePath = validSourceDir.toString();
        args.targetMethodReference = "Calculator.add(int,int)";
        args.targetParameters = List.of("0:int");
        args.testGenerationStrategy = "unknown-strategy";

        List<String> errors = args.validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("Unknown test generation strategy")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Available strategies")));
    }

    @Test
    void givenAiStrategyWithoutApiKey_whenValidate_thenReturnsError() {
        Arguments args = new Arguments();
        args.applicationJarPath = validJar.toString();
        args.sourceCodePath = validSourceDir.toString();
        args.targetMethodReference = "Calculator.add(int,int)";
        args.targetParameters = List.of("0:int");
        args.testGenerationStrategy = "ai-assisted";
        args.apiKey = null;

        List<String> errors = args.validate();

        // Only check if ANTHROPIC_API_KEY env var is not set
        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            assertTrue(errors.stream().anyMatch(e -> e.contains("API key")));
        }
    }

    @Test
    void givenInvalidTraceMode_whenValidate_thenReturnsError() {
        Arguments args = new Arguments();
        args.applicationJarPath = validJar.toString();
        args.sourceCodePath = validSourceDir.toString();
        args.targetMethodReference = "Calculator.add(int,int)";
        args.targetParameters = List.of("0:int");
        args.traceMode = "invalid-mode";

        List<String> errors = args.validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("Invalid trace mode")));
    }

    @Test
    void givenInvalidParameterFormat_whenValidate_thenReturnsError() {
        Arguments args = new Arguments();
        args.applicationJarPath = validJar.toString();
        args.sourceCodePath = validSourceDir.toString();
        args.targetMethodReference = "Calculator.add(int,int)";
        args.targetParameters = List.of("invalid_format"); // Missing :

        List<String> errors = args.validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("Invalid parameter format")));
    }

    @Test
    void givenNoValuesToCapture_whenValidate_thenReturnsError() {
        Arguments args = new Arguments();
        args.applicationJarPath = validJar.toString();
        args.sourceCodePath = validSourceDir.toString();
        args.targetMethodReference = "Calculator.add(int,int)";
        // No parameters or fields specified

        List<String> errors = args.validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("No values to capture")));
    }

    @Test
    void givenMultipleErrors_whenValidate_thenReturnsAllErrors() {
        Arguments args = new Arguments();
        args.applicationJarPath = "/nonexistent/app.jar";
        args.sourceCodePath = "/nonexistent/src";
        args.targetMethodReference = "invalid";
        args.testGenerationStrategy = "unknown";
        // No parameters

        List<String> errors = args.validate();

        assertTrue(errors.size() >= 4, "Expected at least 4 errors but got: " + errors.size());
    }

    @Test
    void givenValidationErrors_whenValidateOrThrow_thenThrowsException() {
        Arguments args = new Arguments();
        args.applicationJarPath = "/nonexistent/app.jar";
        args.sourceCodePath = "/nonexistent/src";
        args.targetMethodReference = "Calculator.add()";
        args.targetParameters = List.of("0:int");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, args::validateOrThrow);
        assertTrue(ex.getMessage().contains("Invalid arguments"));
        assertTrue(ex.getMessage().contains("Application path not found"));
    }
}

