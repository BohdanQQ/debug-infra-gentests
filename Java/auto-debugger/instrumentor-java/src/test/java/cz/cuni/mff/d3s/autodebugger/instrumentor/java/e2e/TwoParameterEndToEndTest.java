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
 * End-to-end test for two-parameter method instrumentation with DiSL.
 * Tests the complete pipeline: compile target, instrument, analyze, and verify trace content.
 * 
 * Target: Calculator.add(int a, int b) in default package (static method)
 * Verifies: Both arguments are correctly captured in trace slots
 */
class TwoParameterEndToEndTest extends DiSLEndToEndTestBase {

    // ========== Test Methods ==========

    @Test
    void givenSingleInvocation_whenInstrumentingTwoParameters_thenTraceContainsBothArguments() throws Exception {
        // Given: Calculator with add(42, 17) call
        Path targetJar = compileAndPackageTarget(
                createSingleInvocationCalculator(),
                "calculator-single.jar",
                "Calculator");

        JavaMethodIdentifier targetMethod = createAddMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createArgumentIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE)
                .build();

        JavaClassIdentifier instrumentationClassName = createInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation and execute analysis
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        // Then: Trace contains both argument values
        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        int slot0 = findSlotForArgument(mapping, 0);
        int slot1 = findSlotForArgument(mapping, 1);

        TraceVerifier.assertSlotContainsExactlyIntValues(trace, slot0, 42);
        TraceVerifier.assertSlotContainsExactlyIntValues(trace, slot1, 17);
    }

    @Test
    void givenMultipleInvocations_whenInstrumentingTwoParameters_thenTraceContainsAllValues() throws Exception {
        // Given: Calculator with add(5, 3) and add(10, 20) calls
        Path targetJar = compileAndPackageTarget(
                createMultipleInvocationCalculator(),
                "calculator-multiple.jar",
                "Calculator");

        JavaMethodIdentifier targetMethod = createAddMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createArgumentIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE)
                .build();

        JavaClassIdentifier instrumentationClassName = createInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation and execute analysis
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        // Then: Trace contains all argument values from both invocations
        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        int slot0 = findSlotForArgument(mapping, 0);
        int slot1 = findSlotForArgument(mapping, 1);

        TraceVerifier.assertSlotContainsExactlyIntValues(trace, slot0, 5, 10);
        TraceVerifier.assertSlotContainsExactlyIntValues(trace, slot1, 3, 20);
    }

    @Test
    void givenTwoParameterMethod_whenCheckingIdentifierMapping_thenSlotsMapToCorrectArguments() throws Exception {
        // Given: Calculator with add method
        Path targetJar = compileAndPackageTarget(
                createSingleInvocationCalculator(),
                "calculator-mapping.jar",
                "Calculator");

        JavaMethodIdentifier targetMethod = createAddMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createArgumentIdentifiers();

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

        // Verify mapping contains both arguments
        assertEquals(2, mapping.size(), "Mapping should contain exactly 2 entries");

        // Verify slot 0 maps to first argument (argumentSlot=0)
        int slot0 = findSlotForArgument(mapping, 0);
        JavaValueIdentifier value0 = mapping.get(slot0);
        assertInstanceOf(JavaArgumentIdentifier.class, value0);
        assertEquals(0, ((JavaArgumentIdentifier) value0).getArgumentSlot());
        assertEquals("int", value0.getType());

        // Verify slot 1 maps to second argument (argumentSlot=1)
        int slot1 = findSlotForArgument(mapping, 1);
        JavaValueIdentifier value1 = mapping.get(slot1);
        assertInstanceOf(JavaArgumentIdentifier.class, value1);
        assertEquals(1, ((JavaArgumentIdentifier) value1).getArgumentSlot());
        assertEquals("int", value1.getType());
    }

    // ========== Helper Methods ==========

    /**
     * Creates Calculator source code with a single invocation: add(42, 17)
     */
    private String createSingleInvocationCalculator() {
        return """
                public class Calculator {
                    public static int add(int a, int b) {
                        return a + b;
                    }

                    public static void main(String[] args) {
                        int result = add(42, 17);
                        System.out.println("Result: " + result);
                    }
                }
                """;
    }

    /**
     * Creates Calculator source code with multiple invocations: add(5, 3) and add(10, 20)
     */
    private String createMultipleInvocationCalculator() {
        return """
                public class Calculator {
                    public static int add(int a, int b) {
                        return a + b;
                    }

                    public static void main(String[] args) {
                        int result1 = add(5, 3);
                        System.out.println("Result 1: " + result1);

                        int result2 = add(10, 20);
                        System.out.println("Result 2: " + result2);
                    }
                }
                """;
    }

    /**
     * Creates JavaMethodIdentifier for Calculator.add(int, int)
     */
    private JavaMethodIdentifier createAddMethodIdentifier() {
        return createMethodIdentifier(
                "",              // default package
                "Calculator",    // class name
                "add",           // method name
                "int",           // return type
                List.of("int", "int")  // parameter types
        );
    }

    /**
     * Creates list of JavaArgumentIdentifiers for both parameters
     */
    private List<JavaValueIdentifier> createArgumentIdentifiers() {
        return List.of(
                createArgumentIdentifier(0, "int"),  // first argument
                createArgumentIdentifier(1, "int")   // second argument
        );
    }

    /**
     * Creates JavaClassIdentifier for the instrumentation class
     */
    private JavaClassIdentifier createInstrumentationClassName() {
        return new JavaClassIdentifier(
                ClassIdentifierParameters.builder()
                        .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                        .className("CalculatorInstrumentation")
                        .build());
    }
}
