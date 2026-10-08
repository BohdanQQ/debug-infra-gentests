package cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace;

import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.MethodIdentifier;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for NaiveTraceBasedGenerator RunConfiguration-based methods.
 * This test verifies that the generator can create tests directly from RunConfiguration
 * without requiring manual TestGenerationContext construction.
 */
class NaiveTraceBasedGeneratorRunConfigurationTest {

    @TempDir
    Path tempDir;

    private NaiveTraceBasedGenerator generator;
    private Map<Integer, JavaValueIdentifier> identifierMapping;
    private JavaRunConfiguration runConfiguration;
    private Trace trace;

    @BeforeEach
    void setUp() {
        // Create identifier mapping
        identifierMapping = new HashMap<>();
        
        JavaArgumentIdentifier arg1 = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(0)
                .variableType("int")
                .build()
        );
        JavaArgumentIdentifier arg2 = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(1)
                .variableType("int")
                .build()
        );

        identifierMapping.put(0, arg1);
        identifierMapping.put(1, arg2);
        
        generator = new NaiveTraceBasedGenerator(identifierMapping);

        // Create Java method identifier
        JavaPackageIdentifier packageIdentifier = new JavaPackageIdentifier("com.example.math");
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
                .parameterTypes(Arrays.asList("int", "int"))
                .build()
        );

        // Create run configuration
        runConfiguration = JavaRunConfiguration.builder()
                .applicationPath(Path.of("test-app.jar"))
                .sourceCodePath(Path.of("src/main/java"))
                .targetMethod(methodIdentifier)
                .outputDirectory(tempDir)
                .build();

        // Create trace with test data
        trace = new Trace();
        trace.addIntValue(0, 5);
        trace.addIntValue(1, 10);
        trace.addIntValue(0, 3);
        trace.addIntValue(1, 7);
    }

    @Test
    void givenRunConfiguration_whenGeneratingTests_thenCreatesValidTestFile() throws Exception {
        // When - generate tests using RunConfiguration directly
        List<Path> generatedFiles = generator.generateTests(trace, runConfiguration);

        // Then - verify test generation succeeded
        assertNotNull(generatedFiles);
        assertEquals(1, generatedFiles.size(), "Should generate exactly one test file");
        
        Path testFile = generatedFiles.getFirst();
        assertTrue(Files.exists(testFile), "Generated test file should exist");
        
        String content = Files.readString(testFile);
        
        // Verify that the content uses information from JavaMethodIdentifier
        assertTrue(content.contains("com.example.math.Calculator.add(int, int)"), 
                  "Should use fully qualified signature from JavaMethodIdentifier");
        assertTrue(content.contains("com.example.math.Calculator"), 
                  "Should use fully qualified class name from JavaMethodIdentifier");
        assertTrue(content.contains("package com.example.math"), 
                  "Should use package name from JavaMethodIdentifier");
        
        // Verify test structure
        assertTrue(content.contains("@Test"), "Should contain test annotations");
        assertTrue(content.contains("void test"), "Should contain test methods");
        assertTrue(content.contains("assertNotNull"), "Should contain assertions");
    }

    @Test
    void givenRunConfigurationVsManualContext_whenGeneratingTests_thenProducesSimilarResults() throws Exception {
        // Given - create manual TestGenerationContext for comparison
        MethodIdentifier methodIdentifier = new MethodIdentifier("add", "int", List.of("int","int")) {
            @Override public String getClassName() { return "Calculator"; }
            @Override public String getPackageName() { return "com.example.math"; }
            @Override public String getFullyQualifiedClassName() { return "com.example.math.Calculator"; }
            @Override public String getFullyQualifiedSignature() { return "com.example.math.Calculator.add(int, int)"; }
        };
        TestGenerationContext manualContext = TestGenerationContext.builder()
                .targetMethod(methodIdentifier)
                .outputDirectory(tempDir.resolve("manual"))
                .build();

        // When - generate tests both ways
        List<Path> configBasedFiles = generator.generateTests(trace, runConfiguration);
        List<Path> contextBasedFiles = generator.generateTests(trace, tempDir, manualContext);

        // Then - both should succeed and produce similar results
        assertEquals(1, configBasedFiles.size());
        assertEquals(1, contextBasedFiles.size());
        
        String configBasedContent = Files.readString(configBasedFiles.getFirst());
        String contextBasedContent = Files.readString(contextBasedFiles.getFirst());
        
        // Both should contain the same key elements
        assertTrue(configBasedContent.contains("com.example.math.Calculator"));
        assertTrue(contextBasedContent.contains("com.example.math.Calculator"));
        
        assertTrue(configBasedContent.contains("package com.example.math"));
        assertTrue(contextBasedContent.contains("package com.example.math"));
        
        // Both should have similar test structure
        assertTrue(configBasedContent.contains("@Test"));
        assertTrue(contextBasedContent.contains("@Test"));
    }

    @Test
    void givenNullRunConfiguration_whenGeneratingTests_thenThrowsException() {
        // When/Then - should throw exception for null configuration
        assertThrows(IllegalArgumentException.class, () -> {
            generator.generateTests(trace, (JavaRunConfiguration) null);
        });
    }

    @Test
    void givenEmptyTrace_whenGeneratingTests_thenThrowsException() {
        // Given - empty trace
        Trace emptyTrace = new Trace();

        // When/Then - should throw exception for empty trace
        assertThrows(IllegalArgumentException.class, () -> {
            generator.generateTests(emptyTrace, runConfiguration);
        });
    }

    @Test
    void givenJavaMethodIdentifier_whenGeneratingTests_thenUsesUtilityMethods() throws Exception {
        // When - generate tests using RunConfiguration
        List<Path> generatedFiles = generator.generateTests(trace, runConfiguration);

        // Then - verify that the utility methods from JavaMethodIdentifier are used
        String content = Files.readString(generatedFiles.getFirst());
        
        // Verify fully qualified signature is used (from getFullyQualifiedSignature())
        assertTrue(content.contains("com.example.math.Calculator.add(int, int)"));
        
        // Verify fully qualified class name is used (from getFullyQualifiedClassName())
        assertTrue(content.contains("com.example.math.Calculator"));
        
        // Verify package name is used (from getPackageName())
        assertTrue(content.contains("package com.example.math"));
        
        // Verify the content is well-formed
        assertTrue(content.contains("import"), "Should contain import statements");
        assertTrue(content.contains("public class"), "Should contain class declaration");
        // PP: setUp method with @BeforeEach annotation does not have to be generated in the current version
        //assertTrue(content.contains("@BeforeEach"), "Should contain setup method");
    }
}
