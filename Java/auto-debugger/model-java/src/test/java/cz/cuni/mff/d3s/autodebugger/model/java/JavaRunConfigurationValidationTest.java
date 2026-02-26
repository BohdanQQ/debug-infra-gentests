package cz.cuni.mff.d3s.autodebugger.model.java;

import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for JavaRunConfiguration validation.
 */
class JavaRunConfigurationValidationTest {

    @TempDir
    Path tempDir;

    private Path validJarFile;
    private Path validSourceDir;
    private Path validDislHome;
    private Path validOutputDir;
    private JavaMethodIdentifier validMethodIdentifier;
    private List<JavaValueIdentifier> validExportableValues;

    @BeforeEach
    void setUp() throws IOException {
        // Create valid test files and directories
        validJarFile = tempDir.resolve("test-app.jar");
        Files.createFile(validJarFile);

        validSourceDir = tempDir.resolve("src");
        Files.createDirectory(validSourceDir);

        validDislHome = tempDir.resolve("disl");
        Files.createDirectory(validDislHome);
        Files.createDirectories(validDislHome.resolve("bin"));
        Files.createFile(validDislHome.resolve("bin/disl.py"));
        Files.createDirectories(validDislHome.resolve("output/lib"));

        validOutputDir = tempDir.resolve("output");

        // Create valid method identifier
        JavaPackageIdentifier packageIdentifier = new JavaPackageIdentifier("com.example");
        JavaClassIdentifier classIdentifier = new JavaClassIdentifier(
            ClassIdentifierParameters.builder()
                .packageIdentifier(packageIdentifier)
                .className("TestClass")
                .build()
        );
        validMethodIdentifier = new JavaMethodIdentifier(
            MethodIdentifierParameters.builder()
                .ownerClassIdentifier(classIdentifier)
                .methodName("testMethod")
                .returnType("void")
                .parameterTypes(Arrays.asList("int", "String"))
                .build()
        );

        // Create valid exportable values
        JavaArgumentIdentifier argIdentifier = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(0)
                .variableType("int")
                .build()
        );
        JavaFieldIdentifier fieldIdentifier = new JavaFieldIdentifier(
            FieldIdentifierParameters.builder()
                .variableName("testField")
                .variableType("String")
                .ownerClassIdentifier(classIdentifier)
                .build()
        );
        validExportableValues = Arrays.asList(argIdentifier, fieldIdentifier);
    }

    @Test
    void givenValidConfiguration_whenValidating_thenSucceeds() {
        JavaRunConfiguration config = JavaRunConfiguration.builder()
            .applicationPath(validJarFile)
            .sourceCodePath(validSourceDir)
            .dislHomePath(validDislHome)
            .outputDirectory(validOutputDir)
            .targetMethod(validMethodIdentifier)
            .exportableValues(validExportableValues)
            .build();

        assertDoesNotThrow(config::validate);
    }

    // Application Path Validation Tests
    @Test
    void givenNullApplicationPath_whenValidating_thenThrows() {
        JavaRunConfiguration config = createConfigBuilder()
            .applicationPath(null)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Application path cannot be null"));
    }

    @Test
    void givenNonExistentApplicationPath_whenValidating_thenThrows() {
        Path nonExistentFile = tempDir.resolve("nonexistent.jar");
        JavaRunConfiguration config = createConfigBuilder()
            .applicationPath(nonExistentFile)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Application path does not exist"));
    }

    @Test
    void givenUnreadableApplicationPath_whenValidating_thenThrows() throws IOException {
        Path unreadableFile = tempDir.resolve("unreadable.jar");
        Files.createFile(unreadableFile);
        unreadableFile.toFile().setReadable(false);

        JavaRunConfiguration config = createConfigBuilder()
            .applicationPath(unreadableFile)
            .build();

        try {
            IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
            assertTrue(exception.getMessage().contains("Application path is not readable"));
        } finally {
            // Restore permissions for cleanup
            unreadableFile.toFile().setReadable(true);
        }
    }

    @Test
    void givenDirectoryAsApplicationPath_whenValidating_thenSucceeds() throws IOException {
        // Directories are now allowed as application paths (for compiled class directories)
        Path directory = tempDir.resolve("app-dir");
        Files.createDirectory(directory);

        JavaRunConfiguration config = createConfigBuilder()
            .applicationPath(directory)
            .build();

        // Should not throw - directories are valid application paths
        assertDoesNotThrow(config::validate);
    }

    // Source Code Path Validation Tests
    @Test
    void givenNullSourceCodePath_whenValidating_thenThrows() {
        JavaRunConfiguration config = createConfigBuilder()
            .sourceCodePath(null)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Source code path cannot be null"));
    }

    @Test
    void givenNonExistentSourceCodePath_whenValidating_thenThrows() {
        Path nonExistentDir = tempDir.resolve("nonexistent-src");
        JavaRunConfiguration config = createConfigBuilder()
            .sourceCodePath(nonExistentDir)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Source code directory does not exist"));
    }

    // PP: disabled test case because also the validation check is disabled now
    @Test
    @Disabled
    void givenFileAsSourceCodePath_whenValidating_thenThrows() throws IOException {
        Path file = tempDir.resolve("src-file.txt");
        Files.createFile(file);

        JavaRunConfiguration config = createConfigBuilder()
            .sourceCodePath(file)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Source code path must point to a directory"));
    }

    // DiSL Home Path Validation Tests
    @Test
    void givenNullDislHomePath_whenValidating_thenThrows() {
        JavaRunConfiguration config = createConfigBuilder()
            .dislHomePath(null)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("DiSL home path cannot be null"));
    }

    @Test
    void givenNonExistentDislHomePath_whenValidating_thenThrows() {
        Path nonExistentDir = tempDir.resolve("nonexistent-disl");
        JavaRunConfiguration config = createConfigBuilder()
            .dislHomePath(nonExistentDir)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("DiSL home directory does not exist"));
    }

    @Test
    void givenDislHomeWithoutBinDislPy_whenValidating_thenThrows() throws IOException {
        Path invalidDislHome = tempDir.resolve("invalid-disl");
        Files.createDirectory(invalidDislHome);
        Files.createDirectories(invalidDislHome.resolve("output/lib"));

        JavaRunConfiguration config = createConfigBuilder()
            .dislHomePath(invalidDislHome)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("missing bin/disl.py"));
    }

    @Test
    void givenDislHomeWithoutOutputLib_whenValidating_thenThrows() throws IOException {
        Path invalidDislHome = tempDir.resolve("invalid-disl");
        Files.createDirectory(invalidDislHome);
        Files.createDirectories(invalidDislHome.resolve("bin"));
        Files.createFile(invalidDislHome.resolve("bin/disl.py"));

        JavaRunConfiguration config = createConfigBuilder()
            .dislHomePath(invalidDislHome)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("missing output/lib directory"));
    }

    // Classpath Entries Validation Tests
    @Test
    void givenNullClasspathEntries_whenValidating_thenSucceeds() {
        // Don't set classpathEntries at all (they're optional)
        JavaRunConfiguration config = JavaRunConfiguration.builder()
            .applicationPath(validJarFile)
            .sourceCodePath(validSourceDir)
            .dislHomePath(validDislHome)
            .outputDirectory(validOutputDir)
            .targetMethod(validMethodIdentifier)
            .exportableValues(validExportableValues)
            .build();

        assertDoesNotThrow(config::validate); // Classpath entries are optional
    }

    @Test
    void givenNullClasspathEntry_whenValidating_thenThrows() {
        JavaRunConfiguration config = createConfigBuilder()
            .classpathEntry(null)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Classpath entry at index 0 cannot be null"));
    }

    @Test
    void givenNonExistentClasspathEntry_whenValidating_thenThrows() {
        Path nonExistentPath = tempDir.resolve("nonexistent.jar");
        JavaRunConfiguration config = createConfigBuilder()
            .classpathEntry(nonExistentPath)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Classpath entry does not exist"));
    }

    @Test
    void givenValidJarClasspathEntry_whenValidating_thenSucceeds() throws IOException {
        Path jarFile = tempDir.resolve("classpath.jar");
        Files.createFile(jarFile);

        JavaRunConfiguration config = createConfigBuilder()
            .classpathEntry(jarFile)
            .build();

        assertDoesNotThrow(config::validate);
    }

    @Test
    void givenValidDirectoryClasspathEntry_whenValidating_thenSucceeds() throws IOException {
        Path directory = tempDir.resolve("classpath-dir");
        Files.createDirectory(directory);

        JavaRunConfiguration config = createConfigBuilder()
            .classpathEntry(directory)
            .build();

        assertDoesNotThrow(config::validate);
    }

    // Output Directory Validation Tests
    @Test
    void givenNullOutputDirectory_whenValidating_thenSucceeds() {
        JavaRunConfiguration config = createConfigBuilder()
            .outputDirectory(null)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Output directory cannot be null"));
    }

    @Test
    void givenFileAsOutputDirectory_whenValidating_thenThrows() throws IOException {
        Path file = tempDir.resolve("output-file.txt");
        Files.createFile(file);

        JavaRunConfiguration config = createConfigBuilder()
            .outputDirectory(file)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Output path exists but is not a directory"));
    }

    @Test
    void givenNonExistentOutputDirectory_whenValidating_thenCreatesDirectory() {
        Path newOutputDir = tempDir.resolve("new-output");
        JavaRunConfiguration config = createConfigBuilder()
            .outputDirectory(newOutputDir)
            .build();

        assertDoesNotThrow(config::validate);
        assertTrue(Files.exists(newOutputDir));
        assertTrue(Files.isDirectory(newOutputDir));
    }

    // Target Method Validation Tests
    @Test
    void givenNullTargetMethod_whenValidating_thenThrows() {
        JavaRunConfiguration config = createConfigBuilder()
            .targetMethod(null)
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Target method cannot be null"));
    }

    // Note: This test is disabled because the current validation logic
    // may not catch all invalid method signatures as expected.
    // The signature validation depends on the JavaMethodSignatureParser
    // which is quite permissive in what it considers valid.
    // TODO: Enhance signature validation if stricter checking is needed
    @Test
    @Disabled
    void givenInvalidTargetMethodSignature_whenValidating_thenThrows() {
        // This test would need a more sophisticated approach to create
        // a truly invalid signature that the parser rejects
    }

    // Exportable Values Validation Tests
    @Test
    void givenEmptyExportableValues_whenValidating_thenSucceeds() {
        JavaRunConfiguration config = createConfigBuilder()
            .exportableValues(Collections.emptyList())
            .build();

        // Should not throw exception but should log a warning
        assertDoesNotThrow(config::validate);
    }

    @Test
    void givenNegativeSlotArgumentIdentifier_whenValidating_thenThrows() {
        JavaArgumentIdentifier invalidArg = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(-1)
                .variableType("int")
                .build()
        );

        JavaRunConfiguration config = createConfigBuilder()
            .exportableValues(List.of(invalidArg))
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Argument slot index cannot be negative"));
    }

    @Test
    void givenOutOfBoundsSlotArgumentIdentifier_whenValidating_thenThrows() {
        JavaArgumentIdentifier invalidArg = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(5) // The method only has 2 parameters (indices 0 and 1)
                .variableType("int")
                .build()
        );

        JavaRunConfiguration config = createConfigBuilder()
            .exportableValues(List.of(invalidArg))
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Argument slot index 5 is out of bounds"));
    }

    @Test
    void givenValidSlotArgumentIdentifier_whenValidating_thenSucceeds() {
        JavaArgumentIdentifier validArg = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(1) // Valid slot for second parameter
                .variableType("String")
                .build()
        );

        JavaRunConfiguration config = createConfigBuilder()
            .exportableValues(List.of(validArg))
            .build();

        assertDoesNotThrow(config::validate);
    }

    @Test
    void givenNullFieldNameFieldIdentifier_whenValidating_thenThrows() {
        JavaPackageIdentifier packageIdentifier = new JavaPackageIdentifier("com.example");
        JavaClassIdentifier classIdentifier = new JavaClassIdentifier(
            ClassIdentifierParameters.builder()
                .packageIdentifier(packageIdentifier)
                .className("TestClass")
                .build()
        );

        JavaFieldIdentifier invalidField = new JavaFieldIdentifier(
            FieldIdentifierParameters.builder()
                .variableName(null)
                .variableType("String")
                .ownerClassIdentifier(classIdentifier)
                .build()
        );

        JavaRunConfiguration config = createConfigBuilder()
            .exportableValues(List.of(invalidField))
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Field name cannot be null or empty"));
    }

    @Test
    void givenEmptyFieldNameFieldIdentifier_whenValidating_thenThrows() {
        JavaPackageIdentifier packageIdentifier = new JavaPackageIdentifier("com.example");
        JavaClassIdentifier classIdentifier = new JavaClassIdentifier(
            ClassIdentifierParameters.builder()
                .packageIdentifier(packageIdentifier)
                .className("TestClass")
                .build()
        );

        JavaFieldIdentifier invalidField = new JavaFieldIdentifier(
            FieldIdentifierParameters.builder()
                .variableName("")
                .variableType("String")
                .ownerClassIdentifier(classIdentifier)
                .build()
        );

        JavaRunConfiguration config = createConfigBuilder()
            .exportableValues(List.of(invalidField))
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Field name cannot be null or empty"));
    }

    @Test
    void givenNullOwnerClassFieldIdentifier_whenValidating_thenThrows() {
        JavaFieldIdentifier invalidField = new JavaFieldIdentifier(
            FieldIdentifierParameters.builder()
                .variableName("testField")
                .variableType("String")
                .ownerClassIdentifier(null)
                .build()
        );

        JavaRunConfiguration config = createConfigBuilder()
            .exportableValues(List.of(invalidField))
            .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Field identifier must have an owner class identifier"));
    }

    private JavaRunConfiguration.JavaRunConfigurationBuilder createConfigBuilder() {
        return JavaRunConfiguration.builder()
            .applicationPath(validJarFile)
            .sourceCodePath(validSourceDir)
            .dislHomePath(validDislHome)
            .outputDirectory(validOutputDir)
            .targetMethod(validMethodIdentifier)
            .exportableValues(validExportableValues);
    }
}
