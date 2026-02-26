package cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace;

import cz.cuni.mff.d3s.autodebugger.model.common.RunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.TemporalTrace;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaArgumentIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaFieldIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaMethodIdentifier;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContext;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerationContextFactory;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestGenerator;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestNamingStrategy;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.JavaTestGenerationContextFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enhanced trace-based test generator that leverages the temporal aspects
 * of the TemporalTrace to generate more sophisticated test scenarios.
 * <p>
 * This generator can:
 * - Identify method invocation points in the execution timeline
 * - Reconstruct precise state at specific execution points
 * - Generate tests that capture state evolution over time
 * - Create parameterized tests for different execution scenarios
 * <p>
 * This implementation properly implements the TestGenerator interface
 * and can work with both regular Trace and TemporalTrace objects.
 */
@Slf4j
public class TemporalTraceBasedGenerator implements TestGenerator {

    private TestGenerationContext context;
    
    /**
     * Generates tests from an TemporalTrace by identifying method invocation points
     * and reconstructing the application state at those points.
     * 
     * @param trace The enhanced trace containing temporal execution data
     * @param context Test generation configuration
     * @return List of generated test file paths
     */
    public List<Path> generateTests(TemporalTrace trace, TestGenerationContext context) {
        this.context = context;
        String sig = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()";
        log.info("Generating enhanced trace-based tests for method: {}", sig);

        try {
            // Validate inputs
            if (context == null) {
                throw new IllegalArgumentException("TestGenerationContext cannot be null");
            }

            validateTemporalTrace(trace);

            // Analyze the trace to identify test scenarios
            List<TestScenario> scenarios = analyzeTraceForScenarios(trace);

            if (scenarios.isEmpty()) {
                log.warn("No test scenarios found in trace");
                return List.of();
            }

            // Generate test class content
            String testClassContent = generateTestClass(scenarios, trace);

            // Write test file
            Path testFile = writeTestFile(testClassContent);

            log.info("Generated {} test scenarios in file: {}", scenarios.size(), testFile);
            return List.of(testFile);

        } catch (Exception e) {
            log.error("Failed to generate enhanced trace-based tests", e);
            return List.of();
        }
    }

    // TestGenerator interface implementation

    @Override
    public List<Path> generateTests(Trace trace) {
        log.info("Generating temporal trace-based tests from basic Trace");

        // TemporalTrace and Trace are separate classes, so this generator can't work with regular Trace
        log.error("Regular Trace provided to TemporalTraceBasedGenerator. This generator only works with TemporalTrace objects.");
        throw new IllegalArgumentException("TemporalTraceBasedGenerator requires a TemporalTrace object, but received a regular Trace. " +
                "Use generateTests(TemporalTrace, TestGenerationContext) instead or use a different generator for regular Trace objects.");
    }

    @Override
    public List<Path> generateTests(Trace trace, Path sourceCodePath, TestGenerationContext context) {
        log.info("Generating temporal trace-based tests with source code path: {}", sourceCodePath);

        // TemporalTrace and Trace are separate classes, so this generator can't work with regular Trace
        log.error("Regular Trace provided to TemporalTraceBasedGenerator. This generator only works with TemporalTrace objects.");
        throw new IllegalArgumentException("TemporalTraceBasedGenerator requires a TemporalTrace object, but received a regular Trace. " +
                "Use generateTests(TemporalTrace, TestGenerationContext) instead or use a different generator for regular Trace objects.");
    }

    @Override
    public List<Path> generateTests(Trace trace, RunConfiguration configuration) {
        log.info("Generating temporal trace-based tests with RunConfiguration");

        // Create context from configuration
        TestGenerationContext context;
        if (configuration instanceof JavaRunConfiguration javaRunConfiguration) {
            // Use Java-specific factory for better information extraction
            context = JavaTestGenerationContextFactory.createFromJavaRunConfiguration(javaRunConfiguration);
        } else {
            // Fallback to default implementation
            context = TestGenerationContextFactory.createFromRunConfiguration(configuration);
        }

        return generateTests(trace, configuration.getSourceCodePath(), context);
    }

    @Override
    public String getGenerationTechnique() {
        return "Enhanced Temporal Trace-Based";
    }

    @Override
    public void validateTrace(Trace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("Trace cannot be null");
        }

        // Since TemporalTrace and Trace are separate classes, this will always fail for regular Trace
        throw new IllegalArgumentException("TemporalTraceBasedGenerator requires a TemporalTrace object, but received: " +
                trace.getClass().getSimpleName() + ". Use validateTemporalTrace(TemporalTrace) instead.");
    }

    /**
     * Validates a TemporalTrace for test generation.
     *
     * @param temporalTrace The temporal trace to validate
     * @throws IllegalArgumentException if the trace is invalid
     */
    public void validateTemporalTrace(TemporalTrace temporalTrace) {
        if (temporalTrace == null) {
            throw new IllegalArgumentException("TemporalTrace cannot be null");
        }

        // Check if trace has any tracked identifiers
        if (temporalTrace.getTrackedIdentifiers().isEmpty()) {
            throw new IllegalArgumentException("TemporalTrace has no tracked identifiers - cannot generate meaningful tests.");
        }

        // Check if trace has any events
        Optional<int[]> eventRange = temporalTrace.getEventIndexRange();
        if (eventRange.isEmpty()) {
            throw new IllegalArgumentException("TemporalTrace contains no events - cannot generate meaningful tests.");
        }

        log.debug("TemporalTrace validation passed for enhanced trace-based test generation");
    }

    /**
     * Analyzes the trace to identify distinct test scenarios based on
     * method invocation points and state changes.
     */
    private List<TestScenario> analyzeTraceForScenarios(TemporalTrace trace) {
        List<TestScenario> scenarios = new ArrayList<>();
        
        // Get the event range to understand the execution timeline
        Optional<int[]> eventRange = trace.getEventIndexRange();
        if (eventRange.isEmpty()) {
            log.warn("Trace contains no events");
            return scenarios;
        }
        
        int[] range = eventRange.get();
        log.info("Analyzing trace with event range: [{}, {}]", range[0], range[1]);
        
        // Identify potential method invocation points
        List<Integer> invocationPoints = identifyMethodInvocationPoints(trace, range);
        
        // For each invocation point, create a test scenario
        for (int i = 0; i < invocationPoints.size() && scenarios.size() < context.getMaxTestCount(); i++) {
            Integer invocationEvent = invocationPoints.get(i);
            
            // Get the state snapshot just before the method invocation
            Map<ExportableValue, Object> stateSnapshot = trace.getStateSnapshotAt(invocationEvent - 1);
            
            if (!stateSnapshot.isEmpty()) {
                TestScenario scenario = createScenarioFromSnapshot(stateSnapshot, invocationEvent, i + 1);
                scenarios.add(scenario);
                log.debug("Created scenario {} at event {} with {} variables", 
                         i + 1, invocationEvent, stateSnapshot.size());
            }
        }
        
        // If no specific invocation points found, create scenarios from state changes
        if (scenarios.isEmpty()) {
            scenarios.addAll(createScenariosFromStateChanges(trace, range));
        }
        
        return scenarios;
    }
    
    /**
     * Identifies potential method invocation points in the trace.
     * These are typically events where argument values are captured.
     */
    private List<Integer> identifyMethodInvocationPoints(TemporalTrace trace, int[] eventRange) {
        List<Integer> invocationPoints = new ArrayList<>();
        
        // Look for events where ArgumentIdentifiers have values
        Set<ExportableValue> argumentIdentifiers = trace.getTrackedIdentifiers().stream()
                .filter(id -> id instanceof JavaArgumentIdentifier)
                .collect(Collectors.toSet());
        
        if (argumentIdentifiers.isEmpty()) {
            log.debug("No argument identifiers found, using regular intervals");
            // Fallback: create invocation points at regular intervals
            int interval = Math.max(1, (eventRange[1] - eventRange[0]) / 5);
            for (int event = eventRange[0]; event <= eventRange[1]; event += interval) {
                invocationPoints.add(event);
            }
        } else {
            // Find events where argument values were captured
            Set<Integer> argumentEvents = new HashSet<>();
            for (ExportableValue argId : argumentIdentifiers) {
                argumentEvents.addAll(trace.getValues(argId).keySet());
            }
            
            invocationPoints.addAll(argumentEvents);
            invocationPoints.sort(Integer::compareTo);
        }
        
        log.info("Identified {} potential method invocation points", invocationPoints.size());
        return invocationPoints;
    }
    
    /**
     * Creates a test scenario from a state snapshot.
     */
    private TestScenario createScenarioFromSnapshot(Map<ExportableValue, Object> stateSnapshot, 
                                                   int eventIndex, int scenarioNumber) {
        Map<ExportableValue, Object> arguments = new HashMap<>();
        Map<ExportableValue, Object> fields = new HashMap<>();
        
        // Separate arguments from fields
        for (Map.Entry<ExportableValue, Object> entry : stateSnapshot.entrySet()) {
            ExportableValue identifier = entry.getKey();
            Object value = entry.getValue();
            
            if (identifier instanceof JavaArgumentIdentifier) {
                arguments.put(identifier, value);
            } else if (identifier instanceof JavaFieldIdentifier) {
                fields.put(identifier, value);
            }
        }
        
        return new TestScenario(arguments, fields, eventIndex, scenarioNumber);
    }
    
    /**
     * Creates scenarios based on significant state changes in the trace.
     */
    private List<TestScenario> createScenariosFromStateChanges(TemporalTrace trace, int[] eventRange) {
        List<TestScenario> scenarios = new ArrayList<>();
        
        // Sample the trace at different points to capture state evolution
        // Use configurable maxStateChangeSamples instead of hardcoded 5
        int maxSamples = context.getMaxStateChangeSamples();
        int sampleCount = Math.min(context.getMaxTestCount(), maxSamples);
        int interval = Math.max(1, (eventRange[1] - eventRange[0]) / sampleCount);
        
        for (int i = 0; i < sampleCount; i++) {
            int sampleEvent = eventRange[0] + (i * interval);
            Map<ExportableValue, Object> snapshot = trace.getStateSnapshotAt(sampleEvent);
            
            if (!snapshot.isEmpty()) {
                TestScenario scenario = createScenarioFromSnapshot(snapshot, sampleEvent, i + 1);
                scenarios.add(scenario);
            }
        }
        
        log.info("Created {} scenarios from state changes", scenarios.size());
        return scenarios;
    }
    
    /**
     * Generates the complete test class content.
     */
    private String generateTestClass(List<TestScenario> scenarios, TemporalTrace trace) {
        StringBuilder sb = new StringBuilder();
        
        // Package declaration
        String pkg = context.getTargetMethod() != null ? context.getTargetMethod().getPackageName() : "";
        if (pkg != null && !pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }
        
        // Imports
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import org.junit.jupiter.api.BeforeEach;\n");
        sb.append("import org.junit.jupiter.api.DisplayName;\n");
        sb.append("import static org.junit.jupiter.api.Assertions.*;\n\n");
        
        // Class declaration with documentation
        String sig = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()";
        String testClassName = extractClassNameFromMethod(sig) + "EnhancedTest";
        sb.append("/**\n");
        sb.append(" * Enhanced trace-based test class for ").append(sig).append("\n");
        sb.append(" * Generated on: ").append(LocalDateTime.now()).append("\n");
        sb.append(" * Generation strategy: Enhanced Trace-Based\n");
        sb.append(" * Trace summary: ").append(trace.getSummary().replace("\n", "\n * ")).append("\n");
        sb.append(" */\n");
        sb.append("public class ").append(testClassName).append(" {\n\n");

        // Instance variable for the class under test (skip for static methods)
        String targetClass = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedClassName() : "UnknownClass";
        String instanceName = targetClass.substring(targetClass.lastIndexOf('.') + 1).toLowerCase();

        if (!isStaticMethod()) {
            sb.append("    private ").append(targetClass).append(" ").append(instanceName).append(";\n\n");

            // Setup method
            sb.append("    @BeforeEach\n");
            sb.append("    void setUp() {\n");
            sb.append("        // TODO: Initialize ").append(instanceName).append(" with appropriate constructor\n");
            sb.append("        // ").append(instanceName).append(" = new ").append(targetClass).append("();\n");
            sb.append("    }\n\n");
        }
        
        // Generate test methods for each scenario
        for (TestScenario scenario : scenarios) {
            sb.append(generateTestMethod(scenario, instanceName));
            sb.append("\n");
        }
        
        sb.append("}\n");
        
        return sb.toString();
    }
    
    /**
     * Generates a single test method for a scenario.
     */
    private String generateTestMethod(TestScenario scenario, String instanceName) {
        StringBuilder sb = new StringBuilder();
        
        String methodName = generateTestMethodName(scenario);
        String displayName = generateDisplayName(scenario);
        
        sb.append("    @Test\n");
        sb.append("    @DisplayName(\"").append(displayName).append("\")\n");
        sb.append("    void ").append(methodName).append("() {\n");
        sb.append("        // Arrange - State captured at event ").append(scenario.eventIndex).append("\n");
        
        // Set up field values
        for (Map.Entry<ExportableValue, Object> field : scenario.fields.entrySet()) {
            if (field.getKey() instanceof JavaFieldIdentifier fieldId) {
                sb.append("        // TODO: Set field ").append(fieldId.getFieldName())
                  .append(" to ").append(formatValueForComment(field.getValue())).append("\n");
            }
        }
        
        sb.append("\n        // Act\n");
        
        // Generate method call with arguments
        String methodCall = generateMethodCall(scenario, instanceName);
        sb.append("        ").append(methodCall).append("\n");
        
        sb.append("\n        // Assert\n");
        if (isVoidReturnType()) {
            sb.append("        // Method returns void - verifies execution without exception\n");
            sb.append("        // TODO: Add assertions to verify side effects (e.g., field changes)\n");
        } else {
            sb.append("        assertNotNull(result, \"Method should return a non-null result\");\n");
            sb.append("        // TODO: Add assertions based on expected behavior at event ")
              .append(scenario.eventIndex).append("\n");
            sb.append("        // Captured state suggests specific outcomes based on these inputs\n");
        }

        sb.append("    }\n");
        
        return sb.toString();
    }

    private boolean isVoidReturnType() {
        if (context.getTargetMethod() == null) {
            return false;
        }
        String returnType = context.getTargetMethod().getReturnType();
        return "void".equals(returnType);
    }

    private boolean isStaticMethod() {
        if (context.getTargetMethod() instanceof JavaMethodIdentifier javaMethod) {
            return javaMethod.isStatic();
        }
        return false;
    }

    private String getSimpleClassName() {
        if (context.getTargetMethod() != null) {
            return context.getTargetMethod().getClassName();
        }
        return "UnknownClass";
    }

    private String generateTestMethodName(TestScenario scenario) {
        String baseName = extractMethodNameFromSignature(context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "unknownMethod");
        
        if (context.getNamingStrategy() == TestNamingStrategy.SIMPLE) {
            return "test" + capitalize(baseName) + "Scenario" + scenario.scenarioNumber;
        } else if (context.getNamingStrategy() == TestNamingStrategy.DESCRIPTIVE) {
            return "test" + capitalize(baseName) + "AtEvent" + scenario.eventIndex;
        } else {
            return "test" + capitalize(baseName) + "_Event" + scenario.eventIndex + "_Scenario" + scenario.scenarioNumber;
        }
    }
    
    private String generateDisplayName(TestScenario scenario) {
        String methodName = extractMethodNameFromSignature(context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "unknownMethod");
        return String.format("Test %s at execution event %d (scenario %d)", 
                            methodName, scenario.eventIndex, scenario.scenarioNumber);
    }
    
    private String generateMethodCall(TestScenario scenario, String instanceName) {
        String methodName = extractMethodNameFromSignature(context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : context.getTargetMethodSignature());

        StringBuilder call = new StringBuilder();
        if (!isVoidReturnType()) {
            call.append("var result = ");
        }
        String targetName = isStaticMethod() ? getSimpleClassName() : instanceName;
        call.append(targetName).append(".").append(methodName).append("(");
        
        // Add arguments from the scenario
        List<String> args = scenario.arguments.entrySet().stream()
                .filter(entry -> entry.getKey() instanceof JavaArgumentIdentifier)
                .map(entry -> formatValueForCode(entry.getValue()))
                .collect(Collectors.toList());
        
        call.append(String.join(", ", args));
        call.append(");");
        
        return call.toString();
    }
    
    private String formatValueForCode(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return "\"" + value.toString().replace("\"", "\\\"") + "\"";
        } else if (value instanceof Character) {
            return "'" + value + "'";
        } else {
            return value.toString();
        }
    }
    
    private String formatValueForComment(Object value) {
        return value != null ? value.toString() : "null";
    }
    
    private Path writeTestFile(String testClassContent) throws IOException {
        String methodSignatureForClass = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()";
        String testClassName = extractClassNameFromMethod(methodSignatureForClass) + "EnhancedTest";
        Path testFile = context.getOutputDirectory().resolve(testClassName + ".java");
        
        Files.createDirectories(testFile.getParent());
        Files.write(testFile, testClassContent.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        return testFile;
    }
    
    private String extractClassNameFromMethod(String methodSignature) {
        if (methodSignature.contains(".")) {
            return methodSignature.substring(0, methodSignature.lastIndexOf('.'));
        }
        return "UnknownClass";
    }
    
    private String extractMethodNameFromSignature(String methodSignature) {
        if (methodSignature.contains(".")) {
            String methodPart = methodSignature.substring(methodSignature.lastIndexOf('.') + 1);
            if (methodPart.contains("(")) {
                return methodPart.substring(0, methodPart.indexOf('('));
            }
            return methodPart;
        }
        return methodSignature;
    }
    
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
    
    /**
     * Enhanced test scenario that includes temporal information.
     */
    private static class TestScenario {
        final Map<ExportableValue, Object> arguments;
        final Map<ExportableValue, Object> fields;
        final int eventIndex;
        final int scenarioNumber;
        
        TestScenario(Map<ExportableValue, Object> arguments, Map<ExportableValue, Object> fields, 
                    int eventIndex, int scenarioNumber) {
            this.arguments = arguments;
            this.fields = fields;
            this.eventIndex = eventIndex;
            this.scenarioNumber = scenarioNumber;
        }
    }
}
