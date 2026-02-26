package cz.cuni.mff.d3s.autodebugger.instrumentor.java.e2e.llm;

import cz.cuni.mff.d3s.autodebugger.analyzer.common.AnalysisResult;
import cz.cuni.mff.d3s.autodebugger.analyzer.java.DiSLAnalyzer;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.DiSLInstrumentor;
import cz.cuni.mff.d3s.autodebugger.model.common.TraceMode;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * LLM-based end-to-end test duplicating TwoParameterEndToEndTest.
 * Uses ai-assisted strategy with mock model to verify LLM test generation pipeline.
 * 
 * This test runs the complete DiSL instrumentation and analysis pipeline,
 * then uses the LLM generator with a mock model to generate tests from the trace.
 */
class TwoParameterLLMEndToEndTest extends LLMEndToEndTestBase {

    @Test
    void givenTwoParameterMethod_whenGeneratingWithMockLLM_thenTestFileGenerated() throws Exception {
        // Given: Calculator with add(42, 17) call
        String sourceCode = createSingleInvocationCalculator();
        Path targetJar = compileAndPackageTarget(sourceCode, "calculator-llm.jar", "Calculator");

        // Create source file for LLM generator (it needs a file, not directory)
        Path sourceFile = tempDir.resolve("src/Calculator.java");
        java.nio.file.Files.createDirectories(sourceFile.getParent());
        java.nio.file.Files.writeString(sourceFile, sourceCode);

        JavaMethodIdentifier targetMethod = createAddMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createArgumentIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE)
                .build();

        JavaClassIdentifier instrumentationClassName = createInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation, execute analysis, then generate LLM tests
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        TestGenerator llmGenerator = createMockLLMTestGenerator(runConfig, instrumentation.getIdentifiersMappingPath());
        List<Path> generatedTests = generateLLMTests(llmGenerator, trace, sourceFile, runConfig);

        // Then: Verify tests were generated
        assertTestsGenerated(generatedTests);
        for (Path testPath : generatedTests) {
            assertGeneratedTestIsValid(testPath);
        }
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

