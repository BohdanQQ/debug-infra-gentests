package cz.cuni.mff.d3s.autodebugger.runner.orchestrator;

import cz.cuni.mff.d3s.autodebugger.runner.args.Arguments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorEmptyResultsIntegrationTest {

  @TempDir Path tempDir;

  private Arguments baseArgs(Path outputDir, Path dislHome, Path appJar, Path sourceDir) {
    Arguments args = new Arguments();
    args.language = cz.cuni.mff.d3s.autodebugger.model.common.TargetLanguage.JAVA;
    args.applicationJarPath = appJar.toString();
    args.sourceCodePath = sourceDir.toString();
    args.dislHomePath = dislHome.toString();
    args.targetMethodReference = "com.example.SimpleAdder.add(int, int)";
    args.testGenerationStrategy = "trace-based-basic";
    args.classpath = List.of();
    args.targetParameters = List.of("0:int", "1:int");
    args.targetFields = List.of();
    args.runtimeArguments = List.of();
    return args;
  }

  @Test
  void whenStubDisabled_andNoSignals_thenExecuteAnalysisThrows() throws Exception {
    // given: no stub and no pre-created results; analysis should produce an empty result

    Path sourceDir = tempDir.resolve("src");
    Path outputDir = tempDir.resolve("output");
    Path dislHome = tempDir.resolve("disl");
    Path appJar = tempDir.resolve("app.jar");
    Files.createDirectories(sourceDir);
    Files.createDirectories(outputDir);
    Files.createDirectories(dislHome.resolve("bin"));
    Files.createDirectories(dislHome.resolve("output").resolve("lib"));
    Files.createFile(dislHome.resolve("bin").resolve("disl.py"));
    Files.createFile(appJar);

    Orchestrator orchestrator = new Orchestrator(baseArgs(outputDir, dislHome, appJar, sourceDir));

    var model = orchestrator.buildInstrumentationModel();
    var instrumentation = orchestrator.createInstrumentation(model);

    // when/then: executeAnalysis must throw because trace file is not created
    // (in a mock scenario without real DiSL execution, no trace file is produced)
    var ex = assertThrows(Exception.class, () -> orchestrator.executeAnalysis(instrumentation));
    // The validation may throw IllegalStateException (trace not found) or RuntimeException (command execution fails)
    assertTrue(ex.getMessage().contains("Trace file") ||
               ex.getMessage().contains("DiSL") ||
               ex.getMessage().contains("Cannot run") ||
               ex.getMessage().contains("analysis"),
               "Expected exception about trace file, DiSL, or analysis but got: " + ex.getMessage());
  }
}

