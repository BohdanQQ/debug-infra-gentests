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
 * Integration test for JavaRunConfiguration TestGenerationContext creation methods.
 * This test verifies that the RunConfiguration can successfully create TestGenerationContext
 * when the test-generator modules are available on the classpath.
 */
class JavaRunConfigurationIntegrationTest {

    @TempDir
    Path tempDir;

    private JavaRunConfiguration javaRunConfiguration;

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
        
        JavaMethodIdentifier methodIdentifier = new JavaMethodIdentifier(
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
    void givenModulesAvailable_whenCreatingTestGenerationContext_thenReturnsValidContext() {
        // When test-generator-java module is available, should successfully create context
        Object contextObj = javaRunConfiguration.createTestGenerationContext();
        
        assertNotNull(contextObj);
        assertTrue(contextObj instanceof TestGenerationContext);
        
        TestGenerationContext context = (TestGenerationContext) contextObj;
        
        // Verify the context has the correct information extracted from JavaMethodIdentifier
        assertEquals("com.example.service.UserService.authenticate(String, String)", 
                    context.getTargetMethodSignature());
        assertEquals("com.example.service.UserService", context.getTargetClassName());
        assertEquals("com.example.service", context.getPackageName());
        assertEquals(tempDir, context.getOutputDirectory());
        
        // Verify default settings
        assertEquals("junit5", context.getTestFramework());
        assertEquals(Integer.MAX_VALUE, context.getMaxTestCount());
        assertTrue(context.isGenerateEdgeCases());
        assertFalse(context.isGenerateNegativeTests());
        assertEquals(TestNamingStrategy.DESCRIPTIVE, context.getNamingStrategy());
    }

    @Test
    void givenCustomSettings_whenCreatingTestGenerationContext_thenAppliesCustomConfiguration() {
        // Test creating context with custom settings
        TestGenerationSettings customSettings = TestGenerationSettings.builder()
                .testFramework("junit4")
                .maxTestCount(25)
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .namingStrategy(TestNamingStrategy.SIMPLE)
                .build();

        Object contextObj = javaRunConfiguration.createTestGenerationContext(customSettings);
        
        assertNotNull(contextObj);
        assertTrue(contextObj instanceof TestGenerationContext);
        
        TestGenerationContext context = (TestGenerationContext) contextObj;
        
        // Verify the context has the correct information
        assertEquals("com.example.service.UserService.authenticate(String, String)", 
                    context.getTargetMethodSignature());
        assertEquals("com.example.service.UserService", context.getTargetClassName());
        assertEquals("com.example.service", context.getPackageName());
        
        // Verify custom settings were applied
        assertEquals("junit4", context.getTestFramework());
        assertEquals(25, context.getMaxTestCount());
        assertFalse(context.isGenerateEdgeCases());
        assertFalse(context.isGenerateNegativeTests());
        assertEquals(TestNamingStrategy.SIMPLE, context.getNamingStrategy());
    }

    @Test
    void givenNullMethod_whenCreatingTestGenerationContext_thenHandlesGracefully() {
        // Test behavior when target method is null
        JavaRunConfiguration configWithNullMethod = JavaRunConfiguration.builder()
                .applicationPath(Path.of("test-app.jar"))
                .sourceCodePath(Path.of("src/main/java"))
                .targetMethod(null)
                .outputDirectory(tempDir)
                .build();

        Object contextObj = configWithNullMethod.createTestGenerationContext();
        
        assertNotNull(contextObj);
        assertInstanceOf(TestGenerationContext.class, contextObj);
        
        TestGenerationContext context = (TestGenerationContext) contextObj;
        
        // Since targetMethod is null, getters depending on it should throw
        assertThrows(IllegalStateException.class, context::getTargetMethodSignature);
        assertThrows(IllegalStateException.class, context::getTargetClassName);
        assertThrows(IllegalStateException.class, context::getPackageName);
    }

    @Test
    void givenReflectionBasedApproach_whenCreatingTestGenerationContext_thenWorksCorrectly() {
        // Test that the reflection-based approach works correctly when modules are available
        Object contextObj = javaRunConfiguration.createTestGenerationContext();
        
        assertNotNull(contextObj);
        
        // Verify the context has the expected type
        assertEquals("TestGenerationContext", contextObj.getClass().getSimpleName());
        
        // Verify we can call methods on the context
        assertNotNull(contextObj.toString());
        
        // Cast and verify specific functionality
        TestGenerationContext context = (TestGenerationContext) contextObj;
        assertNotNull(context.getTargetMethodSignature());
        assertNotNull(context.getTargetClassName());
        assertNotNull(context.getPackageName());
        assertNotNull(context.getOutputDirectory());
    }
}
