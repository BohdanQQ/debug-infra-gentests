package cz.cuni.mff.d3s.autodebugger.testgenerator.common;

import cz.cuni.mff.d3s.autodebugger.model.common.RunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.common.TargetLanguage;
import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.MethodIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for TestGenerationContextFactory.
 */
class TestGenerationContextFactoryTest {

    @TempDir
    Path tempDir;

    private RunConfiguration mockRunConfiguration;
    private MethodIdentifier mockMethodIdentifier;

    @BeforeEach
    void setUp() {
        // Create a mock method identifier
        mockMethodIdentifier = new MethodIdentifier("authenticate", "boolean",
                List.of("java.lang.String", "java.lang.String")) {
            @Override
            public String getClassName() {
                return "UserService";
            }

            @Override
            public String getPackageName() {
                return "";
            }

            @Override
            public String getFullyQualifiedClassName() {
                return "UserService";
            }

            @Override
            public String getFullyQualifiedSignature() {
                return "UserService.authenticate(String, String)";
            }

            @Override
            public String toString() {
                return "UserService.authenticate(String, String)";
            }
        };

        // Create mock run configuration
        mockRunConfiguration = new RunConfiguration() {
            @Override
            public TargetLanguage getLanguage() {
                return TargetLanguage.JAVA;
            }

            @Override
            public Path getApplicationPath() {
                return Path.of("test-app.jar");
            }

            @Override
            public Path getSourceCodePath() {
                return Path.of("src/main/java");
            }

            @Override
            public MethodIdentifier getTargetMethod() {
                return mockMethodIdentifier;
            }

            @Override
            public List getExportableValues() {
                return Collections.emptyList();
            }

            @Override
            public Path getOutputDirectory() {
                return tempDir;
            }

            @Override
            public List<String> getRuntimeArguments() {
                return Collections.emptyList();
            }

            @Override
            public void validate() {
                // Mock implementation - no validation needed
            }
        };
    }

    @Test
    void givenRunConfiguration_whenCreatingContextWithDefaults_thenSetsExpectedDefaults() {
        TestGenerationContext context = TestGenerationContextFactory
                .createFromRunConfiguration(mockRunConfiguration);

        assertNotNull(context);
        assertEquals("UserService.authenticate(String, String)",
                    context.getTargetMethod().getFullyQualifiedSignature());
        assertEquals("UserService", context.getTargetMethod().getFullyQualifiedClassName());
        assertEquals("", context.getTargetMethod().getPackageName());
        assertEquals(tempDir, context.getOutputDirectory());

        // Verify default settings
        assertEquals("junit5", context.getTestFramework());
        assertEquals(Integer.MAX_VALUE, context.getMaxTestCount());
        assertTrue(context.isGenerateEdgeCases());
        assertFalse(context.isGenerateNegativeTests());
        assertEquals(TestNamingStrategy.DESCRIPTIVE, context.getNamingStrategy());
    }

    @Test
    void givenRunConfigurationAndCustomSettings_whenCreatingContext_thenAppliesCustomSettings() {
        TestGenerationSettings customSettings = TestGenerationSettings.builder()
                .testFramework("junit4")
                .maxTestCount(25)
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .namingStrategy(TestNamingStrategy.SIMPLE)
                .build();

        TestGenerationContext context = TestGenerationContextFactory
                .createFromRunConfiguration(mockRunConfiguration, customSettings);

        assertNotNull(context);
        assertEquals("UserService.authenticate(String, String)",
                    context.getTargetMethod().getFullyQualifiedSignature());
        assertEquals("UserService", context.getTargetMethod().getFullyQualifiedClassName());
        assertEquals("", context.getTargetMethod().getPackageName());

        // Verify custom settings
        assertEquals("junit4", context.getTestFramework());
        assertEquals(25, context.getMaxTestCount());
        assertFalse(context.isGenerateEdgeCases());
        assertFalse(context.isGenerateNegativeTests());
        assertEquals(TestNamingStrategy.SIMPLE, context.getNamingStrategy());
    }

    @Test
    void givenNullMethod_whenCreatingContext_thenThrowsOnDependentGetters() {
        RunConfiguration configWithNullMethod = new RunConfiguration() {
            @Override
            public TargetLanguage getLanguage() {
                return TargetLanguage.JAVA;
            }

            @Override
            public Path getApplicationPath() {
                return Path.of("test-app.jar");
            }

            @Override
            public Path getSourceCodePath() {
                return Path.of("src/main/java");
            }

            @Override
            public MethodIdentifier getTargetMethod() {
                return null;
            }

            @Override
            public List getExportableValues() {
                return Collections.emptyList();
            }

            @Override
            public Path getOutputDirectory() {
                return tempDir;
            }

            @Override
            public List<String> getRuntimeArguments() {
                return Collections.emptyList();
            }

            @Override
            public void validate() {
                // Mock implementation
            }
        };

        TestGenerationContext context = TestGenerationContextFactory
                .createFromRunConfiguration(configWithNullMethod);

        assertNotNull(context);
        assertThrows(IllegalStateException.class, context::getTargetMethodSignature);
        assertThrows(IllegalStateException.class, context::getTargetClassName);
        assertThrows(IllegalStateException.class, context::getPackageName);
    }

    @Test
    void givenMethodIdentifier_whenCreatingMinimalContext_thenSetsRequiredFields() {
        MethodIdentifier methodIdentifier = new MethodIdentifier("add", "int", List.of("int", "int")) {
            @Override public String getClassName() { return "Calculator"; }
            @Override public String getPackageName() { return ""; }
            @Override public String getFullyQualifiedClassName() { return "Calculator"; }
            @Override public String getFullyQualifiedSignature() { return "Calculator.add(int, int)"; }
        };

        TestGenerationContext context = TestGenerationContextFactory
                .createMinimal(methodIdentifier, tempDir);

        assertNotNull(context);
        assertEquals("Calculator.add(int, int)", context.getTargetMethod().getFullyQualifiedSignature());
        assertEquals(tempDir, context.getOutputDirectory());
    }

    @Test
    void givenNullRunConfiguration_whenCreatingContext_thenThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
            TestGenerationContextFactory.createFromRunConfiguration(null));
    }

    @Test
    void givenNullSettings_whenCreatingContext_thenUsesDefaultSettings() {
        // Should use default settings when null is passed
        TestGenerationContext context = TestGenerationContextFactory
                .createFromRunConfiguration(mockRunConfiguration, null);

        assertNotNull(context);
        assertEquals("junit5", context.getTestFramework()); // Default value
        assertEquals(Integer.MAX_VALUE, context.getMaxTestCount()); // Default value
}

    @Test
    void givenStrings_whenCreateFromStrings_withEmptyPackage_thenBuildsDefaultClassNameAndSignature() {
        var ctx = TestGenerationContextFactory.createFromStrings(
            "", "Calculator", "divide(int, int)", "int", tempDir);
        assertNotNull(ctx);
        assertEquals("Calculator", ctx.getTargetClassName());
        assertEquals("Calculator.divide(int, int)", ctx.getTargetMethodSignature());
        assertEquals("", ctx.getPackageName());
    }

    @Test
    void givenStrings_whenCreateFromStrings_withNoParams_thenParsesMethodNameOnly() {
        var ctx = TestGenerationContextFactory.createFromStrings(
            "com.example", "Util", "now()", "long", tempDir);
        assertNotNull(ctx);
        assertEquals("com.example.Util", ctx.getTargetMethod().getFullyQualifiedClassName());
        assertEquals("com.example", ctx.getPackageName());
        assertEquals("com.example.Util.now()", ctx.getTargetMethodSignature());
    }

    @Test
    void givenStrings_whenCreateFromStrings_withSpacing_thenTrimsParameterTypes() {
        var ctx = TestGenerationContextFactory.createFromStrings(
            "a.b", "C", "m( int ,  java.lang.String  ,double)", "void", tempDir);
        assertNotNull(ctx);
        assertEquals("a.b.C.m(int, java.lang.String, double)", ctx.getTargetMethodSignature());
    }


}
