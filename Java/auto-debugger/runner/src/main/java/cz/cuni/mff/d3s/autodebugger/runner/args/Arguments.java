package cz.cuni.mff.d3s.autodebugger.runner.args;

import cz.cuni.mff.d3s.autodebugger.model.common.TargetLanguage;
import cz.cuni.mff.d3s.autodebugger.runner.strategies.TestGenerationStrategyProvider;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command-line arguments container for the auto-debugger application.
 * Uses PicoCLI annotations to define command-line options with validation,
 * default values, and help text for user-friendly CLI experience.
 */
public class Arguments {
    @CommandLine.Option(names = { "-j", "--jar" }, paramLabel = "JAR", description = "Path to the application JAR file", required = true)
    public String applicationJarPath;

    @CommandLine.Option(names = { "-c", "--classpath" }, paramLabel = "CLASSPATH", description = "Additional classpath entries (separated by ':')", split = ":")
    public List<String> classpath;

    @CommandLine.Option(names = { "-a", "--args" }, paramLabel = "ARGS", description = "Runtime arguments for the target application (separated by ' ')", split = " ")
    public List<String> runtimeArguments;

    @CommandLine.Option(names = { "-s", "--source" }, paramLabel = "SOURCE", description = "Path to the target application's source code", required = true)
    public String sourceCodePath;

    @CommandLine.Option(names = { "-o", "--output-dir" }, paramLabel = "OUTPUT", description = "Output directory for generated artifacts (results, identifiers)")
    public String outputDirectory;

    @CommandLine.Option(names = { "-d", "--disl-home" }, paramLabel = "DISL_HOME", description = "Path to the DiSL project (required for DiSL-based analysis)")
    public String dislHomePath;

    @CommandLine.Option(names = { "-m", "--method" }, paramLabel = "METHOD", description = "Target method reference (e.g., org.example.Main.main(String[]))", required = true)
    public String targetMethodReference;

    @CommandLine.Option(names = { "-p", "--parameters" }, paramLabel = "PARAMETERS", description = "Target method parameters (format: type:name or slot:type)", split = ",")
    public List<String> targetParameters;

    @CommandLine.Option(names = { "-f", "--fields" }, paramLabel = "FIELDS", description = "Target class fields (format: type:name)", split = ",")
    public List<String> targetFields;

    @CommandLine.Option(names = { "-l", "--language" }, paramLabel = "LANGUAGE",
                        description = "Programming language. Supported: ${COMPLETION-CANDIDATES}",
                        defaultValue = "java", converter = TargetLanguageConverter.class)
    public TargetLanguage language;

    @CommandLine.Option(names = { "-t", "--test-strategy" }, paramLabel = "STRATEGY",
                        description = "Test generation strategy (e.g., trace-based-basic, ai-assisted)",
                        defaultValue = "trace-based-basic")
    public String testGenerationStrategy;

    @CommandLine.Option(names = { "-r", "--trace-mode" }, paramLabel = "TRACE_MODE",
                        description = "Trace collection mode: naive or temporal",
                        defaultValue = "naive")
    public String traceMode;

    @CommandLine.Option(names = { "-k", "--api-key" }, paramLabel = "API_KEY",
                        description = "API key for LLM services (can also be set via ANTHROPIC_API_KEY environment variable)")
    public String apiKey;

    @CommandLine.Option(names = { "--static-method" },
                        description = "Indicates the target method is a static method")
    public boolean isStaticMethod = false;

    @CommandLine.Option(names = { "--void-method" },
                        description = "Indicates the target method returns void")
    public boolean isVoidMethod = false;

    @CommandLine.Option(names = { "--max-argument-combinations" }, paramLabel = "COUNT",
                        description = "Maximum number of argument value combinations to generate. " +
                                      "Default: no limit (all captured combinations). " +
                                      "Use this to limit test generation if you have many captured values.")
    public Integer maxArgumentCombinations;

    @CommandLine.Option(names = { "-h", "--help" }, usageHelp = true, description = "display a help message")
    private boolean helpRequested = false;

    /**
     * Custom converter for TargetLanguage enum to work with picocli.
     */
    public static class TargetLanguageConverter implements CommandLine.ITypeConverter<TargetLanguage> {
        @Override
        public TargetLanguage convert(String value) {
            return TargetLanguage.fromIdentifier(value);
        }
    }

    /**
     * Validates the arguments and returns a list of validation errors.
     * Returns an empty list if all arguments are valid.
     *
     * @return List of validation error messages, empty if valid
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        // Validate required paths exist (JAR file or directory with classes)
        if (applicationJarPath != null) {
            Path jarPath = Path.of(applicationJarPath);
            if (!Files.exists(jarPath)) {
                errors.add("Application path not found: " + applicationJarPath);
            } else if (!applicationJarPath.endsWith(".jar") && !Files.isDirectory(jarPath)) {
                errors.add("Application path must be a .jar file or directory: " + applicationJarPath);
            }
        }

        if (sourceCodePath != null) {
            Path sourcePath = Path.of(sourceCodePath);
            if (!Files.exists(sourcePath)) {
                errors.add("Source code path not found: " + sourceCodePath);
            }
        }

        // Validate DiSL home path
        if (dislHomePath != null) {
            String expandedPath = dislHomePath.startsWith("~")
                    ? System.getProperty("user.home") + dislHomePath.substring(1)
                    : dislHomePath;
            Path dislPath = Path.of(expandedPath);
            if (!Files.exists(dislPath)) {
                errors.add("DiSL home path not found: " + dislHomePath + " (resolved to: " + expandedPath + ")");
            }
        }

        // Validate method reference format
        if (targetMethodReference != null && !targetMethodReference.isEmpty()) {
            if (!targetMethodReference.contains(".") || !targetMethodReference.contains("(")) {
                errors.add("Invalid method reference format: '" + targetMethodReference + "'. "
                        + "Expected format: ClassName.methodName(paramTypes) or package.ClassName.methodName(paramTypes)");
            }
        }

        // Validate test generation strategy
        if (testGenerationStrategy != null && !testGenerationStrategy.isEmpty()) {
            if (!TestGenerationStrategyProvider.hasStrategy(testGenerationStrategy)) {
                String availableStrategies = TestGenerationStrategyProvider.getAvailableStrategies().stream()
                        .map(s -> s.getId())
                        .collect(Collectors.joining(", "));
                errors.add("Unknown test generation strategy: '" + testGenerationStrategy + "'. "
                        + "Available strategies: " + availableStrategies);
            }
        }

        // Validate AI-assisted strategy requires API key
        if ("ai-assisted".equals(testGenerationStrategy)) {
            String envApiKey = System.getenv("ANTHROPIC_API_KEY");
            if ((apiKey == null || apiKey.isEmpty()) && (envApiKey == null || envApiKey.isEmpty())) {
                errors.add("AI-assisted strategy requires an API key. "
                        + "Provide via --api-key or set ANTHROPIC_API_KEY environment variable.");
            }
        }

        // Validate trace mode
        if (traceMode != null && !traceMode.isEmpty()) {
            if (!traceMode.equalsIgnoreCase("naive") && !traceMode.equalsIgnoreCase("temporal")) {
                errors.add("Invalid trace mode: '" + traceMode + "'. Supported modes: naive, temporal");
            }
        }

        // Validate parameter format if provided
        if (targetParameters != null) {
            for (String param : targetParameters) {
                if (!param.contains(":")) {
                    errors.add("Invalid parameter format: '" + param + "'. Expected format: slot:type (e.g., 0:int)");
                }
            }
        }

        // Validate field format if provided
        if (targetFields != null) {
            for (String field : targetFields) {
                if (!field.contains(":")) {
                    errors.add("Invalid field format: '" + field + "'. Expected format: type:name (e.g., int:counter)");
                }
            }
        }

        // Warn if no values to capture
        if ((targetParameters == null || targetParameters.isEmpty())
                && (targetFields == null || targetFields.isEmpty())) {
            errors.add("No values to capture. Specify at least one parameter (--parameters) or field (--fields).");
        }

        return errors;
    }

    /**
     * Validates arguments and throws an exception if invalid.
     *
     * @throws IllegalArgumentException if validation fails, with all error messages
     */
    public void validateOrThrow() throws IllegalArgumentException {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            String message = "Invalid arguments:\n  - " + String.join("\n  - ", errors);
            throw new IllegalArgumentException(message);
        }
    }
}