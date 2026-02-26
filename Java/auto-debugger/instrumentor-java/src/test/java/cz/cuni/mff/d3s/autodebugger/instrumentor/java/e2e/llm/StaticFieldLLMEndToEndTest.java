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
 * LLM-based end-to-end test duplicating StaticFieldEndToEndTest.
 * Uses ai-assisted strategy with mock model to verify LLM test generation pipeline.
 * 
 * This test runs the complete DiSL instrumentation and analysis pipeline,
 * then uses the LLM generator with a mock model to generate tests from the trace.
 */
class StaticFieldLLMEndToEndTest extends LLMEndToEndTestBase {

    @Test
    void givenStaticFieldMethod_whenGeneratingWithMockLLM_thenTestFileGenerated() throws Exception {
        // Given: Globals with X=0, single bump() call
        String sourceCode = createSingleInvocationGlobals();
        Path targetJar = compileAndPackageTarget(sourceCode, "globals-llm.jar", "Globals");

        // Create source file for LLM generator (it needs a file, not directory)
        Path sourceFile = tempDir.resolve("src/Globals.java");
        java.nio.file.Files.createDirectories(sourceFile.getParent());
        java.nio.file.Files.writeString(sourceFile, sourceCode);

        JavaMethodIdentifier targetMethod = createBumpMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createStaticFieldIdentifiers();

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

