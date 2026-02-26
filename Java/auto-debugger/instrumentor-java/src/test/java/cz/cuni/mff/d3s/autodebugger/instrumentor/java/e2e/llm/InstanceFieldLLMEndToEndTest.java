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
 * LLM-based end-to-end test duplicating InstanceFieldEndToEndTest.
 * Uses ai-assisted strategy with mock model to verify LLM test generation pipeline.
 * 
 * This test runs the complete DiSL instrumentation and analysis pipeline,
 * then uses the LLM generator with a mock model to generate tests from the trace.
 */
class InstanceFieldLLMEndToEndTest extends LLMEndToEndTestBase {

    @Test
    void givenInstanceFieldMethod_whenGeneratingWithMockLLM_thenTestFileGenerated() throws Exception {
        // Given: Counter with value=10, single increment() call
        String sourceCode = createSingleInvocationCounter();
        Path targetJar = compileAndPackageTarget(sourceCode, "counter-llm.jar", "Counter");

        // Create source file for LLM generator (it needs a file, not directory)
        Path sourceFile = tempDir.resolve("src/Counter.java");
        java.nio.file.Files.createDirectories(sourceFile.getParent());
        java.nio.file.Files.writeString(sourceFile, sourceCode);

        JavaMethodIdentifier targetMethod = createIncrementMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createFieldIdentifiers();

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

