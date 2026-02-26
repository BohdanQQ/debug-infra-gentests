package cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm;

import cz.cuni.mff.d3s.autodebugger.model.common.RunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.TemporalTrace;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaValueIdentifier;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.*;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.exceptions.LLMConfigurationException;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm.exceptions.TestGenerationWorkflowException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * LLM-based test generator that uses Large Language Models to generate
 * semantically rich and comprehensive test suites based on runtime traces
 * and source code analysis.
 *
 * This implementation follows the simplified TestGenerator interface and
 * consolidates all test generation logic into clean, single-responsibility methods.
 */
@Slf4j
@RequiredArgsConstructor
public class LLMBasedTestGenerator implements TestGenerator {

    private LLMConfiguration llmConfig;
    private final AnthropicClient anthropicClient;
    private final PromptBuilder promptBuilder;
    private final CodeValidator codeValidator;
    private String generationTechnique = "LLM-based";

    /**
     * Configures the LLM-based test generator with the specified configuration.
     * This method must be called before generating tests.
     *
     * @param config The LLM configuration containing model settings and API credentials
     * @throws LLMConfigurationException if the configuration is invalid
     */
    public void configure(LLMConfiguration config) throws LLMConfigurationException {
        if (config == null) {
            throw new LLMConfigurationException("LLM configuration cannot be null");
        }
        this.llmConfig = config;

        // Configure the Anthropic client with the same configuration
        anthropicClient.configure(config);

        log.info("Configured LLM-based test generator with model: {}", config.getModelName());
    }

    @Override
    public List<Path> generateTests(Trace trace) {
        log.info("Generating LLM-based tests from trace without additional context");
        throw new TestGenerationWorkflowException(
            "LLM-based test generation requires source code path and context. Use generateTests(Trace, Path, TestGenerationContext) instead.",
            "Configuration", null, null);
    }

    @Override
    public List<Path> generateTests(Trace trace, RunConfiguration configuration) {
        log.info("Generating LLM-based tests from trace with RunConfiguration");
        // Use the RunConfiguration to get source code path and create context
        Path sourceCodePath = configuration.getSourceCodePath();
        TestGenerationContext context = TestGenerationContextFactory.createFromRunConfiguration(configuration);
        return generateTests(trace, sourceCodePath, context);
    }

    @Override
    public String getGenerationTechnique() {
        return generationTechnique;
    }

    /**
     * Allows the factory or caller to set a human-friendly technique label.
     * This supports scenarios where UI or tests expect a specific technique id like "ai-assisted".
     */
    public void setGenerationTechnique(String technique) {
        if (technique != null && !technique.isBlank()) {
            this.generationTechnique = technique;
        }
    }

    @Override
    public void validateTrace(Trace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("Trace cannot be null");
        }

        if (llmConfig == null) {
            throw new IllegalStateException("LLM configuration must be set before validating trace. Call configure() first.");
        }

        log.debug("Trace validation passed for LLM-based test generation");
    }
    
    @Override
    public List<Path> generateTests(Trace trace, Path sourceCodePath, TestGenerationContext context) {
        String methodSig = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()";
        String classFqcn = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedClassName() : "UnknownClass";
        String pkg = context.getTargetMethod() != null ? context.getTargetMethod().getPackageName() : "";
        log.info("Generating LLM-based tests for method: {}", methodSig);

        // Validate all parameters
        validateParameters(trace, sourceCodePath, context);

        try {
            // Read source code
            String sourceCode = Files.readString(sourceCodePath);

            // Build context for LLM prompt (handles both regular and temporal traces)
            LLMPromptContext promptContext = buildPromptContext(trace, sourceCode, context);

            // Generate and refine test code
            String generatedCode = generateAndRefineCode(promptContext);

            // Write test file
            Path testFile = writeTestFile(generatedCode, context);

            log.info("Successfully generated LLM-based test file: {}", testFile);
            return List.of(testFile);

        } catch (TestGenerationWorkflowException e) {
            // Re-throw workflow exceptions
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate LLM-based tests", e);
            throw new TestGenerationWorkflowException(
                "LLM-based test generation failed: " + e.getMessage(),
                "Test Generation",
                sourceCodePath.toString(),
                methodSig,
                e
            );
        }
    }

    /**
     * Validates all required parameters for test generation.
     *
     * @param trace The runtime trace data
     * @param sourceCodePath The path to the source code file
     * @param context The test generation context
     * @throws TestGenerationWorkflowException if any parameter is invalid
     */
    private void validateParameters(Trace trace, Path sourceCodePath, TestGenerationContext context) {
        if (trace == null) {
            throw new TestGenerationWorkflowException("Trace cannot be null", "Parameter Validation",
                sourceCodePath != null ? sourceCodePath.toString() : null, null);
        }
        if (sourceCodePath == null) {
            throw new TestGenerationWorkflowException("Source code path cannot be null", "Parameter Validation", null, null);
        }
        if (context == null) {
            throw new TestGenerationWorkflowException("Test generation context cannot be null", "Parameter Validation",
                sourceCodePath.toString(), null);
        }
        if (!Files.exists(sourceCodePath)) {
            throw new TestGenerationWorkflowException("Source code path does not exist: " + sourceCodePath,
                "Parameter Validation", sourceCodePath.toString(), null);
        }
        if (!Files.isRegularFile(sourceCodePath)) {
            throw new TestGenerationWorkflowException("Source code path must be a file, not a directory: " + sourceCodePath,
                "Parameter Validation", sourceCodePath.toString(), null);
        }
        if (llmConfig == null) {
            throw new IllegalStateException("LLM configuration must be set before generating tests. Call configure() first.");
        }
    }

    /**
     * Generates and refines test code using the LLM.
     *
     * @param promptContext The context for building LLM prompts
     * @return The generated and refined test code
     * @throws Exception if code generation fails
     */
    private String generateAndRefineCode(LLMPromptContext promptContext) throws Exception {
        // Generate initial test suite
        String prompt = promptBuilder.buildTestGenerationPrompt(promptContext);
        String generatedCode = anthropicClient.generateCode(prompt);

        // Validate and refine if needed
        if (llmConfig.isEnableIterativeRefinement()) {
            generatedCode = refineGeneratedCode(generatedCode, promptContext);
        }

        // Validate final code
        if (llmConfig.isValidateGeneratedCode()) {
            CodeValidationResult validation = codeValidator.validate(generatedCode);
            if (!validation.isValid()) {
                log.warn("Generated code validation failed: {}", validation.getErrors());
                // Attempt one more refinement
                generatedCode = fixValidationErrors(generatedCode, validation, promptContext);
            }
        }

        return generatedCode;
    }

    /**
     * Enhanced method that generates tests from a TemporalTrace with temporal information.
     * This provides richer context to the LLM for generating more sophisticated tests.
     *
     * @param enhancedTrace The temporal trace with execution timeline data
     * @param sourceCodePath The path to the source code file
     * @param context The test generation context
     * @return List of generated test file paths
     * @deprecated Use {@link #generateTests(Trace, Path, TestGenerationContext)} instead.
     *             The main method now automatically detects and handles TemporalTrace objects.
     */
    @Deprecated(since = "1.1.0", forRemoval = true)
    public List<Path> generateTests(TemporalTrace enhancedTrace, Path sourceCodePath, TestGenerationContext context) {
        log.warn("generateTests(TemporalTrace, Path, TestGenerationContext) is deprecated. " +
                "Use generateTests(Trace, Path, TestGenerationContext) for regular traces.");

        // Handle TemporalTrace directly since it doesn't extend Trace
        String methodSig = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()";
        log.info("Generating LLM-based tests from TemporalTrace for method: {}", methodSig);

        // Validate all parameters
        validateTemporalTraceParameters(enhancedTrace, sourceCodePath, context);

        try {
            // Read source code
            String sourceCode = Files.readString(sourceCodePath);

            // Build context for LLM prompt with temporal data
            LLMPromptContext promptContext = buildTemporalPromptContext(enhancedTrace, sourceCode, context);

            // Generate and refine test code
            String generatedCode = generateAndRefineCode(promptContext);

            // Write test file
            Path testFile = writeTestFile(generatedCode, context);

            log.info("Successfully generated LLM-based test file from TemporalTrace: {}", testFile);
            return List.of(testFile);

        } catch (TestGenerationWorkflowException e) {
            // Re-throw workflow exceptions
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate LLM-based tests from TemporalTrace", e);
            throw new TestGenerationWorkflowException(
                "LLM-based test generation from TemporalTrace failed: " + e.getMessage(),
                "Test Generation",
                sourceCodePath.toString(),
                (context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()"),
                e
            );
        }
    }

    /**
     * Validates parameters for TemporalTrace-based test generation.
     */
    private void validateTemporalTraceParameters(TemporalTrace trace, Path sourceCodePath, TestGenerationContext context) {
        if (trace == null) {
            throw new TestGenerationWorkflowException("TemporalTrace cannot be null", "Parameter Validation",
                sourceCodePath != null ? sourceCodePath.toString() : null, null);
        }
        if (sourceCodePath == null) {
            throw new TestGenerationWorkflowException("Source code path cannot be null", "Parameter Validation", null, null);
        }
        if (context == null) {
            throw new TestGenerationWorkflowException("Test generation context cannot be null", "Parameter Validation",
                sourceCodePath.toString(), null);
        }
        if (!Files.exists(sourceCodePath)) {
            throw new TestGenerationWorkflowException("Source code path does not exist: " + sourceCodePath,
                "Parameter Validation", sourceCodePath.toString(), null);
        }
        if (!Files.isRegularFile(sourceCodePath)) {
            throw new TestGenerationWorkflowException("Source code path must be a file, not a directory: " + sourceCodePath,
                "Parameter Validation", sourceCodePath.toString(), null);
        }
        if (llmConfig == null) {
            throw new IllegalStateException("LLM configuration must be set before generating tests. Call configure() first.");
        }
    }

    /**
     * Builds prompt context specifically for TemporalTrace data.
     */
    private LLMPromptContext buildTemporalPromptContext(TemporalTrace trace, String sourceCode, TestGenerationContext context) {
        var methodSig = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()";
        var classFqcn = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedClassName() : "UnknownClass";
        var pkg = context.getTargetMethod() != null ? context.getTargetMethod().getPackageName() : "";
        return LLMPromptContext.builder()
                .sourceCode(sourceCode)
                .targetMethodSignature(methodSig)
                .targetClassName(classFqcn)
                .packageName(pkg)
                .testFramework(context.getTestFramework())
                .traceData(formatTemporalTraceData(trace, context))
                .maxTestCount(context.getMaxTestCount())
                .generateEdgeCases(context.isGenerateEdgeCases())
                .generateNegativeTests(context.isGenerateNegativeTests())
                .namingStrategy(context.getNamingStrategy())
                .additionalInstructions("This trace contains temporal execution data with precise event ordering. " +
                                       "Use this information to generate tests that capture the evolution of state over time.")
                .build();
    }

    /**
     * Builds prompt context for LLM with regular Trace data.
     *
     * @param trace The runtime trace data
     * @param sourceCode The source code content
     * @param context The test generation context
     * @return LLM prompt context with trace formatting
     */
    private LLMPromptContext buildPromptContext(Trace trace, String sourceCode, TestGenerationContext context) {
        log.debug("Using basic trace formatting for LLM prompt");
        var methodSig = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()";
        var classFqcn = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedClassName() : "UnknownClass";
        var pkg = context.getTargetMethod() != null ? context.getTargetMethod().getPackageName() : "";
        return LLMPromptContext.builder()
                .sourceCode(sourceCode)
                .targetMethodSignature(methodSig)
                .targetClassName(classFqcn)
                .packageName(pkg)
                .testFramework(context.getTestFramework())
                .traceData(formatTraceData(trace))
                .maxTestCount(context.getMaxTestCount())
                .generateEdgeCases(context.isGenerateEdgeCases())
                .generateNegativeTests(context.isGenerateNegativeTests())
                .namingStrategy(context.getNamingStrategy())
                .build();
    }

    private String formatTraceData(Trace trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime Trace Data:\n");

        // Format trace data for LLM consumption
        sb.append("// Observed runtime values during execution:\n");

        // Add byte values
        trace.getByteValues(0).forEach(value ->
            sb.append("// Byte value observed: ").append(value).append("\n"));

        // Add int values
        trace.getIntValues(0).forEach(value ->
            sb.append("// Int value observed: ").append(value).append("\n"));

        // Add boolean values
        trace.getBooleanValues(0).forEach(value ->
            sb.append("// Boolean value observed: ").append(value).append("\n"));

        // TODO: Add more comprehensive trace formatting based on identifier mapping

        return sb.toString();
    }

    /**
     * Formats TemporalTrace data for LLM consumption with temporal context.
     * Uses configurable limits from TestGenerationContext.
     */
    private String formatTemporalTraceData(TemporalTrace trace, TestGenerationContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Enhanced Runtime Trace Data with Temporal Information:\n");
        sb.append("// ").append(trace.getSummary().replace("\n", "\n// ")).append("\n\n");

        // Get event range for context
        Optional<int[]> eventRange = trace.getEventIndexRange();
        eventRange.ifPresent(range -> sb.append("// Execution timeline: events ").append(range[0]).append(" to ").append(range[1]).append("\n\n"));

        // Use configurable limit for values per variable
        int maxValuesPerVariable = context.getMaxValuesPerVariable();

        // Format variable histories
        for (ExportableValue identifier : trace.getTrackedIdentifiers()) {
            sb.append("// Variable: ").append(((JavaValueIdentifier)identifier).getType()).append("\n");

            var values = trace.getValues(identifier);
            if (values.size() <= maxValuesPerVariable) {
                // Show all values for small histories
                for (Map.Entry<Integer, Object> entry : values.entrySet()) {
                    sb.append("//   Event ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            } else {
                // Show first few, last few, and summary for large histories
                var entryList = new ArrayList<>(values.entrySet());
                int showCount = Math.min(3, maxValuesPerVariable / 2);
                for (int i = 0; i < showCount; i++) {
                    var entry = entryList.get(i);
                    sb.append("//   Event ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                sb.append("//   ... (").append(values.size() - (showCount * 2)).append(" more values) ...\n");
                for (int i = entryList.size() - showCount; i < entryList.size(); i++) {
                    var entry = entryList.get(i);
                    sb.append("//   Event ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }
            sb.append("\n");
        }

        // Add execution scenarios based on state snapshots
        // Use configurable limit for execution scenarios
        int maxExecutionScenarios = context.getMaxExecutionScenarios();
        if (eventRange.isPresent()) {
            int[] range = eventRange.get();
            int sampleCount = Math.min(maxExecutionScenarios, range[1] - range[0] + 1);
            sb.append("// Key execution scenarios:\n");

            for (int i = 0; i < sampleCount; i++) {
                int sampleEvent = range[0] + (i * (range[1] - range[0]) / Math.max(1, sampleCount - 1));
                Map<ExportableValue, Object> snapshot = trace.getStateSnapshotAt(sampleEvent);

                sb.append("//   Scenario at event ").append(sampleEvent).append(":\n");
                for (Map.Entry<ExportableValue, Object> entry : snapshot.entrySet()) {
                    String type = ((JavaValueIdentifier)entry.getKey()).getType();
                    sb.append("//     ").append(type).append(" = ").append(entry.getValue()).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }
    
    private String refineGeneratedCode(String initialCode, LLMPromptContext context) {
        log.debug("Refining generated code through iterative improvement");
        
        String currentCode = initialCode;
        
        for (int iteration = 0; iteration < llmConfig.getMaxRefinementIterations(); iteration++) {
            // Validate current code
            CodeValidationResult validation = codeValidator.validate(currentCode);
            
            if (validation.isValid()) {
                log.debug("Code validation passed after {} iterations", iteration);
                break;
            }
            
            // Build refinement prompt
            String refinementPrompt = promptBuilder.buildRefinementPrompt(currentCode, validation, context);
            
            try {
                currentCode = anthropicClient.generateCode(refinementPrompt);
                log.debug("Completed refinement iteration {}", iteration + 1);
            } catch (Exception e) {
                log.warn("Refinement iteration {} failed: {}", iteration + 1, e.getMessage());
                break;
            }
        }
        
        return currentCode;
    }
    
    private String fixValidationErrors(String code, CodeValidationResult validation, LLMPromptContext context) {
        log.debug("Attempting to fix validation errors");
        
        String fixPrompt = promptBuilder.buildErrorFixPrompt(code, validation, context);
        
        try {
            return anthropicClient.generateCode(fixPrompt);
        } catch (Exception e) {
            log.warn("Failed to fix validation errors: {}", e.getMessage());
            return code; // Return original code if fixing fails
        }
    }
    
    private Path writeTestFile(String testCode, TestGenerationContext context) throws TestGenerationWorkflowException {
        try {
            // Extract class name from generated code
            String className = extractClassNameFromCode(testCode);
            if (className == null) {
                String methodSig = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()";
                className = extractClassNameFromMethod(methodSig) + "Test";
            }

            Path testFile = context.getOutputDirectory().resolve(className + ".java");
            Files.createDirectories(testFile.getParent());
            Files.write(testFile, testCode.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            return testFile;
        } catch (Exception e) {
            throw new TestGenerationWorkflowException(
                "Failed to write test file: " + e.getMessage(),
                "Test File Writing",
                null,
                context.getTargetMethodSignature(),
                e
            );
        }
    }
    
    private String extractClassNameFromCode(String code) {
        // Simple regex to extract class name from "public class ClassName"
        String[] lines = code.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("public class ") && line.contains("{")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    String className = parts[2];
                    if (className.endsWith("{")) {
                        className = className.substring(0, className.length() - 1).trim();
                    }
                    return className;
                }
            }
        }
        return null;
    }
    
    private String extractClassNameFromMethod(String methodSignature) {
        if (methodSignature.contains(".")) {
            return methodSignature.substring(0, methodSignature.lastIndexOf('.'));
        }
        return "UnknownClass";
    }
}
