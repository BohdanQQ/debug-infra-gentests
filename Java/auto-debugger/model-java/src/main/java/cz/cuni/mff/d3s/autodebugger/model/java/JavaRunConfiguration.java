package cz.cuni.mff.d3s.autodebugger.model.java;

import cz.cuni.mff.d3s.autodebugger.model.common.RunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.common.TargetLanguage;
import cz.cuni.mff.d3s.autodebugger.model.common.TempPathResolver;
import cz.cuni.mff.d3s.autodebugger.model.common.TraceMode;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaArgumentIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaFieldIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaMethodIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaValueIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.parsing.JavaMethodSignatureParser;
import cz.cuni.mff.d3s.autodebugger.model.java.parsing.MethodSignature;
import cz.cuni.mff.d3s.autodebugger.model.java.parsing.SignatureState;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;

/**
 * Java-specific implementation of RunConfiguration.
 * Contains all the necessary information for instrumenting, analyzing, and generating tests
 * for Java applications.
 */
@Builder
@Getter
@Slf4j
public class JavaRunConfiguration implements RunConfiguration {

    private final Path applicationPath;
    private final Path sourceCodePath;
    private final JavaMethodIdentifier targetMethod;
    
    @Singular
    private final List<JavaValueIdentifier> exportableValues;
    
    @Singular("classpathEntry")
    private final List<Path> classpathEntries;

    @Singular("runtimeArgument")
    public final List<String> runtimeArguments;

    @Builder.Default
    private final Path outputDirectory = TempPathResolver.getDefaultOutputDirectory();


    @Builder.Default
    private final TargetLanguage language = TargetLanguage.JAVA;

    @Builder.Default
    private final TraceMode traceMode = TraceMode.NAIVE;

    @Builder.Default
    private final String testGenerationStrategy = "trace-based-basic";

    /**
     * Maximum number of argument value combinations to generate in tests.
     * Default: null (uses TestGenerationContext default of Integer.MAX_VALUE - no limit).
     * When set, limits the number of test scenarios generated from argument values.
     */
    private final Integer maxArgumentCombinations;

    // No default - must be explicitly set via CLI argument or DISL_HOME env var
    private final Path dislHomePath;

    @Override
    public void validate() {
        log.debug("Validating Java run configuration");

        // Path and File System Validation
        validateApplicationPath();
        validateSourceCodePath();
        validateDislHomePath();
        validateClasspathEntries();
        validateOutputDirectory();

        // Method and Value Targeting Validation
        validateTargetMethod();
        validateExportableValues();

        log.debug("Java run configuration validation completed successfully");
    }

    /**
     * Creates a TestGenerationContext from this JavaRunConfiguration using default settings.
     * Uses the JavaTestGenerationContextFactory to leverage Java-specific identifier methods.
     *
     * @return A TestGenerationContext with default settings
     * @throws UnsupportedOperationException if the test-generator-java module is not available
     */
    @Override
    public Object createTestGenerationContext() {
        try {
            // Use reflection to avoid compile-time dependency on test-generator-java
            Class<?> factoryClass = Class.forName("cz.cuni.mff.d3s.autodebugger.testgenerator.java.JavaTestGenerationContextFactory");
            java.lang.reflect.Method createMethod = factoryClass.getMethod("createFromJavaRunConfiguration", JavaRunConfiguration.class);
            return createMethod.invoke(null, this);
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException(
                "JavaTestGenerationContextFactory not found. Ensure test-generator-java module is on the classpath.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create TestGenerationContext", e);
        }
    }

    /**
     * Creates a TestGenerationContext from this JavaRunConfiguration with custom settings.
     * Uses the JavaTestGenerationContextFactory to leverage Java-specific identifier methods.
     *
     * @param settings TestGenerationSettings for customizing test generation behavior
     * @return A TestGenerationContext with the specified settings
     * @throws UnsupportedOperationException if the test-generator-java module is not available
     */
    @Override
    public Object createTestGenerationContext(Object settings) {
        try {
            // Use reflection to avoid compile-time dependency on test-generator-java
            Class<?> factoryClass = Class.forName("cz.cuni.mff.d3s.autodebugger.testgenerator.java.JavaTestGenerationContextFactory");
            Class<?> settingsClass = Class.forName("cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationSettings");
            java.lang.reflect.Method createMethod = factoryClass.getMethod("createFromJavaRunConfiguration",
                    JavaRunConfiguration.class, settingsClass);
            return createMethod.invoke(null, this, settings);
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException(
                "JavaTestGenerationContextFactory or TestGenerationSettings not found. " +
                "Ensure test-generator-java and test-generator-common modules are on the classpath.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create TestGenerationContext with custom settings", e);
        }
    }

    /**
     * Validates the application path is an existing, readable file or directory.
     * For JAR files, issues a warning if the file does not have a .jar extension.
     * For directories, assumes it contains compiled class files.
     */
    private void validateApplicationPath() {
        if (applicationPath == null) {
            throw new IllegalStateException("Application path cannot be null");
        }

        if (!Files.exists(applicationPath)) {
            throw new IllegalStateException("Application path does not exist: " + applicationPath);
        }

        if (!Files.isReadable(applicationPath)) {
            throw new IllegalStateException("Application path is not readable: " + applicationPath);
        }

        // Allow both regular files (JARs) and directories (compiled classes)
        if (!Files.isRegularFile(applicationPath) && !Files.isDirectory(applicationPath)) {
            throw new IllegalStateException("Application path must be a file or directory: " + applicationPath);
        }

        // Validate that application is a JAR file for Java (only warn for files)
        if (Files.isRegularFile(applicationPath)) {
            String fileName = applicationPath.getFileName().toString().toLowerCase();
            if (!fileName.endsWith(".jar")) {
                log.warn("Application file does not appear to be a JAR file: {}", fileName);
            }
        }
    }

    /**
     * Validates the source code path is an existing, readable directory.
     */
    private void validateSourceCodePath() {
        if (sourceCodePath == null) {
            throw new IllegalStateException("Source code path cannot be null");
        }

        if (!Files.exists(sourceCodePath)) {
            throw new IllegalStateException("Source code directory does not exist: " + sourceCodePath);
        }

        // PP: disabled this check because source code path refers to a single file when tests are generated using LLM
        /*
        if (!Files.isDirectory(sourceCodePath)) {
            throw new IllegalStateException("Source code path must point to a directory: " + sourceCodePath);
        }
        */

        if (!Files.isReadable(sourceCodePath)) {
            throw new IllegalStateException("Source code directory is not readable: " + sourceCodePath);
        }
    }

    /**
     * Validates the DiSL home path points to a valid DiSL installation directory.
     * Checks for the existence of key subfiles/directories like bin/disl.py and output/lib.
     */
    private void validateDislHomePath() {
        if (dislHomePath == null) {
            throw new IllegalStateException("DiSL home path cannot be null");
        }

        if (!Files.exists(dislHomePath)) {
            throw new IllegalStateException("DiSL home directory does not exist: " + dislHomePath);
        }

        if (!Files.isDirectory(dislHomePath)) {
            throw new IllegalStateException("DiSL home path must point to a directory: " + dislHomePath);
        }

        if (!Files.isReadable(dislHomePath)) {
            throw new IllegalStateException("DiSL home directory is not readable: " + dislHomePath);
        }

        // Check for key DiSL installation files/directories
        Path dislPyPath = dislHomePath.resolve("bin/disl.py");
        if (!Files.exists(dislPyPath)) {
            throw new IllegalStateException("DiSL installation appears invalid - missing bin/disl.py: " + dislPyPath);
        }

        Path outputLibPath = dislHomePath.resolve("output/lib");
        if (!Files.exists(outputLibPath)) {
            throw new IllegalStateException("DiSL installation appears invalid - missing output/lib directory: " + outputLibPath);
        }

        if (!Files.isDirectory(outputLibPath)) {
            throw new IllegalStateException("DiSL installation appears invalid - output/lib is not a directory: " + outputLibPath);
        }
    }

    /**
     * Validates each classpath entry exists and is either a readable .jar file or directory.
     */
    private void validateClasspathEntries() {
        if (classpathEntries == null) {
            return; // Classpath entries are optional
        }

        for (int i = 0; i < classpathEntries.size(); i++) {
            Path classpathEntry = classpathEntries.get(i);

            if (classpathEntry == null) {
                throw new IllegalStateException("Classpath entry at index " + i + " cannot be null");
            }

            if (!Files.exists(classpathEntry)) {
                throw new IllegalStateException("Classpath entry does not exist: " + classpathEntry);
            }

            if (!Files.isReadable(classpathEntry)) {
                throw new IllegalStateException("Classpath entry is not readable: " + classpathEntry);
            }

            // Must be either a JAR file or a directory
            if (Files.isRegularFile(classpathEntry)) {
                String fileName = classpathEntry.getFileName().toString().toLowerCase();
                if (!fileName.endsWith(".jar")) {
                    log.warn("Classpath entry appears to be a file but not a JAR: {}", classpathEntry);
                }
            } else if (!Files.isDirectory(classpathEntry)) {
                throw new IllegalStateException("Classpath entry must be either a JAR file or directory: " + classpathEntry);
            }
        }
    }

    /**
     * Validates the output directory is writable or can be created.
     */
    private void validateOutputDirectory() {
        if (outputDirectory == null) {
            throw new IllegalStateException("Output directory cannot be null");
        }

        if (Files.exists(outputDirectory)) {
            if (!Files.isDirectory(outputDirectory)) {
                throw new IllegalStateException("Output path exists but is not a directory: " + outputDirectory);
            }

            if (!Files.isWritable(outputDirectory)) {
                throw new IllegalStateException("Output directory is not writable: " + outputDirectory);
            }
        } else {
            // Directory doesn't exist, check if we can create it
            Path parent = outputDirectory.getParent();
            if (parent != null && Files.exists(parent)) {
                if (!Files.isWritable(parent)) {
                    throw new IllegalStateException("Cannot create output directory - parent directory is not writable: " + parent);
                }
            } else if (parent != null) {
                throw new IllegalStateException("Cannot create output directory - parent directory does not exist: " + parent);
            }

            // Try to create the directory to verify we can write to it
            try {
                Files.createDirectories(outputDirectory);
                log.debug("Created output directory: {}", outputDirectory);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create output directory: " + outputDirectory, e);
            }
        }
    }

    /**
     * Validates the target method is specified and can be parsed to FULL_METHOD state.
     */
    private void validateTargetMethod() {
        if (targetMethod == null) {
            throw new IllegalStateException("Target method cannot be null");
        }

        // Validate that the method signature can be parsed to FULL_METHOD state
        String methodSignature = buildMethodSignature(targetMethod);
        MethodSignature signature = JavaMethodSignatureParser.parseMethodSignature(methodSignature);

        if (signature.getState() != SignatureState.FULL_METHOD) {
            throw new IllegalStateException(
                "Target method signature cannot be parsed to FULL_METHOD state. " +
                "Expected format: 'package.Class.method(param1,param2)'. " +
                "Current signature: " + methodSignature + ", parsed state: " + signature.getState()
            );
        }
    }

    /**
     * Validates exportable values for correct slot indices and field existence.
     */
    private void validateExportableValues() {
        if (exportableValues == null || exportableValues.isEmpty()) {
            log.warn("No exportable values specified - analysis may not capture any data");
            return;
        }

        List<String> methodParameterTypes = targetMethod.getParameterTypes();

        for (JavaValueIdentifier exportableValue : exportableValues) {
            if (exportableValue instanceof JavaArgumentIdentifier argId) {
                validateArgumentIdentifier(argId, methodParameterTypes);
            } else if (exportableValue instanceof JavaFieldIdentifier fieldId) {
                validateFieldIdentifier(fieldId);
            }
        }
    }

    /**
     * Validates that a JavaArgumentIdentifier has a valid slot index for the target method's parameter list.
     */
    private void validateArgumentIdentifier(JavaArgumentIdentifier argumentIdentifier, List<String> methodParameterTypes) {
        int argumentSlot = argumentIdentifier.getArgumentSlot();

        if (argumentSlot < 0) {
            throw new IllegalStateException(
                "Argument slot index cannot be negative. Got: " + argumentSlot +
                " for argument identifier: " + argumentIdentifier.getName()
            );
        }

        if (argumentSlot >= methodParameterTypes.size()) {
            throw new IllegalStateException(
                "Argument slot index " + argumentSlot + " is out of bounds for target method with " +
                methodParameterTypes.size() + " parameters. Valid range: 0-" + (methodParameterTypes.size() - 1) +
                " for argument identifier: " + argumentIdentifier.getName()
            );
        }

        // Optional: Validate that the argument type matches the method parameter type
        String expectedType = methodParameterTypes.get(argumentSlot);
        String actualType = argumentIdentifier.getType();
        if (!isTypeCompatible(expectedType, actualType)) {
            log.warn("Argument type mismatch for slot {}: expected '{}', got '{}' in argument identifier: {}",
                argumentSlot, expectedType, actualType, argumentIdentifier.getName());
        }
    }

    /**
     * Validates that a JavaFieldIdentifier references an existing field in the target method's owning class or superclass hierarchy.
     * Note: This is a basic validation - full validation would require classpath analysis.
     */
    private void validateFieldIdentifier(JavaFieldIdentifier fieldIdentifier) {
        if (fieldIdentifier.getFieldName() == null || fieldIdentifier.getFieldName().trim().isEmpty()) {
            throw new IllegalStateException("Field name cannot be null or empty in field identifier");
        }

        if (fieldIdentifier.getOwnerClassIdentifier() == null) {
            throw new IllegalStateException("Field identifier must have an owner class identifier for field: " + fieldIdentifier.getFieldName());
        }

        // Note: Full field existence validation would require loading the class from the classpath
        // and checking the field hierarchy. This is a complex operation that would require:
        // 1. Loading the target class using a custom ClassLoader with the provided classpath
        // 2. Checking the field in the class and its superclass hierarchy
        // 3. Handling potential ClassNotFoundException and other reflection issues
        // For now, we perform basic structural validation only.

        log.debug("Field identifier validation passed for field: {} in class: {}",
            fieldIdentifier.getFieldName(),
            fieldIdentifier.getOwnerClassIdentifier().getName());
    }

    /**
     * Builds a method signature string from a JavaMethodIdentifier for validation purposes.
     */
    private String buildMethodSignature(JavaMethodIdentifier methodIdentifier) {
        StringBuilder signature = new StringBuilder();

        // Add package and class name
        if (methodIdentifier.getOwnerClassIdentifier() != null) {
            if (methodIdentifier.getOwnerClassIdentifier().getPackageIdentifier() != null) {
                String packageName = methodIdentifier.getOwnerClassIdentifier().getPackageIdentifier().getName();
                if (packageName != null && !packageName.isEmpty() && !packageName.equals("default")) {
                    signature.append(packageName).append(".");
                }
            }
            signature.append(methodIdentifier.getOwnerClassIdentifier().getName());
        }

        // Add method name
        signature.append(".").append(methodIdentifier.getName());

        // Add parameters
        signature.append("(");
        List<String> parameterTypes = methodIdentifier.getParameterTypes();
        if (parameterTypes != null) {
            for (int i = 0; i < parameterTypes.size(); i++) {
                if (i > 0) {
                    signature.append(",");
                }
                signature.append(parameterTypes.get(i));
            }
        }
        signature.append(")");

        return signature.toString();
    }

    /**
     * Checks if two Java types are compatible for argument validation.
     * This is a simplified type compatibility check.
     */
    private boolean isTypeCompatible(String expectedType, String actualType) {
        if (expectedType == null || actualType == null) {
            return false;
        }

        // Exact match
        if (expectedType.equals(actualType)) {
            return true;
        }

        // Handle primitive type variations
        String normalizedExpected = normalizeType(expectedType);
        String normalizedActual = normalizeType(actualType);

        return normalizedExpected.equals(normalizedActual);
    }

    /**
     * Normalizes Java type names for comparison.
     */
    private String normalizeType(String type) {
        if (type == null) {
            return null;
        }

        // Remove whitespace
        type = type.trim();

        // Handle common type variations
        return switch (type) {
            case "java.lang.String" -> "String";
            case "java.lang.Integer" -> "int";
            case "java.lang.Boolean" -> "boolean";
            case "java.lang.Double" -> "double";
            case "java.lang.Float" -> "float";
            case "java.lang.Long" -> "long";
            case "java.lang.Short" -> "short";
            case "java.lang.Byte" -> "byte";
            case "java.lang.Character" -> "char";
            default -> type;
        };
    }
}
