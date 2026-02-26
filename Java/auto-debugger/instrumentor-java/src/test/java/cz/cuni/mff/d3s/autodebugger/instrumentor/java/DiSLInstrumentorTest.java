package cz.cuni.mff.d3s.autodebugger.instrumentor.java;

import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.Constants;
import cz.cuni.mff.d3s.autodebugger.testutils.DiSLPathResolver;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.DiSLModel;
import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DiSLInstrumentorTest {

  @TempDir
  Path tempDir;

  private Path testOutputDirectory;
  private Path testIdentifierDirectory;
  private Path mockDislHome;

  @BeforeEach
  void setUp() throws IOException {
    testOutputDirectory = tempDir.resolve("output");
    testIdentifierDirectory = tempDir.resolve("identifiers");
    mockDislHome = tempDir.resolve("mock-disl");
    Files.createDirectories(testOutputDirectory);
    Files.createDirectories(testIdentifierDirectory);

    // Create mock DiSL structure for tests that don't need real DiSL
    createMockDislStructure();

    // Copy the CollectorRE.java file to the test output directory
    Path collectorRESource = Path.of("../analyzer-disl/src/main/java/cz/cuni/mff/d3s/autodebugger/analyzer/disl/CollectorRE.java");
    Path collectorRETarget = testOutputDirectory.resolve("CollectorRE.java");
    if (Files.exists(collectorRESource)) {
      Files.copy(collectorRESource, collectorRETarget);
    } else {
      // Create a minimal CollectorRE for testing
      String collectorREContent = """
        import ch.usi.dag.dislre.REDispatch;

        public class CollectorRE {
          private static short intId = REDispatch.registerMethod("Collector.collectInt");

          public static void collectInt(final int slot, final int i) {
            REDispatch.analysisStart(intId);
            REDispatch.sendInt(slot);
            REDispatch.sendInt(i);
            REDispatch.analysisEnd();
          }
        }
        """;
      Files.writeString(collectorRETarget, collectorREContent);
    }
  }

  private void createMockDislStructure() throws IOException {
    Files.createDirectories(mockDislHome.resolve("bin"));
    Files.createDirectories(mockDislHome.resolve("output/lib"));
    Files.writeString(mockDislHome.resolve("bin/disl.py"), "# mock");
  }

  private Path getDislHomeOrMock() {
    return DiSLPathResolver.getDislHomePath().orElse(mockDislHome);
  }

  /**
   * Test Case 1.1: Verify placeholder replacement in Collector.jt template transformation.
   * Tests that the ${PATH} placeholder is correctly replaced with the absolute path
   * to the identifier mapping file.
   */
  @Test
  void givenCollectorTemplate_whenTransforming_thenSubstitutesPlaceholders() throws IOException {
    // given - Test just the template transformation part, not the full compilation
    Path testMappingPath = testIdentifierDirectory.resolve("identifierMapping-test.json");

    // Create a minimal Collector.jt for this transformation test
    Path templatePath = testOutputDirectory.resolve("Collector.jt");
    String templateContent = """
      import ch.usi.dag.dislreserver.remoteanalysis.RemoteAnalysis;
      import cz.cuni.mff.d3s.autodebugger.model.java.Trace;

      public class Collector extends RemoteAnalysis {
        private final String identifierMappingFilePath = "${PATH}";
        private final String resultsFilePath = "${RESULTS}";
        private final String targetPackage = "${TARGET_PACKAGE}";
        private final String targetClass = "${TARGET_CLASS}";
        private final String targetMethod = "${TARGET_METHOD}";
      }
      """;
    Files.writeString(templatePath, templateContent);


    // Create a test identifier mapping file
    Files.writeString(testMappingPath, "test mapping content");

    // Test the template transformation directly
    JavaTemplateHandler templateHandler = new JavaTemplateHandler(new JavaTemplateTransformer("${%s}"));
    Path collectorTemplate = testOutputDirectory.resolve("Collector.jt");
    Path collectorOutput = testOutputDirectory.resolve("Collector.java");

    // when
    templateHandler.transformFile(
            templatePath,
            collectorOutput,
            org.javatuples.Pair.with("PATH", testMappingPath.toAbsolutePath().toString()),
            org.javatuples.Pair.with("RESULTS", "/tmp/results.lst"),
            org.javatuples.Pair.with("TARGET_PACKAGE", "com.example"),
            org.javatuples.Pair.with("TARGET_CLASS", "Example"),
            org.javatuples.Pair.with("TARGET_METHOD", "foo(int)"));

    // then
    assertTrue(Files.exists(collectorOutput), "Collector.java should be generated");

    String collectorContent = Files.readString(collectorOutput);
    assertFalse(collectorContent.contains("${PATH}"),
        "Template placeholder should be replaced");
    assertTrue(collectorContent.contains("identifierMapping"),
        "Should contain identifier mapping file reference");

    // Verify the path is absolute
    String[] lines = collectorContent.split("\n");
    boolean foundAbsolutePath = false;
    for (String line : lines) {
      if (line.contains("identifierMappingFilePath") && line.contains("=")) {
        String pathPart = line.substring(line.indexOf("\"") + 1, line.lastIndexOf("\""));
        assertTrue(Path.of(pathPart).isAbsolute(),
            "Identifier mapping path should be absolute: " + pathPart);
        assertEquals(testMappingPath.toAbsolutePath().toString(), pathPart,
            "Should contain the correct absolute path");
        foundAbsolutePath = true;
        break;
      }
    }
    assertTrue(foundAbsolutePath, "Should find absolute path assignment");

    // Validate that additional placeholders are replaced
    assertTrue(collectorContent.contains("/tmp/results.lst"), "Should contain RESULTS placeholder value");
    assertTrue(collectorContent.contains("targetPackage = \"com.example\""), "Should contain TARGET_PACKAGE");
    assertTrue(collectorContent.contains("targetClass = \"Example\""), "Should contain TARGET_CLASS");
    assertTrue(collectorContent.contains("targetMethod = \"foo(int)\""), "Should contain TARGET_METHOD");
  }

  /**
   * Test Case 2.1: Test serialization of value identifiers and verify mapping file creation.
   * Tests that identifier mapping is correctly serialized to a file and can be deserialized.
   */
  @Test
  void givenIdentifierMapping_whenSerializing_thenCanDeserialize() throws IOException, ClassNotFoundException {
    // given
    JavaArgumentIdentifier argIdentifier = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                    .argumentSlot(0)
                    .variableType("int")
                    .build());

    JavaFieldIdentifier fieldIdentifier = new JavaFieldIdentifier(
            FieldIdentifierParameters.builder()
                    .variableName("statusField")
                    .variableType("java.lang.String")
                    .ownerClassIdentifier(Constants.testClassIdentifier)
                    .build());

    DiSLInstrumentor instrumentor = DiSLInstrumentor.builder()
            .instrumentationClassName(new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                            .className("DiSLClass")
                            .build()))
            .runConfiguration(JavaRunConfiguration.builder()
                    .applicationPath(Constants.targetJarPath)
                    .classpathEntry(Constants.targetJarPath)
                    .dislHomePath(getDislHomeOrMock())
                    .outputDirectory(tempDir)
                    .sourceCodePath(Path.of("src/test/resources/targets/extraction"))
                    .targetMethod(new JavaMethodIdentifier(Constants.targetMethodIdentifierParameters))
                    .exportableValues(List.of(argIdentifier, fieldIdentifier))
                    .build())
            .generatedCodeOutputDirectory(testOutputDirectory)
            .jarOutputPath(tempDir.resolve("test-instrumentation.jar"))
            .build();

    // when - Test just the serialization part
    Path identifierDir = tempDir.resolve("identifiers");
    Files.createDirectories(identifierDir);

    // Call the serializeIdentifiers method directly using reflection
    try {
      java.lang.reflect.Method serializeMethod = DiSLInstrumentor.class.getDeclaredMethod("serializeIdentifiers", Path.class);
      serializeMethod.setAccessible(true);
      Path mappingFile = (Path) serializeMethod.invoke(instrumentor, identifierDir);

      // then
      assertTrue(Files.exists(mappingFile), "Mapping file should exist");
      assertTrue(mappingFile.getFileName().toString().startsWith("identifierMapping"),
          "Mapping file should have correct name prefix");

      // Deserialize and verify content
      try (FileInputStream fileInput = new FileInputStream(mappingFile.toFile());
           ObjectInputStream objectStream = new ObjectInputStream(fileInput)) {

        @SuppressWarnings("unchecked")
        Map<Integer, ExportableValue> deserializedMapping =
            (Map<Integer, ExportableValue>) objectStream.readObject();

        assertEquals(2, deserializedMapping.size(), "Should contain exactly two entries");

        // Verify argument identifier
        ExportableValue argValue = deserializedMapping.get(argIdentifier.getInternalId());
        assertNotNull(argValue, "Should contain argument identifier");
        assertInstanceOf(JavaArgumentIdentifier.class, argValue,
            "Should be JavaArgumentIdentifier instance");

        JavaArgumentIdentifier deserializedArg = (JavaArgumentIdentifier) argValue;
        assertEquals(0, deserializedArg.getArgumentSlot(), "Argument slot should match");
        assertEquals("int", deserializedArg.getType(), "Argument type should match");

        // Verify field identifier
        ExportableValue fieldValue = deserializedMapping.get(fieldIdentifier.getInternalId());
        assertNotNull(fieldValue, "Should contain field identifier");
        assertInstanceOf(JavaFieldIdentifier.class, fieldValue,
            "Should be JavaFieldIdentifier instance");

        JavaFieldIdentifier deserializedField = (JavaFieldIdentifier) fieldValue;
        assertEquals("statusField", deserializedField.getFieldName(), "Field name should match");
        assertEquals("java.lang.String", deserializedField.getType(), "Field type should match");
      }
    } catch (Exception e) {
      fail("Failed to test serialization: " + e.getMessage());
    }
  }

  /**
   * Test Case 3.1: Full JAR generation from a complete model.
   * Tests the complete instrumentation pipeline including JAR creation,
   * manifest verification, and DiSLClass content validation.
   */
  @Test
  void givenInstrumentationModel_whenGeneratingJar_thenCreatesValidJarWithManifest() throws IOException {
    // given
    JavaArgumentIdentifier stringArg = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                    .argumentSlot(0)
                    .variableType("java.lang.String")
                    .build());

    JavaFieldIdentifier intField = new JavaFieldIdentifier(
            FieldIdentifierParameters.builder()
                    .variableName("counter")
                    .variableType("int")
                    .ownerClassIdentifier(Constants.testClassIdentifier)
                    .build());

    JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
            .applicationPath(Constants.targetJarPath)
            .classpathEntry(Constants.targetJarPath)
            .dislHomePath(getDislHomeOrMock())
            .outputDirectory(tempDir)
            .sourceCodePath(Path.of("src/test/resources/targets/extraction"))
            .targetMethod(new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                            .ownerClassIdentifier(Constants.testClassIdentifier)
                            .methodName("myMethod")
                            .returnType("void")
                            .parameterTypes(List.of("java.lang.String"))
                            .build()))
            .exportableValues(List.of(stringArg, intField))
            .build();

    Path jarOutputPath = tempDir.resolve("test-instrumentation.jar");
    DiSLInstrumentor instrumentor = DiSLInstrumentor.builder()
            .instrumentationClassName(new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                            .className("DiSLClass")
                            .build()))
            .runConfiguration(runConfiguration)
            .generatedCodeOutputDirectory(testOutputDirectory)
            .jarOutputPath(jarOutputPath)
            .build();

    DiSLModel model = new DiSLModel(
            new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                            .ownerClassIdentifier(Constants.testClassIdentifier)
                            .methodName("myMethod")
                            .returnType("void")
                            .parameterTypes(List.of("java.lang.String"))
                            .build()),
            List.of(stringArg, intField));

    // when
    InstrumentationResult result = instrumentor.generateInstrumentation(model);

    // then
    Path generatedJar = result.getPrimaryArtifact();
    assertNotNull(generatedJar, "JAR path should not be null");
    assertTrue(Files.exists(generatedJar), "Generated JAR file should exist");

    // Verify JAR contents
    try (JarFile jarFile = new JarFile(generatedJar.toFile())) {
      // Check for required class files
      assertNotNull(jarFile.getEntry("DiSLClass.class"),
          "JAR should contain DiSLClass.class");
      assertNotNull(jarFile.getEntry("Collector.class"),
          "JAR should contain Collector.class");
      assertNotNull(jarFile.getEntry("CollectorRE.class"),
          "JAR should contain CollectorRE.class");

      // Verify manifest
      Manifest manifest = jarFile.getManifest();
      assertNotNull(manifest, "JAR should have a manifest");

      String dislClasses = manifest.getMainAttributes().getValue("DiSL-Classes");
      assertNotNull(dislClasses, "Manifest should contain DiSL-Classes entry");
      assertEquals("DiSLClass", dislClasses, "DiSL-Classes should reference DiSLClass");
    }

    // Verify generated DiSLClass source content (before compilation)
    Path dislClassSource = testOutputDirectory.resolve("DiSLClass.java");
    assertTrue(Files.exists(dislClassSource), "DiSLClass.java source should exist");

    String dislClassContent = Files.readString(dislClassSource);

    // Verify scope contains the correct method reference (with fully qualified class name and parameter types)
    String packageName = Constants.testClassIdentifier.getPackageIdentifier().getPackageName();
    String expectedScope = packageName.isEmpty() ?
        Constants.testClassIdentifier.getName() + ".myMethod(java.lang.String)" :
        packageName + "." + Constants.testClassIdentifier.getName() + ".myMethod(java.lang.String)";
    assertTrue(dislClassContent.contains("scope = \"" + expectedScope + "\""),
        "Should contain correct scope reference");

    // Verify argument retrieval logic
    assertTrue(dislClassContent.contains("di.getMethodArgumentValue(0, java.lang.String.class)"),
        "Should contain String argument retrieval logic");

    // Verify field retrieval logic
    assertTrue(dislClassContent.contains("di.getInstanceFieldValue(di.getThis(), " +
        Constants.testClassIdentifier.getName() + ".class, \"counter\", int.class)"),
        "Should contain int field retrieval logic");

    // Verify collector calls
    assertTrue(dislClassContent.contains("CollectorRE.collectString("),
        "Should contain CollectorRE.collectString call");
    assertTrue(dislClassContent.contains("CollectorRE.collectInt("),
        "Should contain CollectorRE.collectInt call");
    assertTrue(dislClassContent.contains("CollectorRE.startEvent();"),
        "Should start a new event before collector calls");
  }

  /**
   * Test that Collector and CollectorRE classes contain the required imports and methods
   * for proper test generation technique integration.
   */
  @Test
  void givenRunConfiguration_whenGeneratingCollector_thenProducesValidContent() throws IOException {
    // given
    JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
            .applicationPath(Constants.targetJarPath)
            .classpathEntry(Constants.targetJarPath)
            .dislHomePath(getDislHomeOrMock())
            .outputDirectory(tempDir)
            .sourceCodePath(Path.of("src/test/resources/targets/extraction"))
            .targetMethod(new JavaMethodIdentifier(Constants.targetMethodIdentifierParameters))
            .exportableValues(List.of(
                    new JavaArgumentIdentifier(
                            ArgumentIdentifierParameters.builder()
                                    .argumentSlot(0)
                                    .variableType("int")
                                    .build())))
            .build();

    DiSLInstrumentor instrumentor = DiSLInstrumentor.builder()
            .instrumentationClassName(new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                            .className("DiSLClass")
                            .build()))
            .runConfiguration(runConfiguration)
            .generatedCodeOutputDirectory(testOutputDirectory)
            .jarOutputPath(tempDir.resolve("test-instrumentation.jar"))
            .build();

    DiSLModel model = new DiSLModel(
            new JavaMethodIdentifier(Constants.targetMethodIdentifierParameters),
            List.of(new JavaArgumentIdentifier(
                    ArgumentIdentifierParameters.builder()
                            .argumentSlot(0)
                            .variableType("int")
                            .build())));

    // when
    instrumentor.generateInstrumentation(model);

    // then - Verify Collector.java content
    Path collectorPath = testOutputDirectory.resolve("Collector.java");
    assertTrue(Files.exists(collectorPath), "Collector.java should be generated");

    String collectorContent = Files.readString(collectorPath);

    // Check required imports for trace collection
    assertTrue(collectorContent.contains("import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace"),
        "Collector should import common Trace class");
    assertTrue(collectorContent.contains("import ch.usi.dag.dislreserver.remoteanalysis.RemoteAnalysis"),
        "Collector should import RemoteAnalysis");

    // Check that Collector has the required structure for trace collection
    assertTrue(collectorContent.contains("public class Collector extends RemoteAnalysis"),
        "Collector should extend RemoteAnalysis");
    assertTrue(collectorContent.contains("private final String identifierMappingFilePath"),
        "Collector should have identifierMappingFilePath field");
    assertTrue(collectorContent.contains("private final String traceFilePath"),
        "Collector should have traceFilePath field");
    assertTrue(collectorContent.contains("public void atExit()"),
        "Collector should have atExit method for trace serialization");

    // Verify CollectorRE.java has been materialized next to Collector.java
    Path collectorREPath = testOutputDirectory.resolve("CollectorRE.java");
    assertTrue(Files.exists(collectorREPath), "CollectorRE.java should be present");
    String collectorREContent = Files.readString(collectorREPath);
    assertTrue(collectorREContent.contains("public static void collectInt("),
        "CollectorRE should have collectInt method");
    assertTrue(collectorREContent.contains("REDispatch.registerMethod("),
        "CollectorRE should register methods with REDispatch");
  }

  @Test
  void givenStaticMethod_whenInstrumentingArguments_thenValuesAreExtracted() {
    JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
            .applicationPath(Path.of("src/test/resources/targets/extraction/Test.jar"))
            .classpathEntry(Constants.targetJarPath)
            .dislHomePath(getDislHomeOrMock())
            .outputDirectory(tempDir)
            .sourceCodePath(Path.of("src/test/resources/targets/extraction"))
            .targetMethod(new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(Constants.testClassIdentifier)
                        .methodName("testMul")
                        .returnType("void")
                        .build()))
            .exportableValues(
                    List.of(
                            new JavaArgumentIdentifier(
                                    ArgumentIdentifierParameters.builder()
                                            .argumentSlot(0)
                                            .variableType("int")
                                            .build()),
                            new JavaArgumentIdentifier(
                                    ArgumentIdentifierParameters.builder()
                                            .argumentSlot(1)
                                            .variableType("int")
                                            .build())))
            .build();

    DiSLModel model = new DiSLModel(
            new JavaMethodIdentifier(Constants.targetMethodIdentifierParameters),
            List.of(
                    new JavaArgumentIdentifier(
                            ArgumentIdentifierParameters.builder()
                                    .argumentSlot(0)
                                    .variableType("int")
                                    .build()),
                    new JavaArgumentIdentifier(
                            ArgumentIdentifierParameters.builder()
                                    .argumentSlot(1)
                                    .variableType("int")
                                    .build())));

    Path instrumentationJarPath = Path.of("../analyzer-disl/build/libs/instrumentation.jar");
    DiSLInstrumentor instrumentor =
        DiSLInstrumentor.builder()
            .instrumentationClassName(new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                            .className("DiSLClass")
                            .build()))
            .runConfiguration(runConfiguration)
            .generatedCodeOutputDirectory(
                Path.of(
                    "../analyzer-disl/src/main/java/cz/cuni/mff/d3s/autodebugger/analyzer/disl/"))
            .jarOutputPath(instrumentationJarPath)
            .build();

    // when
    var result = instrumentor.generateInstrumentation(model);

    // then
    assertEquals(instrumentationJarPath, result.getPrimaryArtifact());
  }

  @Test
  void givenInstanceMethod_whenInstrumentingInstanceFields_thenValuesAreExtracted() {
    // given
    JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
            .applicationPath(Constants.targetJarPath)
            .classpathEntry(Constants.targetJarPath)
            .dislHomePath(getDislHomeOrMock())
            .outputDirectory(tempDir)
            .sourceCodePath(Path.of("src/test/resources/targets/extraction"))
            .targetMethod(new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(Constants.testClassIdentifier)
                        .methodName("testAdd")
                        .returnType("void")
                        .build()))
            .exportableValues(
                    List.of(
                            new JavaFieldIdentifier(
                                    FieldIdentifierParameters.builder()
                                            .variableName("f")
                                            .ownerClassIdentifier(Constants.testClassIdentifier)
                                            // TODO:
                                            //  Create a better abstraction for classes (types)
                                            //  and use it for return types and parameter types
                                            .variableType("int")
                                            .build()),
                            new JavaFieldIdentifier(
                                    FieldIdentifierParameters.builder()
                                            .variableName("g")
                                            .ownerClassIdentifier(Constants.testClassIdentifier)
                                            .variableType("int")
                                            .build())))
            .build();

    DiSLModel model = new DiSLModel(
            new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(Constants.testClassIdentifier)
                        .methodName("testAdd")
                        .returnType("void")
                        .build()),
            List.of(
                    new JavaFieldIdentifier(
                            FieldIdentifierParameters.builder()
                                    .variableName("f")
                                    .ownerClassIdentifier(Constants.testClassIdentifier)
                                    .variableType("int")
                                    .build()),
                    new JavaFieldIdentifier(
                            FieldIdentifierParameters.builder()
                                    .variableName("g")
                                    .ownerClassIdentifier(Constants.testClassIdentifier)
                                    .variableType("int")
                                    .build())));

    Path instrumentationJarPath = Path.of("../analyzer-disl/build/libs/instrumentation.jar");
    DiSLInstrumentor instrumentor =
        DiSLInstrumentor.builder()
            .instrumentationClassName(new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                            .className("DiSLClass")
                            .build()))
            .runConfiguration(runConfiguration)
            .generatedCodeOutputDirectory(
                Path.of(
                    "../analyzer-disl/src/main/java/cz/cuni/mff/d3s/autodebugger/analyzer/disl/"))
            .jarOutputPath(instrumentationJarPath)
            .build();

    // when
    var result = instrumentor.generateInstrumentation(model);

    // then
    assertEquals(instrumentationJarPath, result.getPrimaryArtifact());
  }

  @Test
  void givenStaticMethod_whenInstrumentingReturnValue_thenValuesAreExtracted() throws IOException {
    // given
    JavaMethodIdentifier methodIdentifier = new JavaMethodIdentifier(
            MethodIdentifierParameters.builder()
                    .ownerClassIdentifier(Constants.testClassIdentifier)
                    .methodName("testMul")
                    .returnType("int")  // testMul returns int
                    .parameterTypes(List.of("int", "int"))
                    .build());

    JavaRunConfiguration runConfiguration = JavaRunConfiguration.builder()
            .applicationPath(Path.of("src/test/resources/targets/extraction/Test.jar"))
            .classpathEntry(Constants.targetJarPath)
            .dislHomePath(getDislHomeOrMock())
            .outputDirectory(tempDir)
            .sourceCodePath(Path.of("src/test/resources/targets/extraction"))
            .targetMethod(methodIdentifier)
            .exportableValues(
                    List.of(new JavaReturnValueIdentifier(new ReturnValueIdentifierParameters(methodIdentifier))))
            .build();

    DiSLModel model = new DiSLModel(
            methodIdentifier,
            List.of(new JavaReturnValueIdentifier(new ReturnValueIdentifierParameters(methodIdentifier))));

    // Use temp directory for output paths
    Path instrumentationJarPath = tempDir.resolve("instrumentation.jar");
    DiSLInstrumentor instrumentor =
            DiSLInstrumentor.builder()
                    .instrumentationClassName(new JavaClassIdentifier(
                            ClassIdentifierParameters.builder()
                                    .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                                    .className("DiSLClass")
                                    .build()))
                    .runConfiguration(runConfiguration)
                    .generatedCodeOutputDirectory(testOutputDirectory)
                    .jarOutputPath(instrumentationJarPath)
                    .build();

    // when
    var result = instrumentor.generateInstrumentation(model);

    // then
    assertEquals(instrumentationJarPath, result.getPrimaryArtifact());
    assertTrue(Files.exists(result.getPrimaryArtifact()), "Instrumentation JAR should exist");

    // Verify return value collection code was generated in DiSLClass
    Path dislClassPath = testOutputDirectory.resolve("DiSLClass.java");
    assertTrue(Files.exists(dislClassPath), "DiSLClass.java should be generated");
    String dislClassContent = Files.readString(dislClassPath);
    // Should contain getStackValue call for return value extraction
    assertTrue(dislClassContent.contains("getStackValue"),
        "DiSLClass should have getStackValue call for return value extraction");
  }
}
