package cz.cuni.mff.d3s.autodebugger.analyzer.java;

import cz.cuni.mff.d3s.autodebugger.analyzer.common.Analyzer;
import cz.cuni.mff.d3s.autodebugger.analyzer.common.AnalysisResult;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.helper.DiSLPathHelper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Java-specific analyzer implementation that executes instrumented Java applications
 * and collects runtime traces through DiSL instrumentation.
 */
@Slf4j
@RequiredArgsConstructor
public class DiSLAnalyzer implements Analyzer {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300; // 5 minutes

    @Getter
    private final JavaRunConfiguration runConfiguration;

    /**
     * Gets the timeout in seconds for process execution.
     * Protected to allow overriding in tests.
     */
    protected long getTimeoutSeconds() {
        return DEFAULT_TIMEOUT_SECONDS;
    }

    /**
     * Executes analysis on the instrumented application and returns analysis artifacts.
     * This method only performs the instrumentation execution and trace collection.
     *
     * The Collector (running in the DiSL process) is responsible for:
     * - Collecting runtime values
     * - Building the Trace
     * - Serializing the Trace to disk
     *
     * This method orchestrates the execution and validates the output.
     */
    @Override
    public AnalysisResult executeAnalysis(InstrumentationResult instrumentation) {
        log.info("Starting Java analysis on instrumented application: {}", instrumentation);

        validateInstrumentation(instrumentation);
        Path instrumentationJarPath = instrumentation.getPrimaryArtifact();
        try {
            List<String> command = buildExecutionCommand(instrumentationJarPath);
            int exitCode = runCommandAsProcess(command);

            if (exitCode != 0) {
                log.error("Analysis process failed with exit code: {}", exitCode);
                throw new RuntimeException("DiSL analysis failed with exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            log.error("Failed to execute instrumented application", e);
            throw new RuntimeException("Analysis execution failed", e);
        }

        // Validate analysis produced output
        Path traceFilePath = instrumentation.getTraceFilePath();
        Path identifierMappingPath = instrumentation.getIdentifiersMappingPath();
        validateAnalysisOutput(traceFilePath, identifierMappingPath);

        return AnalysisResult.builder()
                .traceFilePath(traceFilePath)
                .identifiersMappingPath(identifierMappingPath)
                .outputDirectory(runConfiguration.getOutputDirectory())
                .build();
    }



    @Override
    public void validateInstrumentation(InstrumentationResult instrumentation) {
        if (instrumentation == null || instrumentation.getPrimaryArtifact() == null) {
            throw new IllegalArgumentException("Instrumentation primary artifact cannot be null");
        }
        var instrumentationPath = instrumentation.getPrimaryArtifact();
        if (!Files.exists(instrumentationPath)) {
            throw new IllegalArgumentException("Instrumentation file does not exist: " + instrumentationPath);
        }
        if (!instrumentationPath.toString().endsWith(".jar")) {
            throw new IllegalArgumentException("Expected JAR file for DiSL instrumentation, got: " + instrumentationPath);
        }
        log.debug("Instrumentation validation passed for: {}", instrumentationPath);
    }

    public List<String> buildExecutionCommand(Path instrumentationJarPath) {
        List<String> command = new ArrayList<>();

        // Run the disl.py script
        command.add("python3");
        command.add(DiSLPathHelper.getDislRunnerPath(runConfiguration).toString());

        // Add the DiSL home path
        command.add("-d");
        command.add(DiSLPathHelper.getDislHomePath(runConfiguration).toString());

        // Run with the client (target app), server (DiSL instrumentation server), and evaluation (ShadowVM)
        command.add("-cse");

        // Build classpath: include application path if it's a directory, plus any additional entries
        Path applicationPath = runConfiguration.getApplicationPath();
        boolean isDirectory = Files.isDirectory(applicationPath);
        List<Path> classpathEntries = runConfiguration.getClasspathEntries();

        // Collect all classpath entries
        List<String> allClasspathEntries = new ArrayList<>();
        if (isDirectory) {
            // Application is a directory of classes, add it to classpath
            allClasspathEntries.add(applicationPath.toAbsolutePath().toString());
        }
        if (classpathEntries != null && !classpathEntries.isEmpty()) {
            classpathEntries.stream()
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .forEach(allClasspathEntries::add);
        }

        // Add client classpath if we have any entries
        // Note: disl.py requires using = sign for args starting with dash to avoid argparse confusion
        if (!allClasspathEntries.isEmpty()) {
            String classpathString = String.join(File.pathSeparator, allClasspathEntries);
            command.add("-c_opts=-cp");
            command.add("-c_opts=" + classpathString);
        }

        command.add("--");

        // Add the generated DiSL instrumentation JAR (use absolute path)
        command.add(instrumentationJarPath.toAbsolutePath().toString());

        // Add the target application: JAR file uses -jar, directory uses main class from runtime args
        if (!isDirectory) {
            command.add("-jar");
            command.add(applicationPath.toString());
        }

        // Add runtime arguments for the target application
        if (!runConfiguration.getRuntimeArguments().isEmpty()) {
            command.addAll(runConfiguration.getRuntimeArguments());
        }

        return command;
    }

    private int runCommandAsProcess(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(runConfiguration.getOutputDirectory().toFile());

        log.info("Executing command: {}", String.join(" ", command));
        Process process = processBuilder.start();

        // Capture output and error streams
        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();

        // Read output in separate threads
        Thread outputReader = new Thread(() -> readStream(process.getInputStream(), output));
        Thread errorReader = new Thread(() -> readStream(process.getErrorStream(), errorOutput));

        outputReader.start();
        errorReader.start();

        // Wait for process completion with timeout
        boolean finished = process.waitFor(getTimeoutSeconds(), TimeUnit.SECONDS);

        if (!finished) {
            log.warn("Analysis process timed out after {} seconds, terminating", getTimeoutSeconds());
            process.destroyForcibly();
            throw new RuntimeException("Analysis process timed out");
        }

        // Wait for output readers to finish
        outputReader.join(5000);
        errorReader.join(5000);

        int exitCode = process.exitValue();
        log.info("Analysis process completed with exit code: {}", exitCode);

        // Always log both output and error streams for debugging
        log.info("Analysis stdout output: {}", output);
        log.info("Analysis stderr output: {}", errorOutput);

        return exitCode;
    }



    /**
     * Validates that analysis produced required output files.
     */
    private void validateAnalysisOutput(Path traceFilePath, Path identifierMappingPath) {
        if (traceFilePath == null) {
            throw new IllegalStateException(
                "Trace file path is null - DiSL execution may not have configured trace output");
        }
        if (!Files.exists(traceFilePath)) {
            throw new IllegalStateException(
                "Trace file not created after DiSL execution: " + traceFilePath +
                ". Check DiSL process logs for errors.");
        }
        if (identifierMappingPath == null || !Files.exists(identifierMappingPath)) {
            throw new IllegalStateException(
                "Identifier mapping file not found: " + identifierMappingPath);
        }
    }

    private void readStream(InputStream inputStream, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        } catch (IOException e) {
            log.error("Error reading process stream", e);
        }
    }
}
