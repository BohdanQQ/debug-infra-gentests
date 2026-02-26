package cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling;

import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.Constants.normalizeVariableNames;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DiSLModel class.
 * Tests model construction, code generation, and import handling
 * for various instrumentation scenarios.
 */
class DiSLModelTests {

    /**
     * Test Case 1.1: Instrument a single primitive parameter.
     * Target Method: public void process(int count) in class com.example.Processor
     * Exportable Value: The int count parameter (slot 0)
     */
    @Test
    void givenSinglePrimitiveParameter_whenConstructingModel_thenInstrumentationIsGenerated() {
        // given
        JavaClassIdentifier processorClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("Processor")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(processorClass)
                        .methodName("process")
                        .returnType("void")
                        .parameterTypes(List.of("int"))
                        .build());

        JavaArgumentIdentifier intArgument = new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("int")
                        .build());

        List<JavaValueIdentifier> exportedValues = List.of(intArgument);

        // when
        DiSLModel model = new DiSLModel(targetMethod, exportedValues);
        String generatedCode = model.transform();

        // then
        assertNotNull(model);
        assertNotNull(generatedCode);

        // Verify the generated code contains expected elements
        assertTrue(generatedCode.contains("public class DiSLClass"), "Should contain class declaration");
        assertTrue(generatedCode.contains("@Before(marker = BodyMarker.class, scope = \"com.example.Processor.process(int)\")"), "Should contain @Before annotation with correct scope");
        assertTrue(generatedCode.contains("@After(marker = BodyMarker.class, scope = \"com.example.Processor.process(int)\")"), "Should contain @After annotation with correct scope");
        assertTrue(generatedCode.contains("public static void"), "Should contain static method");
        assertTrue(generatedCode.contains("DynamicContext di"), "Should contain DynamicContext parameter");

        // Verify argument retrieval code
        String normalizedCode = normalizeVariableNames(generatedCode);
        assertTrue(normalizedCode.contains("int generatedVariableX = di.getMethodArgumentValue(0, int.class);"), "Should contain argument retrieval");

        // Verify collector call
        assertTrue(normalizedCode.contains("CollectorRE.collectInt(" + intArgument.getInternalId() + ", generatedVariableX);"), "Should contain collector call");
    }

    /**
     * Test Case 2.1: Instrument multiple parameters and fields.
     * Target Class: com.example.service.TaskRunner with fields taskCounter (int) and currentStatus (String)
     * Target Method: public boolean execute(String taskName, int priority)
     * Exportable Values: Both parameters and both fields
     */
    @Test
    void givenMultipleParametersAndFields_whenConstructingModel_thenAllValuesAreInstrumented() {
        // given
        JavaClassIdentifier taskRunnerClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("TaskRunner")
                        .packageIdentifier(new JavaPackageIdentifier("com.example.service"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(taskRunnerClass)
                        .methodName("execute")
                        .returnType("boolean")
                        .parameterTypes(List.of("java.lang.String", "int"))
                        .build());

        // Create exportable values
        JavaArgumentIdentifier taskNameArg = new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("java.lang.String")
                        .build());

        JavaArgumentIdentifier priorityArg = new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(1)
                        .variableType("int")
                        .build());

        JavaFieldIdentifier taskCounterField = new JavaFieldIdentifier(
                FieldIdentifierParameters.builder()
                        .variableName("taskCounter")
                        .variableType("int")
                        .ownerClassIdentifier(taskRunnerClass)
                        .build());

        JavaFieldIdentifier currentStatusField = new JavaFieldIdentifier(
                FieldIdentifierParameters.builder()
                        .variableName("currentStatus")
                        .variableType("java.lang.String")
                        .ownerClassIdentifier(taskRunnerClass)
                        .build());

        List<JavaValueIdentifier> exportedValues = List.of(
                taskNameArg, priorityArg, taskCounterField, currentStatusField);

        // when
        DiSLModel model = new DiSLModel(targetMethod, exportedValues);
        String generatedCode = model.transform();

        // then
        assertNotNull(model);
        assertNotNull(generatedCode);

        String normalizedCode = normalizeVariableNames(generatedCode);

        // Verify parameter retrieval statements
        assertTrue(normalizedCode.contains("java.lang.String generatedVariableX = di.getMethodArgumentValue(0, java.lang.String.class);"));
        assertTrue(normalizedCode.contains("int generatedVariableX = di.getMethodArgumentValue(1, int.class);"));

        // Verify field retrieval statements
        assertTrue(normalizedCode.contains("int generatedVariableX = di.getInstanceFieldValue(di.getThis(), TaskRunner.class, \"taskCounter\", int.class);"));
        assertTrue(normalizedCode.contains("java.lang.String generatedVariableX = di.getInstanceFieldValue(di.getThis(), TaskRunner.class, \"currentStatus\", java.lang.String.class);"));

        // Verify collector calls
        assertTrue(normalizedCode.contains("CollectorRE.collectString(" + taskNameArg.getInternalId() + ", generatedVariableX);"));
        assertTrue(normalizedCode.contains("CollectorRE.collectInt(" + priorityArg.getInternalId() + ", generatedVariableX);"));
        assertTrue(normalizedCode.contains("CollectorRE.collectInt(" + taskCounterField.getInternalId() + ", generatedVariableX);"));
        assertTrue(normalizedCode.contains("CollectorRE.collectString(" + currentStatusField.getInternalId() + ", generatedVariableX);"));
    }

    /**
     * Test Case 3.1: Instrument a field with a type from an external package (java.util.List).
     * Target Class: com.example.data.Repository with field userCache (java.util.List<String>)
     * Target Method: public void clearCache()
     * Exportable Value: The java.util.List<String> userCache field
     * Note: The current implementation imports the owner class, not the field type
     */
    @Test
    void givenFieldFromExternalPackage_whenConstructingModel_thenOwnerClassIsImported() {
        // given
        JavaClassIdentifier repositoryClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("Repository")
                        .packageIdentifier(new JavaPackageIdentifier("com.example.data"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(repositoryClass)
                        .methodName("clearCache")
                        .returnType("void")
                        .build());

        JavaFieldIdentifier userCacheField = new JavaFieldIdentifier(
                FieldIdentifierParameters.builder()
                        .variableName("userCache")
                        .variableType("java.util.List")
                        .ownerClassIdentifier(repositoryClass)
                        .build());

        List<JavaValueIdentifier> exportedValues = List.of(userCacheField);

        // when
        DiSLModel model = new DiSLModel(targetMethod, exportedValues);
        String generatedCode = model.transform();

        // then
        assertNotNull(model);
        assertNotNull(generatedCode);

        // Verify an import statement for owner class is included
        assertTrue(generatedCode.contains("import com.example.data.Repository;"));

        // Verify field retrieval uses correct type
        String normalizedCode = normalizeVariableNames(generatedCode);
        assertTrue(normalizedCode.contains("java.util.List generatedVariableX = di.getInstanceFieldValue(di.getThis(), Repository.class, \"userCache\", java.util.List.class);"));

        // Verify collector call for an object type
        assertTrue(normalizedCode.contains("CollectorRE.collectObject(" + userCacheField.getInternalId() + ", generatedVariableX);"));
    }

    /**
     * Test Case 3.2: Instrument a field with a custom type from another package within the same project.
     * Target Class: com.example.service.UserService with field currentUser (com.example.model.User)
     * Target Method: public void login(com.example.model.User user)
     * Exportable Value: The com.example.model.User currentUser field
     * Note: The current implementation imports the owner class, not the field type
     */
    @Test
    void givenFieldFromCustomPackage_whenConstructingModel_thenOwnerClassIsImported() {
        // given
        JavaClassIdentifier userServiceClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("UserService")
                        .packageIdentifier(new JavaPackageIdentifier("com.example.service"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(userServiceClass)
                        .methodName("login")
                        .returnType("void")
                        .parameterTypes(List.of("com.example.model.User"))
                        .build());

        JavaFieldIdentifier currentUserField = new JavaFieldIdentifier(
                FieldIdentifierParameters.builder()
                        .variableName("currentUser")
                        .variableType("com.example.model.User")
                        .ownerClassIdentifier(userServiceClass)
                        .build());

        List<JavaValueIdentifier> exportedValues = List.of(currentUserField);

        // when
        DiSLModel model = new DiSLModel(targetMethod, exportedValues);
        String generatedCode = model.transform();

        // then
        assertNotNull(model);
        assertNotNull(generatedCode);

        // Verify an import statement for owner class is included
        assertTrue(generatedCode.contains("import com.example.service.UserService;"));

        // Verify field retrieval uses correct type
        String normalizedCode = normalizeVariableNames(generatedCode);
        assertTrue(normalizedCode.contains("com.example.model.User generatedVariableX = di.getInstanceFieldValue(di.getThis(), UserService.class, \"currentUser\", com.example.model.User.class);"));
    }

    /**
     * Test Case 4.1: Generate instrumentation with an @AfterReturning annotation to capture a return value.
     * Target Method: public String formatData() in class com.example.Formatter
     * Exportable Value: The method's return value
     * Activation Time: ActivationTime.AFTER_RETURNING (auto-selected when return value is exported)
     */
    @Test
    void givenReturnValueIdentifier_whenConstructingModel_thenAfterReturningInstrumentationIsGenerated() {
        // given
        JavaClassIdentifier formatterClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("Formatter")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(formatterClass)
                        .methodName("formatData")
                        .returnType("java.lang.String")
                        .build());

        // Create return value identifier
        JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                new ReturnValueIdentifierParameters(targetMethod));

        List<JavaValueIdentifier> exportedValues = List.of(returnValue);

        // when
        DiSLModel model = new DiSLModel(targetMethod, exportedValues);
        String generatedCode = model.transform();

        // then
        assertNotNull(model);
        assertNotNull(generatedCode);

        // Verify the model was constructed successfully
        assertTrue(generatedCode.contains("public class DiSLClass"));
        assertTrue(generatedCode.contains("public static void"));
        assertTrue(generatedCode.contains("DynamicContext di"));

        // Verify scope is correct
        assertTrue(generatedCode.contains("scope = \"com.example.Formatter.formatData\""));

        // NEW: Verify @AfterReturning is used (not @Before or @After)
        assertTrue(generatedCode.contains("@AfterReturning"), "Should use @AfterReturning for return value capture");
        assertFalse(generatedCode.contains("@Before"), "Should not have @Before when only return value is captured");
        assertFalse(generatedCode.contains("@After("), "Should not have @After when capturing return value");

        // Verify AfterReturning import is present
        assertTrue(generatedCode.contains("import ch.usi.dag.disl.annotation.AfterReturning;"));

        // Verify return value capture code
        assertTrue(generatedCode.contains("di.getStackValue(0, java.lang.String.class)"));
    }

    /**
     * Test Case 4.2: Generate two instrumentation methods for @Before and @After to capture state change.
     * Target Method: public void updateState() in class com.example.StateManager
     * Exportable Value: A field private int stateValue
     * Activation Times: Both ActivationTime.BEFORE and ActivationTime.AFTER
     */
    @Test
    void givenFieldIdentifier_whenConstructingModel_thenBeforeAndAfterInstrumentationIsGenerated() {
        // given
        JavaClassIdentifier stateManagerClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("StateManager")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(stateManagerClass)
                        .methodName("updateState")
                        .returnType("void")
                        .build());

        JavaFieldIdentifier stateValueField = new JavaFieldIdentifier(
                FieldIdentifierParameters.builder()
                        .variableName("stateValue")
                        .variableType("int")
                        .ownerClassIdentifier(stateManagerClass)
                        .build());

        List<JavaValueIdentifier> exportedValues = List.of(stateValueField);

        // when
        DiSLModel model = new DiSLModel(targetMethod, exportedValues);
        String generatedCode = model.transform();

        // then
        assertNotNull(model);
        assertNotNull(generatedCode);

        // Verify the generated code contains both @Before and @After annotations
        assertTrue(generatedCode.contains("@Before(marker = BodyMarker.class, scope = \"com.example.StateManager.updateState\")"));
        assertTrue(generatedCode.contains("@After(marker = BodyMarker.class, scope = \"com.example.StateManager.updateState\")"));

        // Count the number of static methods (should be 2: one for @Before, one for @After)
        long methodCount = generatedCode.lines()
                .filter(line -> line.trim().startsWith("public static void"))
                .count();
        assertEquals(2, methodCount, "Should generate exactly two instrumentation methods");

        // Verify both methods contain the field access logic
        String normalizedCode = normalizeVariableNames(generatedCode);
        long fieldAccessCount = normalizedCode.lines()
                .filter(line -> line.contains("di.getInstanceFieldValue(di.getThis(), StateManager.class, \"stateValue\", int.class)"))
                .count();
        assertEquals(2, fieldAccessCount, "Both methods should contain field access logic");

        // Verify both methods contain collector calls
        long collectorCallCount = normalizedCode.lines()
                .filter(line -> line.contains("CollectorRE.collectInt(" + stateValueField.getInternalId() + ", generatedVariableX)"))
                .count();
        assertEquals(2, collectorCallCount, "Both methods should contain collector calls");
    }

    /**
     * Test that DiSLModel correctly handles an empty exportable values list.
     * With no values to export, no instrumentation methods should be generated
     * (there's nothing to capture, so no hooks are needed).
     */
    @Test
    void givenEmptyExportableValues_whenConstructingModel_thenNoInstrumentationMethodsGenerated() {
        // given
        JavaClassIdentifier testClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("TestClass")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(testClass)
                        .methodName("testMethod")
                        .returnType("void")
                        .build());

        List<JavaValueIdentifier> exportedValues = List.of(); // Empty list

        // when
        DiSLModel model = new DiSLModel(targetMethod, exportedValues);
        String generatedCode = model.transform();

        // then
        assertNotNull(model);
        assertNotNull(generatedCode);

        // Verify basic class structure is still generated
        assertTrue(generatedCode.contains("public class DiSLClass"));

        // No instrumentation methods should be generated when there are no exports
        assertFalse(generatedCode.contains("@Before"));
        assertFalse(generatedCode.contains("@After"));

        // Should not contain any value collection code
        assertFalse(generatedCode.contains("di.getMethodArgumentValue"));
        assertFalse(generatedCode.contains("di.getInstanceFieldValue"));
        assertFalse(generatedCode.contains("CollectorRE.collect"));
    }

    // ===============================
    // Return Value Partitioning Tests
    // ===============================

    /**
     * Test that when only return value is exported, only @AfterReturning is generated (no @Before).
     */
    @Test
    void givenOnlyReturnValue_whenConstructingModel_thenOnlyAfterReturningGenerated() {
        // given
        JavaClassIdentifier testClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("Calculator")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(testClass)
                        .methodName("calculate")
                        .returnType("int")
                        .build());

        JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                new ReturnValueIdentifierParameters(targetMethod));

        // when
        DiSLModel model = new DiSLModel(targetMethod, List.of(returnValue));
        String generatedCode = model.transform();

        // then
        assertNotNull(generatedCode);

        // Should have @AfterReturning, not @Before or @After
        assertTrue(generatedCode.contains("@AfterReturning"));
        assertFalse(generatedCode.contains("@Before"));
        assertFalse(generatedCode.contains("@After("));

        // Should have AfterReturning import
        assertTrue(generatedCode.contains("import ch.usi.dag.disl.annotation.AfterReturning;"));

        // Should capture return value using getStackValue
        String normalizedCode = normalizeVariableNames(generatedCode);
        assertTrue(normalizedCode.contains("di.getStackValue(0, int.class)"));
    }

    /**
     * Test that arguments go to @Before, return values go to @AfterReturning.
     */
    @Test
    void givenArgumentsAndReturnValue_whenConstructingModel_thenCorrectPartitioning() {
        // given
        JavaClassIdentifier testClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("Calculator")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(testClass)
                        .methodName("add")
                        .returnType("int")
                        .parameterTypes(List.of("int", "int"))
                        .build());

        JavaArgumentIdentifier arg1 = new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("int")
                        .build());

        JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                new ReturnValueIdentifierParameters(targetMethod));

        // when
        DiSLModel model = new DiSLModel(targetMethod, List.of(arg1, returnValue));
        String generatedCode = model.transform();

        // then
        assertNotNull(generatedCode);

        // Should have both @Before and @AfterReturning
        assertTrue(generatedCode.contains("@Before"));
        assertTrue(generatedCode.contains("@AfterReturning"));

        // @Before should contain argument capture
        assertTrue(generatedCode.contains("di.getMethodArgumentValue"));

        // @AfterReturning should contain return value capture
        assertTrue(generatedCode.contains("di.getStackValue(0, int.class)"));
    }

    /**
     * Test that void return values are silently skipped.
     */
    @Test
    void givenVoidReturnValue_whenConstructingModel_thenReturnValueSkipped() {
        // given
        JavaClassIdentifier testClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("Processor")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(testClass)
                        .methodName("process")
                        .returnType("void")  // void method
                        .build());

        // Attempt to export return value of void method
        JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                new ReturnValueIdentifierParameters(targetMethod));

        // when
        DiSLModel model = new DiSLModel(targetMethod, List.of(returnValue));
        String generatedCode = model.transform();

        // then
        assertNotNull(generatedCode);

        // Should not contain any return value capture code
        assertFalse(generatedCode.contains("di.getStackValue"));

        // Should not have @AfterReturning (no return values to capture)
        assertFalse(generatedCode.contains("@AfterReturning"));
        assertFalse(generatedCode.contains("@After"));
        assertFalse(generatedCode.contains("@Before"));
    }

    /**
     * Test that void return value with arguments still captures arguments.
     */
    @Test
    void givenVoidReturnValueAndArgument_whenConstructingModel_thenArgumentCapturedReturnValueSkipped() {
        // given
        JavaClassIdentifier testClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("Processor")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(testClass)
                        .methodName("process")
                        .returnType("void")
                        .parameterTypes(List.of("int"))
                        .build());

        JavaArgumentIdentifier arg = new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("int")
                        .build());

        JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                new ReturnValueIdentifierParameters(targetMethod));

        // when
        DiSLModel model = new DiSLModel(targetMethod, List.of(arg, returnValue));
        String generatedCode = model.transform();

        // then
        assertNotNull(generatedCode);

        // Should have argument capture
        assertTrue(generatedCode.contains("di.getMethodArgumentValue"));
        assertTrue(generatedCode.contains("@Before"));
        assertTrue(generatedCode.contains("@After"));  // Fields/args go in @After for state tracking

        // Should NOT have return value capture (void method)
        assertFalse(generatedCode.contains("di.getStackValue"));
        assertFalse(generatedCode.contains("@AfterReturning"));
    }

    /**
     * Test that fields go in both @Before and @After for state change tracking.
     */
    @Test
    void givenOnlyField_whenConstructingModel_thenFieldInBothHooks() {
        // given
        JavaClassIdentifier testClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("Counter")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(testClass)
                        .methodName("increment")
                        .returnType("void")
                        .build());

        JavaFieldIdentifier field = new JavaFieldIdentifier(
                FieldIdentifierParameters.builder()
                        .ownerClassIdentifier(testClass)
                        .variableName("count")
                        .variableType("int")
                        .isStatic(false)
                        .build());

        // when
        DiSLModel model = new DiSLModel(targetMethod, List.of(field));
        String generatedCode = model.transform();

        // then
        assertNotNull(generatedCode);

        // Should have both @Before and @After (for state tracking)
        assertTrue(generatedCode.contains("@Before"));
        assertTrue(generatedCode.contains("@After"));

        // Field should be captured in both - count occurrences
        int fieldCaptureCount = countOccurrences(generatedCode, "di.getInstanceFieldValue");
        assertEquals(2, fieldCaptureCount, "Field should be captured in both @Before and @After hooks");
    }

    /**
     * Test that Object return type uses Object.class for getStackValue.
     */
    @Test
    void givenObjectReturnType_whenConstructingModel_thenUsesObjectClass() {
        // given
        JavaClassIdentifier testClass = new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .className("Factory")
                        .packageIdentifier(new JavaPackageIdentifier("com.example"))
                        .build());

        JavaMethodIdentifier targetMethod = new JavaMethodIdentifier(
                MethodIdentifierParameters.builder()
                        .ownerClassIdentifier(testClass)
                        .methodName("create")
                        .returnType("java.lang.Object")
                        .build());

        JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                new ReturnValueIdentifierParameters(targetMethod));

        // when
        DiSLModel model = new DiSLModel(targetMethod, List.of(returnValue));
        String generatedCode = model.transform();

        // then
        assertTrue(generatedCode.contains("di.getStackValue(0, java.lang.Object.class)"));
    }

    // Helper method to count occurrences of a substring
    private int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
