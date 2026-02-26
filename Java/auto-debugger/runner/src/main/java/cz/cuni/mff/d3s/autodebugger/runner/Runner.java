package cz.cuni.mff.d3s.autodebugger.runner;

import cz.cuni.mff.d3s.autodebugger.analyzer.common.AnalysisResult;
import cz.cuni.mff.d3s.autodebugger.runner.args.Arguments;
import cz.cuni.mff.d3s.autodebugger.runner.orchestrator.Orchestrator;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

/**
 * Main entry point for the auto-debugger application.
 * Handles command-line argument parsing using PicoCLI and delegates execution
 * to the orchestrator for the complete instrumentation-analysis-testing workflow.
 */
@Slf4j
public class Runner {

  public static void main(String[] args) {
    try {
      // Parse command line arguments
      Arguments arguments = new Arguments();
      CommandLine commandLine = new CommandLine(arguments);
      commandLine.parseArgs(args);

      // Check if help was requested
      if (commandLine.isUsageHelpRequested()) {
        commandLine.usage(System.out);
        return;
      }

      run(arguments);

    } catch (CommandLine.ParameterException e) {
      log.error("Invalid command line arguments: {}", e.getMessage());
      System.err.println("Error: " + e.getMessage());
      new CommandLine(new Arguments()).usage(System.err);
      System.exit(1);
    } catch (Exception e) {
      log.error("Failed to run auto-debugger", e);
      System.err.println("Error: " + e.getMessage());
      System.exit(1);
    }
  }

  /**
   * Executes the complete auto-debugger workflow using the provided arguments.
   * Orchestrates instrumentation, analysis, test generation, and test execution
   * in sequence, with comprehensive logging at each stage.
   */
  public static void run(Arguments arguments) {
    log.info("Starting auto-debugger with arguments:");
    log.info("  Application JAR: {}", arguments.applicationJarPath);
    log.info("  Application arguments: {}", arguments.runtimeArguments);
    log.info("  Source code path: {}", arguments.sourceCodePath);
    log.info("  Target method: {}", arguments.targetMethodReference);
    log.info("  Target parameters: {}", arguments.targetParameters);
    log.info("  Target fields: {}", arguments.targetFields);
    log.info("  Language: {}", arguments.language.getDisplayName());
    log.info("  Test generation strategy: {}", arguments.testGenerationStrategy);
    log.info("  API key provided: {}", arguments.apiKey != null && !arguments.apiKey.isBlank());
    log.info("  Classpath: {}", arguments.classpath);
    log.info("  DiSL home: {}", arguments.dislHomePath);

    // Validate arguments early with clear error messages
    log.info("Validating arguments...");
    arguments.validateOrThrow();
    log.info("Arguments validated successfully");

    var orchestrator = new Orchestrator(arguments);
    log.info("Created orchestrator for language: {}", arguments.language.getDisplayName());

    var instrumentationModel = orchestrator.buildInstrumentationModel();
    log.info("Built instrumentation model");

    var instrumentation = orchestrator.createInstrumentation(instrumentationModel);
    log.info("Created instrumentation: {}", instrumentation);

    log.info("Running analysis...");
    var analysisResult = orchestrator.executeAnalysis(instrumentation);
    log.info("Analysis completed. Trace at: {}", analysisResult.getTraceFilePath());

    log.info("Generating tests...");
    var testSuite = orchestrator.generateTests(analysisResult);
    log.info("Test generation completed. {} test files: {}", testSuite.getTestFiles().size(), testSuite.getTestFiles());

    var testResults = orchestrator.runTests(testSuite);
    log.info("Test execution completed. Results: {}", testResults);
  }
}
