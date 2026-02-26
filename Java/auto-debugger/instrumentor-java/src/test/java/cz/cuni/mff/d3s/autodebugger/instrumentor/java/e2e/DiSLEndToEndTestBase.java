package cz.cuni.mff.d3s.autodebugger.instrumentor.java.e2e;

import cz.cuni.mff.d3s.autodebugger.analyzer.common.AnalysisResult;
import cz.cuni.mff.d3s.autodebugger.analyzer.java.DiSLAnalyzer;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.DiSLInstrumentor;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.DiSLModel;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.IndexedTrace;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.testutils.DiSLPathResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Abstract base class for DiSL end-to-end integration tests.
 * Provides reusable infrastructure for testing the complete DiSL instrumentation,
 * analysis, and trace collection pipeline.
 */
public abstract class DiSLEndToEndTestBase {

    @TempDir
    protected Path tempDir;

    protected Path targetJarsDir;
    protected Path outputDir;
    protected Path dislHome;

    /**
     * Checks DiSL availability before running tests.
     * Tests are skipped if DiSL is not available.
     */
    @BeforeAll
    static void requireDiSL() {
        assumeTrue(DiSLPathResolver.getDislHomePath().isPresent(),
                "Skipping E2E tests - DiSL not available. Set DISL_HOME environment variable.");
    }

    /**
     * Sets up temporary directories for each test.
     */
    @BeforeEach
    void setUpDirectories() throws Exception {
        targetJarsDir = tempDir.resolve("target-jars");
        outputDir = tempDir.resolve("output");
        Files.createDirectories(targetJarsDir);
        Files.createDirectories(outputDir);

        dislHome = DiSLPathResolver.requireDislHomePath();
    }

    // ========== Target JAR Creation ==========

    /**
     * Compiles Java source code and packages it into a JAR file.
     *
     * @param sourceCode Java source code as a string
     * @param jarName Name of the output JAR file
     * @param mainClass Fully qualified main class name (e.g., "com.example.Main")
     * @return Path to the created JAR file
     */
    protected Path compileAndPackageTarget(String sourceCode, String jarName, String mainClass) throws Exception {
        // Extract package and class name from mainClass
        String packageName = "";
        String className = mainClass;
        int lastDot = mainClass.lastIndexOf('.');
        if (lastDot > 0) {
            packageName = mainClass.substring(0, lastDot);
            className = mainClass.substring(lastDot + 1);
        }

        // Create source file with proper package structure
        Path srcDir = tempDir.resolve("src");
        Path packageDir = srcDir;
        if (!packageName.isEmpty()) {
            packageDir = srcDir.resolve(packageName.replace('.', '/'));
        }
        Files.createDirectories(packageDir);

        Path sourceFile = packageDir.resolve(className + ".java");
        Files.writeString(sourceFile, sourceCode);

        return compileJavaToJar(sourceFile, jarName, mainClass);
    }

    /**
     * Compiles a Java source file and packages it into a JAR.
     *
     * @param sourceFile Path to the Java source file
     * @param jarName Name of the output JAR file
     * @param mainClass Fully qualified main class name
     * @return Path to the created JAR file
     */
    protected Path compileJavaToJar(Path sourceFile, String jarName, String mainClass) throws Exception {
        // Compile the source file
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available. Ensure you're running with a JDK, not JRE.");
        }

        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));

            Iterable<? extends JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            boolean success = compiler.getTask(null, fileManager, diagnostics, null, null, compilationUnits).call();

            if (!success) {
                StringBuilder errors = new StringBuilder("Compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    errors.append(diagnostic.toString()).append("\n");
                }
                throw new RuntimeException(errors.toString());
            }
        }

        // Package into JAR
        Path jarPath = targetJarsDir.resolve(jarName);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);

        try (JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jarPath.toFile()), manifest)) {
            addDirectoryToJar(classesDir, classesDir, jarOut);
        }

        return jarPath;
    }

    /**
     * Recursively adds directory contents to a JAR file.
     */
    private void addDirectoryToJar(Path sourceDir, Path baseDir, JarOutputStream jarOut) throws IOException {
        Files.walk(sourceDir)
            .filter(Files::isRegularFile)
            .forEach(file -> {
                try {
                    Path relativePath = baseDir.relativize(file);
                    String entryName = relativePath.toString().replace('\\', '/');

                    JarEntry entry = new JarEntry(entryName);
                    entry.setTime(Files.getLastModifiedTime(file).toMillis());
                    jarOut.putNextEntry(entry);

                    Files.copy(file, jarOut);
                    jarOut.closeEntry();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
    }

    // ========== Configuration Builders ==========

    /**
     * Creates a JavaRunConfiguration builder with common defaults.
     */
    protected JavaRunConfiguration.JavaRunConfigurationBuilder createRunConfigurationBuilder(
            Path applicationPath, JavaMethodIdentifier targetMethod, List<JavaValueIdentifier> exportableValues) {
        return JavaRunConfiguration.builder()
                .applicationPath(applicationPath)
                .sourceCodePath(tempDir.resolve("src"))
                .dislHomePath(dislHome)
                .targetMethod(targetMethod)
                .exportableValues(exportableValues)
                .outputDirectory(outputDir)
                // Include target JAR in classpath for DiSL compilation (needed for field access)
                .classpathEntries(List.of(applicationPath));
    }

    /**
     * Creates a JavaMethodIdentifier for a method.
     */
    protected JavaMethodIdentifier createMethodIdentifier(
            String packageName, String className, String methodName,
            String returnType, List<String> parameterTypes) {
        return createMethodIdentifier(packageName, className, methodName, returnType, parameterTypes, false);
    }

    protected JavaMethodIdentifier createMethodIdentifier(
            String packageName, String className, String methodName,
            String returnType, List<String> parameterTypes, boolean isStatic) {
        JavaPackageIdentifier packageId = packageName.isEmpty()
                ? JavaPackageIdentifier.DEFAULT_PACKAGE
                : new JavaPackageIdentifier(packageName);

        JavaClassIdentifier classId = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .packageIdentifier(packageId)
                        .className(className)
                        .build());

        return new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(classId)
                        .methodName(methodName)
                        .returnType(returnType)
                        .parameterTypes(parameterTypes)
                        .isStatic(isStatic)
                        .build());
    }

    /**
     * Creates a JavaArgumentIdentifier for a method argument.
     */
    protected JavaArgumentIdentifier createArgumentIdentifier(int argumentSlot, String variableType) {
        return new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(argumentSlot)
                        .variableType(variableType)
                        .build());
    }

    /**
     * Creates a JavaFieldIdentifier for a field.
     */
    protected JavaFieldIdentifier createFieldIdentifier(
            String packageName, String className, String fieldName, String fieldType) {
        return createFieldIdentifier(packageName, className, fieldName, fieldType, false);
    }

    /**
     * Creates a JavaFieldIdentifier for a field with optional static modifier.
     */
    protected JavaFieldIdentifier createFieldIdentifier(
            String packageName, String className, String fieldName, String fieldType, boolean isStatic) {
        JavaPackageIdentifier packageId = packageName.isEmpty()
                ? JavaPackageIdentifier.DEFAULT_PACKAGE
                : new JavaPackageIdentifier(packageName);

        JavaClassIdentifier classId = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .packageIdentifier(packageId)
                        .className(className)
                        .build());

        return new JavaFieldIdentifier(
                FieldIdentifierParameters.builder()
                        .variableName(fieldName)
                        .variableType(fieldType)
                        .ownerClassIdentifier(classId)
                        .isStatic(isStatic)
                        .build());
    }

    // ========== Instrumentation Helpers ==========

    /**
     * Creates a DiSLInstrumentor instance.
     */
    protected DiSLInstrumentor createInstrumentor(
            JavaRunConfiguration runConfiguration, JavaClassIdentifier instrumentationClassName) {
        Path generatedCodeDir = outputDir.resolve("generated");
        Path jarOutputPath = outputDir.resolve("instrumentation.jar");

        return DiSLInstrumentor.builder()
                .instrumentationClassName(instrumentationClassName)
                .runConfiguration(runConfiguration)
                .generatedCodeOutputDirectory(generatedCodeDir)
                .jarOutputPath(jarOutputPath)
                .build();
    }

    /**
     * Generates instrumentation using DiSLInstrumentor.
     */
    protected InstrumentationResult generateInstrumentation(
            DiSLInstrumentor instrumentor, JavaMethodIdentifier targetMethod,
            List<JavaValueIdentifier> exportableValues) throws Exception {
        DiSLModel model = new DiSLModel(targetMethod, exportableValues);
        return instrumentor.generateInstrumentation(model);
    }

    // ========== Analysis Helpers ==========

    /**
     * Creates a DiSLAnalyzer instance.
     */
    protected DiSLAnalyzer createAnalyzer(JavaRunConfiguration runConfiguration) {
        return new DiSLAnalyzer(runConfiguration);
    }

    /**
     * Executes analysis using DiSLAnalyzer.
     */
    protected AnalysisResult executeAnalysis(
            DiSLAnalyzer analyzer, InstrumentationResult instrumentation) throws Exception {
        return analyzer.executeAnalysis(instrumentation);
    }

    // ========== Trace Deserialization ==========

    /**
     * Deserializes a Trace from a file.
     */
    protected Trace deserializeTrace(Path traceFile) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(traceFile))) {
            return (Trace) ois.readObject();
        }
    }

    /**
     * Deserializes an IndexedTrace from a file.
     */
    protected IndexedTrace deserializeIndexedTrace(Path traceFile) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(traceFile))) {
            return (IndexedTrace) ois.readObject();
        }
    }

    /**
     * Deserializes identifier mapping from a file.
     */
    @SuppressWarnings("unchecked")
    protected Map<Integer, JavaValueIdentifier> deserializeIdentifierMapping(Path mappingFile) throws Exception {
        try (FileInputStream fis = new FileInputStream(mappingFile.toFile());
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            return (Map<Integer, JavaValueIdentifier>) ois.readObject();
        }
    }

    /**
     * Finds the slot number for a given argument identifier in the mapping.
     */
    protected int findSlotForArgument(
            Map<Integer, JavaValueIdentifier> mapping, int argumentSlot) {
        for (Map.Entry<Integer, JavaValueIdentifier> entry : mapping.entrySet()) {
            if (entry.getValue() instanceof JavaArgumentIdentifier) {
                JavaArgumentIdentifier argId = (JavaArgumentIdentifier) entry.getValue();
                if (argId.getArgumentSlot() == argumentSlot) {
                    return entry.getKey();
                }
            }
        }
        throw new IllegalArgumentException("No slot found for argument " + argumentSlot);
    }

    /**
     * Finds the slot number for a given field identifier in the mapping.
     *
     * @param mapping The identifier mapping from slot to value identifier
     * @param fieldName The name of the field to find
     * @return The slot number for the field
     * @throws IllegalArgumentException if no slot found for the field
     */
    protected int findSlotForField(
            Map<Integer, JavaValueIdentifier> mapping, String fieldName) {
        for (Map.Entry<Integer, JavaValueIdentifier> entry : mapping.entrySet()) {
            if (entry.getValue() instanceof JavaFieldIdentifier) {
                JavaFieldIdentifier fieldId = (JavaFieldIdentifier) entry.getValue();
                if (fieldId.getFieldName().equals(fieldName)) {
                    return entry.getKey();
                }
            }
        }
        throw new IllegalArgumentException("No slot found for field " + fieldName);
    }
}


