package cz.cuni.mff.d3s.autodebugger.runner.factories;

import cz.cuni.mff.d3s.autodebugger.instrumentor.common.modelling.InstrumentationModel;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.DiSLModel;
import cz.cuni.mff.d3s.autodebugger.model.common.RunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.common.TargetLanguage;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for InstrumentationModelFactory functionality.
 * Tests factory dispatch logic, field propagation from configuration to model,
 * and edge case handling for instrumentation model creation.
 * 
 * <p>All tests are CI-safe and do not require DiSL installation.
 */
class InstrumentationModelFactoryTest {

    private JavaMethodIdentifier testMethod;
    private JavaArgumentIdentifier testArgument;
    private JavaFieldIdentifier testField;

    @BeforeEach
    void setUp() {
        // Create reusable test fixtures
        JavaClassIdentifier testClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("TestClass")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        testMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(testClass)
                        .methodName("testMethod")
                        .returnType("void")
                        .parameterTypes(List.of("int"))
                        .build());

        testArgument = createArgumentIdentifier(0, "int");
        testField = createFieldIdentifier("testField", "java.lang.String", testClass);
    }

    // ========================================
    // A. Unit Tests for Factory Logic
    // ========================================

    @Nested
    class FactoryLogicTests {

        @Test
        void givenJavaRunConfiguration_whenBuildingModel_thenReturnsDiSLModel() {
            // given
            JavaRunConfiguration config = createMinimalJavaConfig();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);

            // then
            assertNotNull(model);
            assertInstanceOf(DiSLModel.class, model);
        }

        @Test
        void givenNonJavaLanguage_whenBuildingModel_thenThrowsIllegalArgumentException() {
            // given
            RunConfiguration config = createMockNonJavaLanguageConfig();

            // when/then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
                InstrumentationModelFactory.buildInstrumentationModel(config);
            });

            assertTrue(exception.getMessage().contains("Unsupported language"));
        }

        @Test
        void givenNonJavaRunConfigurationInstance_whenBuildingModel_thenThrowsIllegalArgumentException() {
            // given
            RunConfiguration config = createMockJavaLanguageNonJavaConfig();

            // when/then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
                InstrumentationModelFactory.buildInstrumentationModel(config);
            });

            assertTrue(exception.getMessage().contains("Expected JavaRunConfiguration"));
        }

        @Test
        void givenJavaConfigWithNoExportableValues_whenBuildingModel_thenCreatesModelSuccessfully() {
            // given
            JavaRunConfiguration config = JavaRunConfiguration.builder()
                    .applicationPath(Path.of("test.jar"))
                    .sourceCodePath(Path.of("src"))
                    .targetMethod(testMethod)
                    .build();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);

            // then
            assertNotNull(model);
            assertInstanceOf(DiSLModel.class, model);
        }

        @Test
        void givenJavaConfigWithMultipleExportableValues_whenBuildingModel_thenCreatesModelSuccessfully() {
            // given
            JavaRunConfiguration config = JavaRunConfiguration.builder()
                    .applicationPath(Path.of("test.jar"))
                    .sourceCodePath(Path.of("src"))
                    .targetMethod(testMethod)
                    .exportableValue(testArgument)
                    .exportableValue(testField)
                    .build();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);

            // then
            assertNotNull(model);
            assertInstanceOf(DiSLModel.class, model);
        }
    }

    // ========================================
    // B. Contract Tests - Field Propagation
    // ========================================

    @Nested
    class FieldPropagationTests {

        @Test
        void givenConfigWithTargetMethod_whenBuildingModel_thenModelContainsCorrectTargetMethod() {
            // given
            JavaRunConfiguration config = createJavaConfigWithValues();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);

            // then
            assertInstanceOf(DiSLModel.class, model);
            DiSLModel dislModel = (DiSLModel) model;
            assertEquals(testMethod, dislModel.getTargetMethod());
        }

        @Test
        void givenConfigWithSingleArgument_whenBuildingModel_thenGeneratedCodeContainsArgumentRetrieval() {
            // given
            JavaRunConfiguration config = JavaRunConfiguration.builder()
                    .applicationPath(Path.of("test.jar"))
                    .sourceCodePath(Path.of("src"))
                    .targetMethod(testMethod)
                    .exportableValue(testArgument)
                    .build();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);
            String generatedCode = model.transform();

            // then
            assertNotNull(generatedCode);
            assertTrue(generatedCode.contains("di.getMethodArgumentValue(0, int.class)"),
                    "Generated code should contain argument retrieval for slot 0");
        }

        @Test
        void givenConfigWithSingleField_whenBuildingModel_thenGeneratedCodeContainsFieldRetrieval() {
            // given
            JavaRunConfiguration config = JavaRunConfiguration.builder()
                    .applicationPath(Path.of("test.jar"))
                    .sourceCodePath(Path.of("src"))
                    .targetMethod(testMethod)
                    .exportableValue(testField)
                    .build();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);
            String generatedCode = model.transform();

            // then
            assertNotNull(generatedCode);
            assertTrue(generatedCode.contains("di.getInstanceFieldValue"),
                    "Generated code should contain field retrieval");
            assertTrue(generatedCode.contains("\"testField\""),
                    "Generated code should reference the field name");
        }

        @Test
        void givenConfigWithMultipleExportableValues_whenBuildingModel_thenGeneratedCodeContainsAllRetrievals() {
            // given
            JavaRunConfiguration config = JavaRunConfiguration.builder()
                    .applicationPath(Path.of("test.jar"))
                    .sourceCodePath(Path.of("src"))
                    .targetMethod(testMethod)
                    .exportableValue(testArgument)
                    .exportableValue(testField)
                    .build();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);
            String generatedCode = model.transform();

            // then
            assertNotNull(generatedCode);
            assertTrue(generatedCode.contains("di.getMethodArgumentValue(0, int.class)"),
                    "Generated code should contain argument retrieval");
            assertTrue(generatedCode.contains("di.getInstanceFieldValue"),
                    "Generated code should contain field retrieval");
        }

        @Test
        void givenConfigWithTargetMethodScope_whenBuildingModel_thenGeneratedCodeContainsCorrectScope() {
            // given
            JavaRunConfiguration config = createJavaConfigWithValues();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);
            String generatedCode = model.transform();

            // then
            assertNotNull(generatedCode);
            assertTrue(generatedCode.contains("scope = \"com.example.TestClass.testMethod(int)\""),
                    "Generated code should contain correct method scope");
        }
    }

    // ========================================
    // C. Edge Case Tests
    // ========================================

    @Nested
    class EdgeCaseTests {

        @Test
        void givenConfigWithReturnValueIdentifier_whenBuildingModel_thenCreatesModelSuccessfully() {
            // given
            JavaMethodIdentifier methodWithReturn = new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                            .ownerClassIdentifier(testMethod.getOwnerClassIdentifier())
                            .methodName("methodWithReturn")
                            .returnType("java.lang.String")
                            .build());

            JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                    new ReturnValueIdentifierParameters(methodWithReturn));

            JavaRunConfiguration config = JavaRunConfiguration.builder()
                    .applicationPath(Path.of("test.jar"))
                    .sourceCodePath(Path.of("src"))
                    .targetMethod(methodWithReturn)
                    .exportableValue(returnValue)
                    .build();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);

            // then
            assertNotNull(model);
            assertInstanceOf(DiSLModel.class, model);
        }

        @Test
        void givenConfigWithVariableIdentifier_whenBuildingModel_thenThrowsNotImplementedException() {
            // given
            JavaVariableIdentifier variable = new JavaVariableIdentifier(
                    VariableIdentifierParameters.builder()
                            .variableName("localVar")
                            .variableType("int")
                            .build());

            JavaRunConfiguration config = JavaRunConfiguration.builder()
                    .applicationPath(Path.of("test.jar"))
                    .sourceCodePath(Path.of("src"))
                    .targetMethod(testMethod)
                    .exportableValue(variable)
                    .build();

            // when/then
            // Note: Variable identifier support is not yet implemented in DiSL model
            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
                InstrumentationModelFactory.buildInstrumentationModel(config);
            });

            assertTrue(exception.getMessage().contains("Not implemented"));
        }

        @Test
        void givenConfigWithNullExportableValues_whenBuildingModel_thenCreatesModelSuccessfully() {
            // given
            JavaRunConfiguration config = JavaRunConfiguration.builder()
                    .applicationPath(Path.of("test.jar"))
                    .sourceCodePath(Path.of("src"))
                    .targetMethod(testMethod)
                    .build();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);

            // then
            assertNotNull(model);
            assertInstanceOf(DiSLModel.class, model);
        }

        @Test
        void givenConfigWithComplexFieldType_whenBuildingModel_thenGeneratedCodeContainsImport() {
            // given
            JavaClassIdentifier ownerClass = new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .className("Repository")
                            .packageIdentifier(new JavaPackageIdentifier("com.example.data"))
                            .build());

            JavaFieldIdentifier complexField = createFieldIdentifier(
                    "userCache", "java.util.List", ownerClass);

            JavaMethodIdentifier methodInOwnerClass = new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                            .ownerClassIdentifier(ownerClass)
                            .methodName("clearCache")
                            .returnType("void")
                            .build());

            JavaRunConfiguration config = JavaRunConfiguration.builder()
                    .applicationPath(Path.of("test.jar"))
                    .sourceCodePath(Path.of("src"))
                    .targetMethod(methodInOwnerClass)
                    .exportableValue(complexField)
                    .build();

            // when
            InstrumentationModel model = InstrumentationModelFactory.buildInstrumentationModel(config);
            String generatedCode = model.transform();

            // then
            assertNotNull(generatedCode);
            assertTrue(generatedCode.contains("import com.example.data.Repository;"),
                    "Generated code should import the owner class");
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Creates a minimal JavaRunConfiguration with required fields only.
     */
    private JavaRunConfiguration createMinimalJavaConfig() {
        return JavaRunConfiguration.builder()
                .applicationPath(Path.of("test.jar"))
                .sourceCodePath(Path.of("src"))
                .targetMethod(testMethod)
                .build();
    }

    /**
     * Creates a JavaRunConfiguration with exportable values for testing.
     */
    private JavaRunConfiguration createJavaConfigWithValues() {
        return JavaRunConfiguration.builder()
                .applicationPath(Path.of("test.jar"))
                .sourceCodePath(Path.of("src"))
                .targetMethod(testMethod)
                .exportableValue(testArgument)
                .exportableValue(testField)
                .build();
    }

    /**
     * Creates a mock RunConfiguration with a non-Java language.
     */
    private RunConfiguration createMockNonJavaLanguageConfig() {
        return new RunConfiguration() {
            @Override
            public TargetLanguage getLanguage() {
                // Return a non-JAVA language by creating a mock
                // Since we can't create new enum values, we'll use reflection workaround
                // For testing purposes, we'll throw in the factory itself
                return null; // This will be handled by the factory
            }

            @Override
            public Path getApplicationPath() {
                return Path.of("test.exe");
            }

            @Override
            public Path getSourceCodePath() {
                return Path.of("src");
            }

            @Override
            public cz.cuni.mff.d3s.autodebugger.model.common.identifiers.MethodIdentifier getTargetMethod() {
                return testMethod;
            }

            @Override
            public List<? extends cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue> getExportableValues() {
                return List.of();
            }

            @Override
            public Path getOutputDirectory() {
                return Path.of("output");
            }

            @Override
            public List<String> getRuntimeArguments() {
                return List.of();
            }

            @Override
            public void validate() {
                // No-op for mock
            }
        };
    }

    /**
     * Creates a mock RunConfiguration that returns JAVA language but is not a JavaRunConfiguration instance.
     */
    private RunConfiguration createMockJavaLanguageNonJavaConfig() {
        return new RunConfiguration() {
            @Override
            public TargetLanguage getLanguage() {
                return TargetLanguage.JAVA;
            }

            @Override
            public Path getApplicationPath() {
                return Path.of("test.jar");
            }

            @Override
            public Path getSourceCodePath() {
                return Path.of("src");
            }

            @Override
            public cz.cuni.mff.d3s.autodebugger.model.common.identifiers.MethodIdentifier getTargetMethod() {
                return testMethod;
            }

            @Override
            public List<? extends cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue> getExportableValues() {
                return List.of();
            }

            @Override
            public Path getOutputDirectory() {
                return Path.of("output");
            }

            @Override
            public List<String> getRuntimeArguments() {
                return List.of();
            }

            @Override
            public void validate() {
                // No-op for mock
            }
        };
    }

    /**
     * Helper to create an ArgumentIdentifier with specified slot and type.
     */
    private JavaArgumentIdentifier createArgumentIdentifier(int slot, String type) {
        return new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(slot)
                        .variableType(type)
                        .build());
    }

    /**
     * Helper to create a FieldIdentifier with specified name, type, and owner class.
     */
    private JavaFieldIdentifier createFieldIdentifier(String name, String type, JavaClassIdentifier ownerClass) {
        return new JavaFieldIdentifier(
                FieldIdentifierParameters.builder()
                        .variableName(name)
                        .variableType(type)
                        .ownerClassIdentifier(ownerClass)
                        .build());
    }
}

