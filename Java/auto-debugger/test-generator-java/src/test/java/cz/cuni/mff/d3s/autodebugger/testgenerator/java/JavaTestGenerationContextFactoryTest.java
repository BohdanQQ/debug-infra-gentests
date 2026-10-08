package cz.cuni.mff.d3s.autodebugger.testgenerator.java;

import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContext;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationSettings;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestNamingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for JavaTestGenerationContextFactory.
 */
class JavaTestGenerationContextFactoryTest {

    @TempDir
    Path tempDir;

    private JavaRunConfiguration javaRunConfiguration;
    private JavaMethodIdentifier methodIdentifier;

    @BeforeEach
    void setUp() {
        // Create a complete Java method identifier
        JavaPackageIdentifier packageIdentifier = new JavaPackageIdentifier("com.example.service");
        JavaClassIdentifier classIdentifier = new JavaClassIdentifier(
            ClassIdentifierParameters.builder()
                .packageIdentifier(packageIdentifier)
                .className("UserService")
                .build()
        );
        
        methodIdentifier = new JavaMethodIdentifier(
            MethodIdentifierParameters.builder()
                .ownerClassIdentifier(classIdentifier)
                .methodName("authenticate")
                .returnType("boolean")
                .parameterTypes(Arrays.asList("java.lang.String", "java.lang.String"))
                .build()
        );

        // Create Java run configuration
        javaRunConfiguration = JavaRunConfiguration.builder()
                .applicationPath(Path.of("test-app.jar"))
                .sourceCodePath(Path.of("src/main/java"))
                .targetMethod(methodIdentifier)
                .outputDirectory(tempDir)
                .build();
    }

    @Test
    void givenJavaRunConfiguration_whenCreatingContextWithDefaults_thenSetsExpectedDefaults() {
        TestGenerationContext context = JavaTestGenerationContextFactory
                .createFromJavaRunConfiguration(javaRunConfiguration);

        assertNotNull(context);
        assertEquals("com.example.service.UserService.authenticate(String, String)",
                    context.getTargetMethod().getFullyQualifiedSignature());
        assertEquals("com.example.service.UserService", context.getTargetMethod().getFullyQualifiedClassName());
        assertEquals("com.example.service", context.getTargetMethod().getPackageName());
        assertEquals(tempDir, context.getOutputDirectory());
        
        // Verify default settings
        assertEquals("junit5", context.getTestFramework());
        assertEquals(Integer.MAX_VALUE, context.getMaxTestCount());
        assertTrue(context.isGenerateEdgeCases());
        assertFalse(context.isGenerateNegativeTests());
        assertEquals(TestNamingStrategy.DESCRIPTIVE, context.getNamingStrategy());
    }

    @Test
    void givenJavaRunConfigurationAndCustomSettings_whenCreatingContext_thenAppliesCustomSettings() {
        TestGenerationSettings customSettings = TestGenerationSettings.builder()
                .testFramework("junit4")
                .maxTestCount(25)
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .namingStrategy(TestNamingStrategy.SIMPLE)
                .build();

        TestGenerationContext context = JavaTestGenerationContextFactory
                .createFromJavaRunConfiguration(javaRunConfiguration, customSettings);

        assertNotNull(context);
        assertEquals("com.example.service.UserService.authenticate(String, String)",
                    context.getTargetMethod().getFullyQualifiedSignature());
        assertEquals("com.example.service.UserService", context.getTargetMethod().getFullyQualifiedClassName());
        assertEquals("com.example.service", context.getTargetMethod().getPackageName());
        
        // Verify custom settings
        assertEquals("junit4", context.getTestFramework());
        assertEquals(25, context.getMaxTestCount());
        assertFalse(context.isGenerateEdgeCases());
        assertFalse(context.isGenerateNegativeTests());
        assertEquals(TestNamingStrategy.SIMPLE, context.getNamingStrategy());
    }

    @Test
    void givenJavaRunConfiguration_whenCreatingForTraceBasedGeneration_thenReturnsValidContext() {
        TestGenerationContext context = JavaTestGenerationContextFactory
                .createForTraceBasedGeneration(javaRunConfiguration);

        assertNotNull(context);
        assertEquals("com.example.service.UserService.authenticate(String, String)",
                    context.getTargetMethod().getFullyQualifiedSignature());
        assertEquals("com.example.service.UserService", context.getTargetMethod().getFullyQualifiedClassName());
        assertEquals("com.example.service", context.getTargetMethod().getPackageName());
        
        // Verify trace-based settings
        assertEquals("junit5", context.getTestFramework());
        assertEquals(Integer.MAX_VALUE, context.getMaxTestCount());
        assertTrue(context.isGenerateEdgeCases());
        assertFalse(context.isGenerateNegativeTests()); // Trace data typically doesn't include error cases
        assertEquals(TestNamingStrategy.DESCRIPTIVE, context.getNamingStrategy());
        assertFalse(context.isGenerateParameterizedTests()); // Trace-based tests are usually scenario-specific
    }

    @Test
    void givenJavaRunConfiguration_whenCreatingForLLMBasedGeneration_thenReturnsValidContext() {
        TestGenerationContext context = JavaTestGenerationContextFactory
                .createForLLMBasedGeneration(javaRunConfiguration);

        assertNotNull(context);
        assertEquals("com.example.service.UserService.authenticate(String, String)",
                    context.getTargetMethod().getFullyQualifiedSignature());
        assertEquals("com.example.service.UserService", context.getTargetMethod().getFullyQualifiedClassName());
        assertEquals("com.example.service", context.getTargetMethod().getPackageName());
        
        // Verify LLM-based settings
        assertEquals("junit5", context.getTestFramework());
        assertEquals(Integer.MAX_VALUE, context.getMaxTestCount());
        assertTrue(context.isGenerateEdgeCases());
        assertFalse(context.isGenerateNegativeTests());
        assertEquals(TestNamingStrategy.DESCRIPTIVE, context.getNamingStrategy());
        assertTrue(context.isGenerateParameterizedTests());
        assertFalse(context.isIncludePerformanceAssertions());
    }

    @Test
    void givenNullMethod_whenCreatingContext_thenThrowsOnDependentGetters() {
        JavaRunConfiguration configWithNullMethod = JavaRunConfiguration.builder()
                .applicationPath(Path.of("test-app.jar"))
                .sourceCodePath(Path.of("src/main/java"))
                .targetMethod(null)
                .outputDirectory(tempDir)
                .build();

        TestGenerationContext context = JavaTestGenerationContextFactory
                .createFromJavaRunConfiguration(configWithNullMethod);

        assertNotNull(context);
        assertThrows(IllegalStateException.class, context::getTargetMethodSignature);
        assertThrows(IllegalStateException.class, context::getTargetClassName);
        assertThrows(IllegalStateException.class, context::getPackageName);
    }

    @Test
    void givenNullRunConfiguration_whenCreatingContext_thenThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> 
            JavaTestGenerationContextFactory.createFromJavaRunConfiguration(null));
    }
}
