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
 * End-to-end test for instance field instrumentation with DiSL.
 * Tests the complete pipeline: compile target, instrument, analyze, and verify trace content.
 * 
 * Target: Counter.increment() in default package (instance method)
 * Verifies: Instance field 'value' is correctly captured in trace slots
 */
class InstanceFieldEndToEndTest extends DiSLEndToEndTestBase {

    // ========== Test Methods ==========

    @Test
    void givenSingleInvocation_whenInstrumentingInstanceField_thenTraceContainsFieldValue() throws Exception {
        // Given: Counter with value=10, single increment() call
        Path targetJar = compileAndPackageTarget(
                createSingleInvocationCounter(),
                "counter-single.jar",
                "Counter");

        JavaMethodIdentifier targetMethod = createIncrementMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createFieldIdentifiers();

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

        int slot = findSlotForField(mapping, "value");

        // Value=10 at @Before, then value++ makes it 11, captured at @After
        // Using assertSlotContainsIntValues to verify initial value is captured
        TraceVerifier.assertSlotContainsIntValues(trace, slot, 10);
    }

    @Test
    void givenMultipleInvocations_whenInstrumentingInstanceField_thenTraceContainsAllValues() throws Exception {
        // Given: Counter with value=5, then value=15, two increment() calls
        Path targetJar = compileAndPackageTarget(
                createMultipleInvocationCounter(),
                "counter-multiple.jar",
                "Counter");

        JavaMethodIdentifier targetMethod = createIncrementMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createFieldIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE)
                .build();

        JavaClassIdentifier instrumentationClassName = createInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation and execute analysis
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        // Then: Trace contains all field values from both invocations
        // Note: DiSL instruments both @Before and @After, so we get values at entry and exit
        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        int slot = findSlotForField(mapping, "value");

        // Value=5 at @Before (first call), 6 at @After, 15 at @Before (second call), 16 at @After
        // Using assertSlotContainsIntValues to verify initial values are captured
        TraceVerifier.assertSlotContainsIntValues(trace, slot, 5, 15);
    }

    @Test
    void givenInstanceFieldMethod_whenCheckingIdentifierMapping_thenSlotMapsToCorrectField() throws Exception {
        // Given: Counter with increment method
        Path targetJar = compileAndPackageTarget(
                createSingleInvocationCounter(),
                "counter-mapping.jar",
                "Counter");

        JavaMethodIdentifier targetMethod = createIncrementMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createFieldIdentifiers();

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
        int slot = findSlotForField(mapping, "value");
        JavaValueIdentifier value = mapping.get(slot);
        assertInstanceOf(JavaFieldIdentifier.class, value);
        JavaFieldIdentifier fieldId = (JavaFieldIdentifier) value;
        assertEquals("value", fieldId.getFieldName());
        assertEquals("int", fieldId.getType());
        assertEquals("Counter", fieldId.getOwnerClassIdentifier().getClassName());
    }

    // ========== Helper Methods ==========

    /**
     * Creates Counter source code with a single invocation: value=10, increment()
     */
    private String createSingleInvocationCounter() {
        return """
                public class Counter {
                    public int value = 0;

                    public void increment() {
                        value++;
                    }

                    public static void main(String[] args) {
                        Counter counter = new Counter();
                        counter.value = 10;
                        counter.increment();
                        System.out.println("Value: " + counter.value);
                    }
                }
                """;
    }

    /**
     * Creates Counter source code with multiple invocations: value=5, increment(), value=15, increment()
     */
    private String createMultipleInvocationCounter() {
        return """
                public class Counter {
                    public int value = 0;

                    public void increment() {
                        value++;
                    }

                    public static void main(String[] args) {
                        Counter counter = new Counter();
                        counter.value = 5;
                        counter.increment();
                        System.out.println("Value: " + counter.value);

                        counter.value = 15;
                        counter.increment();
                        System.out.println("Value: " + counter.value);
                    }
                }
                """;
    }

    /**
     * Creates JavaMethodIdentifier for Counter.increment()
     */
    private JavaMethodIdentifier createIncrementMethodIdentifier() {
        return createMethodIdentifier(
                "",              // default package
                "Counter",       // class name
                "increment",     // method name
                "void",          // return type
                List.of()        // no parameters
        );
    }

    /**
     * Creates list of JavaFieldIdentifiers for the value field
     */
    private List<JavaValueIdentifier> createFieldIdentifiers() {
        return List.of(
                createFieldIdentifier(
                        "",          // default package
                        "Counter",   // class name
                        "value",     // field name
                        "int"        // field type
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
                        .className("CounterInstrumentation")
                        .build());
    }
}

