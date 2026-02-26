package cz.cuni.mff.d3s.autodebugger.instrumentor.java.e2e;

import cz.cuni.mff.d3s.autodebugger.analyzer.common.AnalysisResult;
import cz.cuni.mff.d3s.autodebugger.analyzer.java.DiSLAnalyzer;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.DiSLInstrumentor;
import cz.cuni.mff.d3s.autodebugger.model.common.TraceMode;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.testutils.TraceVerifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for static field instrumentation with DiSL.
 * Tests the complete pipeline: compile target, instrument, analyze, and verify trace content.
 * 
 * Target: Globals.bump() in default package (static method)
 * Verifies: Static field 'X' is correctly captured in trace slots
 */
class StaticFieldEndToEndTest extends DiSLEndToEndTestBase {

    // ========== Test Methods ==========

    @Test
    void givenSingleInvocation_whenInstrumentingStaticField_thenTraceContainsFieldValue() throws Exception {
        // Given: Globals with X=0, single bump() call
        Path targetJar = compileAndPackageTarget(
                createSingleInvocationGlobals(),
                "globals-single.jar",
                "Globals");

        JavaMethodIdentifier targetMethod = createBumpMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createStaticFieldIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE)
                .build();

        JavaClassIdentifier instrumentationClassName = createInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation and execute analysis
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        // Then: Trace contains field value captured at method entry (before increment)
        // Note: DiSL instruments both @Before and @After, so we verify the initial value is captured
        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        int slot = findSlotForField(mapping, "X");

        // X=0 at @Before, then X++ makes it 1, captured at @After
        // Using assertSlotContainsIntValues to verify initial value is captured
        TraceVerifier.assertSlotContainsIntValues(trace, slot, 0);
    }

    @Test
    void givenMultipleInvocations_whenInstrumentingStaticField_thenTraceContainsAllValues() throws Exception {
        // Given: Globals with X=0, three bump() calls
        Path targetJar = compileAndPackageTarget(
                createMultipleInvocationGlobals(),
                "globals-multiple.jar",
                "Globals");

        JavaMethodIdentifier targetMethod = createBumpMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createStaticFieldIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE)
                .build();

        JavaClassIdentifier instrumentationClassName = createInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation and execute analysis
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        // Then: Trace contains all field values from all invocations
        // Note: DiSL instruments both @Before and @After, so we get values at entry and exit
        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        int slot = findSlotForField(mapping, "X");

        // X=0 at @Before (first call), 1 at @After, 1 at @Before (second call), 2 at @After, 2 at @Before (third call), 3 at @After
        // Using assertSlotContainsIntValues to verify initial values are captured
        TraceVerifier.assertSlotContainsIntValues(trace, slot, 0, 1, 2);
    }

    @Test
    void givenStaticFieldMethod_whenCheckingIdentifierMapping_thenSlotMapsToCorrectField() throws Exception {
        // Given: Globals with bump method
        Path targetJar = compileAndPackageTarget(
                createSingleInvocationGlobals(),
                "globals-mapping.jar",
                "Globals");

        JavaMethodIdentifier targetMethod = createBumpMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createStaticFieldIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE)
                .build();

        JavaClassIdentifier instrumentationClassName = createInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);

        // When: Generate instrumentation
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);

        // Then: Identifier mapping file exists and contains correct mappings
        assertTrue(instrumentation.getIdentifiersMappingPath().toFile().exists(),
                "Identifier mapping file should exist");

        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        // Verify mapping contains the field
        assertEquals(1, mapping.size(), "Mapping should contain exactly 1 entry");

        // Verify slot maps to the field
        int slot = findSlotForField(mapping, "X");
        JavaValueIdentifier value = mapping.get(slot);
        assertInstanceOf(JavaFieldIdentifier.class, value);
        JavaFieldIdentifier fieldId = (JavaFieldIdentifier) value;
        assertEquals("X", fieldId.getFieldName());
        assertEquals("int", fieldId.getType());
        assertEquals("Globals", fieldId.getOwnerClassIdentifier().getClassName());
        assertTrue(fieldId.isStatic(), "Field should be marked as static");
    }

    // ========== Helper Methods ==========

    /**
     * Creates Globals source code with a single invocation: X=0, bump()
     */
    private String createSingleInvocationGlobals() {
        return """
                public class Globals {
                    public static int X = 0;

                    public static void bump() {
                        X++;
                    }

                    public static void main(String[] args) {
                        bump();
                        System.out.println("X: " + X);
                    }
                }
                """;
    }

    /**
     * Creates Globals source code with multiple invocations: X=0, bump(), bump(), bump()
     */
    private String createMultipleInvocationGlobals() {
        return """
                public class Globals {
                    public static int X = 0;

                    public static void bump() {
                        X++;
                    }

                    public static void main(String[] args) {
                        bump();
                        System.out.println("X: " + X);

                        bump();
                        System.out.println("X: " + X);

                        bump();
                        System.out.println("X: " + X);
                    }
                }
                """;
    }

    /**
     * Creates JavaMethodIdentifier for Globals.bump()
     */
    private JavaMethodIdentifier createBumpMethodIdentifier() {
        return createMethodIdentifier(
                "",              // default package
                "Globals",       // class name
                "bump",          // method name
                "void",          // return type
                List.of(),       // no parameters
                true             // static method
        );
    }

    /**
     * Creates list of JavaFieldIdentifiers for the X field (static)
     */
    private List<JavaValueIdentifier> createStaticFieldIdentifiers() {
        return List.of(
                createFieldIdentifier(
                        "",          // default package
                        "Globals",   // class name
                        "X",         // field name
                        "int",       // field type
                        true         // isStatic
                )
        );
    }

    /**
     * Creates JavaClassIdentifier for the instrumentation class
     */
    private JavaClassIdentifier createInstrumentationClassName() {
        return new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                        .className("GlobalsInstrumentation")
                        .build());
    }
}

