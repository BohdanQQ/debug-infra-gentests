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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * LLM-based end-to-end test duplicating DateParameterEndToEndTest.
 * Uses ai-assisted strategy with mock model to verify LLM test generation pipeline
 * with Date object parameters.
 * 
 * This test runs the complete DiSL instrumentation and analysis pipeline,
 * then uses the LLM generator with a mock model to generate tests from the trace.
 */
class DateParameterLLMEndToEndTest extends LLMEndToEndTestBase {

    private static final long TIMESTAMP_DEC_10_2023 = 1702166400000L;

    @Test
    void givenDateParameter_whenGeneratingWithMockLLM_thenTestFileGenerated() throws Exception {
        // Given: DateService.format(Date) with Date(1702166400000L)
        String sourceCode = createSingleDateServiceTarget();
        Path targetJar = compileAndPackageTarget(sourceCode, "date-service-llm.jar", "DateService");

        // Create source file for LLM generator (it needs a file, not directory)
        Path sourceFile = tempDir.resolve("src/DateService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, sourceCode);

        JavaMethodIdentifier targetMethod = createFormatMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createDateArgumentIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE)
                .build();

        JavaClassIdentifier instrumentationClassName = createDateServiceInstrumentationClassName();
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

    private String createSingleDateServiceTarget() {
        return """
                import java.util.Date;

                public class DateService {
                    public String format(Date date) {
                        return "Date: " + date.toString();
                    }

                    public static void main(String[] args) {
                        DateService service = new DateService();
                        Date date = new Date(%dL);
                        String formatted = service.format(date);
                        System.out.println(formatted);
                    }
                }
                """.formatted(TIMESTAMP_DEC_10_2023);
    }

    private JavaMethodIdentifier createFormatMethodIdentifier() {
        return createMethodIdentifier("", "DateService", "format", "java.lang.String", List.of("java.util.Date"));
    }

    private List<JavaValueIdentifier> createDateArgumentIdentifiers() {
        return List.of(new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("java.util.Date")
                        .build()));
    }

    private JavaClassIdentifier createDateServiceInstrumentationClassName() {
        return new JavaClassIdentifier(ClassIdentifierParameters.builder()
                .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                .className("DateServiceInstrumentation")
                .build());
    }
}

