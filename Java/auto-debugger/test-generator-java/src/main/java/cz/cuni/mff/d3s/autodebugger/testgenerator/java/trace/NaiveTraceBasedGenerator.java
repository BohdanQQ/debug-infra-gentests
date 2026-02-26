package cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace;

import cz.cuni.mff.d3s.autodebugger.model.common.RunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.ObjectSnapshot;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaArgumentIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaFieldIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaMethodIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaValueIdentifier;
import cz.cuni.mff.d3s.autodebugger.testgenerator.common.*;
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.JavaTestGenerationContextFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Naive trace-based test generator that creates simple unit tests
 * by replicating observed method calls with collected runtime values.
 */
@Slf4j
public class NaiveTraceBasedGenerator implements TestGenerator {

    private final Map<Integer, JavaValueIdentifier> identifierMapping;
    private TestGenerationContext context;
    private Set<String> objectImports = new HashSet<>();

    public NaiveTraceBasedGenerator(Map<Integer, JavaValueIdentifier> identifierMapping) {
        this.identifierMapping = identifierMapping;
    }

    /**
     * Convenience constructor that loads identifier mapping from a serialized file.
     */
    @SuppressWarnings("unchecked")
    public NaiveTraceBasedGenerator(Path identifierMappingFile) {
        Map<Integer, JavaValueIdentifier> loaded;
        try {
            try (java.io.FileInputStream fileReader = new java.io.FileInputStream(identifierMappingFile.toFile());
                 java.io.ObjectInputStream objectStream = new java.io.ObjectInputStream(fileReader)) {
                loaded = (HashMap<Integer, JavaValueIdentifier>) objectStream.readObject();
            }
        } catch (Exception e) {
            log.error("Failed to load identifier mapping from {}", identifierMappingFile, e);
            throw new RuntimeException("Failed to load identifier mapping", e);
        }
        this.identifierMapping = loaded;
    }



    @Override
    public List<Path> generateTests(Trace trace) {
        return generateTests(trace, createDefaultContext());
    }

    @Override
    public List<Path> generateTests(Trace trace, Path sourceCodePath, TestGenerationContext context) {
        log.info("Generating naive trace-based tests with source code path: {}", sourceCodePath);
        // Source code path is not used by naive generator, delegate to context-based method
        return generateTests(trace, context);
    }

    /**
     * Override the default RunConfiguration method to use Java-specific factory.
     * This ensures that JavaMethodIdentifier utility methods are used for accurate information extraction.
     */
    @Override
    public List<Path> generateTests(Trace trace, RunConfiguration configuration) {
        if (configuration instanceof JavaRunConfiguration javaRunConfiguration) {
            // Use Java-specific factory for better information extraction
            TestGenerationContext context = JavaTestGenerationContextFactory
                    .createFromJavaRunConfiguration(javaRunConfiguration);
            return generateTests(trace, context);
        } else {
            // Fallback to default implementation
            return TestGenerator.super.generateTests(trace, configuration);
        }
    }

    @Override
    public String getGenerationTechnique() {
        return "Naive Trace-Based";
    }

    @Override
    public void validateTrace(Trace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("Trace cannot be null");
        }

        // Check if trace has any captured values
        if (isTraceEmpty(trace)) {
            throw new IllegalArgumentException("Trace is empty - no captured values found. Cannot generate meaningful tests.");
        }

        log.debug("Trace validation passed for naive trace-based test generation");
    }

    /**
     * Generates tests using TestGenerationContext.
     * This method provides the core functionality for test generation.
     *
     * @param trace The runtime trace data
     * @param context The test generation context
     * @return List of generated test file paths
     */
    public List<Path> generateTests(Trace trace, TestGenerationContext context) {
        this.context = context;
        this.objectImports = new HashSet<>();  // Reset object imports for each test generation
        String methodSig = context.getTargetMethod() != null
                ? context.getTargetMethod().getFullyQualifiedSignature()
                : "UnknownClass.unknownMethod()";
        log.info("Generating naive trace-based tests for method: {}", methodSig);

        // Check if trace is empty
        if (trace == null || isTraceEmpty(trace)) {
            log.warn("Trace is empty or null - cannot generate meaningful tests");
            throw new IllegalArgumentException("Cannot generate tests from empty trace. Trace must contain at least one captured value.");
        }

        try {
            TraceIdentifierMapper mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // Group values by execution scenarios
            List<TestScenario> scenarios = extractTestScenarios(mapper);

            if (scenarios.isEmpty()) {
                log.warn("No test scenarios could be extracted from trace");
                throw new IllegalArgumentException("No test scenarios could be extracted from the provided trace");
            }

            // Generate test class
            String testClassContent = generateTestClass(scenarios);

            // Write test file
            Path testFile = writeTestFile(testClassContent);

            log.info("Generated {} test scenarios in file: {}", scenarios.size(), testFile);
            return List.of(testFile);

        } catch (Exception e) {
            log.error("Failed to generate naive trace-based tests", e);
            throw new RuntimeException("Test generation failed: " + e.getMessage(), e);
        }
    }

    private boolean isTraceEmpty(Trace trace) {
        // Check if trace has any values in any slot
        // We need to check all possible slots, but for practical purposes,
        // we'll check a reasonable range of slots (0-100)
        for (int slot = 0; slot < 100; slot++) {
            if (!trace.getIntValues(slot).isEmpty() ||
                !trace.getLongValues(slot).isEmpty() ||
                !trace.getBooleanValues(slot).isEmpty() ||
                !trace.getFloatValues(slot).isEmpty() ||
                !trace.getDoubleValues(slot).isEmpty() ||
                !trace.getCharValues(slot).isEmpty() ||
                !trace.getByteValues(slot).isEmpty() ||
                !trace.getShortValues(slot).isEmpty() ||
                !trace.getStringValues(slot).isEmpty() ||
                !trace.getObjectValues(slot).isEmpty()) {
                return false; // Found at least one value
            }
        }
        return true; // No values found in any checked slot
    }
    
    private List<TestScenario> extractTestScenarios(TraceIdentifierMapper mapper) {
        List<TestScenario> scenarios = new ArrayList<>();

        // Get all argument and field identifiers
        Map<Integer, JavaArgumentIdentifier> arguments = new HashMap<>();
        Map<Integer, JavaFieldIdentifier> fields = new HashMap<>();

        for (Integer slot : mapper.getSlots()) {
            ExportableValue value = mapper.getExportableValue(slot);
            if (value instanceof JavaArgumentIdentifier arg) {
                arguments.put(slot, arg);
            } else if (value instanceof JavaFieldIdentifier field) {
                fields.put(slot, field);
            }
        }

        // Create test scenarios based on unique combinations of values
        Set<String> uniqueScenarios = new HashSet<>();

        if (!arguments.isEmpty()) {
            // Create one scenario per unique combination of argument values
            List<Map<Integer, Object>> argumentCombinations = generateArgumentCombinations(arguments, mapper);

            for (Map<Integer, Object> argCombo : argumentCombinations) {
                Map<Integer, Object> fieldCombo = extractFieldValues(fields, mapper);

                String scenarioKey = createScenarioKey(argCombo, fieldCombo);
                if (!uniqueScenarios.contains(scenarioKey)) {
                    uniqueScenarios.add(scenarioKey);
                    scenarios.add(new TestScenario(argCombo, fieldCombo, scenarios.size() + 1));
                }
            }
        } else if (!fields.isEmpty()) {
            // Field-only scenario: create scenarios based on unique field value combinations
            List<Map<Integer, Object>> fieldCombinations = generateFieldCombinations(fields, mapper);

            for (Map<Integer, Object> fieldCombo : fieldCombinations) {
                String scenarioKey = createScenarioKey(new HashMap<>(), fieldCombo);
                if (!uniqueScenarios.contains(scenarioKey)) {
                    uniqueScenarios.add(scenarioKey);
                    scenarios.add(new TestScenario(new HashMap<>(), fieldCombo, scenarios.size() + 1));
                }
            }
        }

        return scenarios;
    }

    private List<Map<Integer, Object>> generateFieldCombinations(Map<Integer, JavaFieldIdentifier> fields,
                                                                 TraceIdentifierMapper mapper) {
        List<Map<Integer, Object>> combinations = new ArrayList<>();

        // Get all possible values for each field
        Map<Integer, List<Object>> fieldValues = new HashMap<>();
        for (Map.Entry<Integer, JavaFieldIdentifier> entry : fields.entrySet()) {
            Integer slot = entry.getKey();
            Set<?> values = mapper.getSlotValues(slot);
            fieldValues.put(slot, new ArrayList<>(values));
        }

        // Generate combinations (limit configurable via context to avoid explosion)
        if (!fieldValues.isEmpty()) {
            List<Integer> slots = new ArrayList<>(fieldValues.keySet());
            int maxCombinations = context != null ? context.getMaxFieldCombinations() : 20;
            generateCombinationsRecursive(slots, 0, new HashMap<>(), fieldValues, combinations, maxCombinations);
        }

        return combinations;
    }
    
    private List<Map<Integer, Object>> generateArgumentCombinations(Map<Integer, JavaArgumentIdentifier> arguments,
                                                                   TraceIdentifierMapper mapper) {
        List<Map<Integer, Object>> combinations = new ArrayList<>();
        
        // Get all possible values for each argument
        Map<Integer, List<Object>> argumentValues = new HashMap<>();
        for (Map.Entry<Integer, JavaArgumentIdentifier> entry : arguments.entrySet()) {
            Integer slot = entry.getKey();
            Set<?> values = mapper.getSlotValues(slot);
            argumentValues.put(slot, new ArrayList<>(values));
        }
        
        // Generate combinations (limit configurable via context)
        if (!argumentValues.isEmpty()) {
            List<Integer> slots = new ArrayList<>(argumentValues.keySet());
            int maxCombinations = context != null ? context.getMaxArgumentCombinations() : 10;
            generateCombinationsRecursive(slots, 0, new HashMap<>(), argumentValues, combinations, maxCombinations);
        }
        
        return combinations;
    }
    
    private void generateCombinationsRecursive(List<Integer> slots, int index, 
                                             Map<Integer, Object> current,
                                             Map<Integer, List<Object>> allValues,
                                             List<Map<Integer, Object>> combinations,
                                             int maxCombinations) {
        if (combinations.size() >= maxCombinations) {
            return;
        }
        
        if (index >= slots.size()) {
            combinations.add(new HashMap<>(current));
            return;
        }
        
        Integer slot = slots.get(index);
        List<Object> values = allValues.get(slot);
        
        for (Object value : values) {
            current.put(slot, value);
            generateCombinationsRecursive(slots, index + 1, current, allValues, combinations, maxCombinations);
            current.remove(slot);
            
            if (combinations.size() >= maxCombinations) {
                break;
            }
        }
    }
    
    private Map<Integer, Object> extractFieldValues(Map<Integer, JavaFieldIdentifier> fields,
                                                   TraceIdentifierMapper mapper) {
        Map<Integer, Object> fieldValues = new HashMap<>();
        
        for (Map.Entry<Integer, JavaFieldIdentifier> entry : fields.entrySet()) {
            Integer slot = entry.getKey();
            Set<?> values = mapper.getSlotValues(slot);
            if (!values.isEmpty()) {
                fieldValues.put(slot, values.iterator().next()); // Take first value
            }
        }
        
        return fieldValues;
    }
    
    private String createScenarioKey(Map<Integer, Object> arguments, Map<Integer, Object> fields) {
        StringBuilder key = new StringBuilder();
        key.append("args:").append(arguments.toString());
        key.append("|fields:").append(fields.toString());
        return key.toString();
    }
    
    private String generateTestClass(List<TestScenario> scenarios) {
        // First, generate all test methods to collect object imports
        String targetClass = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedClassName() : "UnknownClass";
        String instanceName = targetClass.substring(targetClass.lastIndexOf('.') + 1).toLowerCase();

        StringBuilder testMethodsBuilder = new StringBuilder();
        for (TestScenario scenario : scenarios) {
            testMethodsBuilder.append(generateTestMethod(scenario, instanceName));
            testMethodsBuilder.append("\n");
        }
        String testMethods = testMethodsBuilder.toString();

        // Now build the complete class with imports
        StringBuilder sb = new StringBuilder();

        // Package declaration
        String pkg = context.getTargetMethod() != null ? context.getTargetMethod().getPackageName() : "";
        if (pkg != null && !pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }

        // Imports
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import org.junit.jupiter.api.BeforeEach;\n");
        sb.append("import static org.junit.jupiter.api.Assertions.*;\n");

        // Add object imports (now populated from test method generation)
        for (String objectImport : objectImports) {
            sb.append("import ").append(objectImport).append(";\n");
        }
        sb.append("\n");

        // Class declaration
        String testClassSig = context.getTargetMethod() != null
                ? context.getTargetMethod().getFullyQualifiedSignature()
                : "UnknownClass.unknownMethod()";
        String testClassName = extractClassNameFromMethod(testClassSig) + "Test";
        sb.append("/**\n");
        sb.append(" * Generated test class for ").append(testClassSig).append("\n");
        sb.append(" * Generated on: ").append(LocalDateTime.now()).append("\n");
        sb.append(" * Generation strategy: Naive Trace-Based\n");
        sb.append(" */\n");
        sb.append("public class ").append(testClassName).append(" {\n\n");

        // Instance variable for the class under test (skip for static methods)
        if (!isStaticMethod()) {
            sb.append("    private ").append(targetClass).append(" ").append(instanceName).append(";\n\n");

            // Setup method
            sb.append("    @BeforeEach\n");
            sb.append("    void setUp() {\n");
            sb.append("        // TODO: Initialize ").append(instanceName).append(" with appropriate constructor\n");
            sb.append("        // ").append(instanceName).append(" = new ").append(targetClass).append("();\n");
            sb.append("    }\n\n");
        }

        // Add the pre-generated test methods
        sb.append(testMethods);

        sb.append("}\n");

        return sb.toString();
    }
    
    private String generateTestMethod(TestScenario scenario, String instanceName) {
        StringBuilder sb = new StringBuilder();

        String methodName = generateTestMethodName(scenario);
        boolean isVoidMethod = isVoidReturnType();

        sb.append("    @Test\n");
        sb.append("    void ").append(methodName).append("() {\n");
        sb.append("        // Arrange\n");

        // Set up field values if any
        for (Map.Entry<Integer, Object> field : scenario.fieldValues.entrySet()) {
            ExportableValue fieldId = identifierMapping.get(field.getKey());
            if (fieldId instanceof JavaFieldIdentifier fieldIdentifier) {
                sb.append("        // TODO: Set field ").append(fieldIdentifier.getFieldName())
                  .append(" to ").append(field.getValue()).append("\n");
            }
        }

        sb.append("\n        // Act\n");

        // Generate method call
        String methodCall = generateMethodCall(scenario, instanceName, isVoidMethod);
        sb.append("        ").append(methodCall).append("\n");

        sb.append("\n        // Assert\n");
        if (isVoidMethod) {
            sb.append("        // Method returns void - test verifies execution completes without exception\n");
            sb.append("        // Note: Add assertions to verify side effects (e.g., field changes)\n");
        } else {
            sb.append("        // Basic assertion to verify method execution completed without exception\n");
            sb.append("        assertNotNull(result, \"Method should return a non-null result\");\n");
            sb.append("        // Note: Add more specific assertions based on expected behavior\n");
        }

        sb.append("    }\n");

        return sb.toString();
    }

    private boolean isVoidReturnType() {
        if (context.getTargetMethod() == null) {
            return false;
        }
        String returnType = context.getTargetMethod().getReturnType();
        // Only treat as void when explicitly set to "void"
        // Unknown return types (null, empty) are treated as non-void for safety
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
        if (context.getNamingStrategy() == TestNamingStrategy.SIMPLE) {
            return "test" + scenario.scenarioNumber;
        }
        
        StringBuilder name = new StringBuilder("test");
        String methodName = extractMethodNameFromSignature(context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "unknownMethod");
        name.append(capitalize(methodName));
        
        if (context.getNamingStrategy() == TestNamingStrategy.PARAMETER_BASED) {
            name.append("_");
            for (Object value : scenario.argumentValues.values()) {
                name.append(sanitizeValueForMethodName(value)).append("_");
            }
            if (name.toString().endsWith("_")) {
                name.setLength(name.length() - 1);
            }
        } else {
            name.append("_Scenario").append(scenario.scenarioNumber);
        }
        
        return name.toString();
    }
    
    private String generateMethodCall(TestScenario scenario, String instanceName, boolean isVoidMethod) {
        String methodName = extractMethodNameFromSignature(context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "unknownMethod");

        StringBuilder call = new StringBuilder();
        if (!isVoidMethod) {
            call.append("var result = ");
        }
        String targetName = isStaticMethod() ? getSimpleClassName() : instanceName;
        call.append(targetName).append(".").append(methodName).append("(");

        // Add arguments in order
        List<String> args = new ArrayList<>();
        for (Map.Entry<Integer, Object> arg : scenario.argumentValues.entrySet()) {
            ExportableValue argId = identifierMapping.get(arg.getKey());
            if (argId instanceof JavaArgumentIdentifier) {
                args.add(formatValueForCode(arg.getValue()));
            }
        }

        call.append(String.join(", ", args));
        call.append(");");

        return call.toString();
    }
    
    private String formatValueForCode(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof ObjectSnapshot) {
            return formatObjectSnapshot((ObjectSnapshot) value);
        } else if (value instanceof String) {
            return "\"" + value.toString().replace("\"", "\\\"") + "\"";
        } else if (value instanceof Character) {
            char c = (Character) value;
            // Handle special characters that need escaping
            return switch (c) {
                case '\n' -> "'\\n'";
                case '\t' -> "'\\t'";
                case '\r' -> "'\\r'";
                case '\\' -> "'\\\\'";
                case '\'' -> "'\\''";
                default -> "'" + c + "'";
            };
        } else if (value instanceof Long) {
            return value.toString() + "L";
        } else if (value instanceof Float) {
            return value.toString() + "f";
        } else if (value instanceof Double) {
            String doubleStr = value.toString();
            // Ensure double literals have decimal point
            if (!doubleStr.contains(".") && !doubleStr.contains("E") && !doubleStr.contains("e")) {
                doubleStr += ".0";
            }
            return doubleStr;
        } else if (value instanceof Byte) {
            return "(byte) " + value.toString();
        } else if (value instanceof Short) {
            return "(short) " + value.toString();
        } else {
            return value.toString();
        }
    }

    /**
     * Formats an ObjectSnapshot for code generation.
     * Generates object construction code with captured field values as constructor arguments.
     * Note: This assumes a constructor exists that takes the fields in the order they were captured.
     *
     * @param snapshot The ObjectSnapshot to format
     * @return Java code string for object construction
     */
    private String formatObjectSnapshot(ObjectSnapshot snapshot) {
        String className = snapshot.getClassName();
        String simpleClassName = snapshot.getSimpleClassName();

        // Add import for this class
        objectImports.add(className);

        StringBuilder code = new StringBuilder();
        code.append("new ").append(simpleClassName).append("(");

        // Generate constructor arguments from captured field values
        // Skip special fields like $class, $value, $cycle, $ref
        Map<String, Object> fields = snapshot.getFields();
        List<String> fieldNames = new ArrayList<>();
        List<Object> constructorArgs = new ArrayList<>();

        for (Map.Entry<String, Object> field : fields.entrySet()) {
            String fieldName = field.getKey();
            // Skip internal/metadata fields
            if (fieldName.startsWith("$")) {
                continue;
            }
            fieldNames.add(fieldName);
            constructorArgs.add(field.getValue());
        }

        // Generate constructor call with captured values
        boolean first = true;
        for (Object arg : constructorArgs) {
            if (!first) {
                code.append(", ");
            }
            first = false;
            code.append(formatConstructorArg(arg));
        }

        code.append(")");

        // Add TODO comment with field names to help verify constructor signature
        if (!fieldNames.isEmpty()) {
            code.append(" /* TODO: verify constructor signature matches (");
            code.append(String.join(", ", fieldNames));
            code.append(") */");
        }

        log.debug("Formatted ObjectSnapshot for class {}: {}", className, code);
        return code.toString();
    }

    /**
     * Formats a value as a constructor argument.
     */
    private String formatConstructorArg(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof ObjectSnapshot nestedSnapshot) {
            // Recursively format nested objects
            return formatObjectSnapshot(nestedSnapshot);
        } else if (value instanceof String) {
            return "\"" + escapeString((String) value) + "\"";
        } else if (value instanceof Character) {
            char c = (Character) value;
            return switch (c) {
                case '\n' -> "'\\n'";
                case '\t' -> "'\\t'";
                case '\r' -> "'\\r'";
                case '\\' -> "'\\\\'";
                case '\'' -> "'\\''";
                default -> "'" + c + "'";
            };
        } else if (value instanceof Long) {
            return value.toString() + "L";
        } else if (value instanceof Float) {
            return value.toString() + "f";
        } else if (value instanceof Double) {
            return value.toString() + "d";
        } else {
            return value.toString();
        }
    }

    /**
     * Escapes special characters in a string for Java code.
     */
    private String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }


    private Path writeTestFile(String testClassContent) throws IOException {
        String sig = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedSignature() : "UnknownClass.unknownMethod()";
        String testClassName = extractClassNameFromMethod(sig) + "Test";
        Path testFile = context.getOutputDirectory().resolve(testClassName + ".java");
        
        Files.createDirectories(testFile.getParent());
        Files.write(testFile, testClassContent.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        return testFile;
    }
    
    private TestGenerationContext createDefaultContext() {
        return TestGenerationContext.builder()
                .outputDirectory(Path.of("generated-tests"))
                .build();
    }
    
    private String extractClassNameFromMethod(String methodSignature) {
        // Extract just the class name (not the full qualified name)
        if (methodSignature.contains(".")) {
            String fullClassName = methodSignature.substring(0, methodSignature.lastIndexOf('.'));
            // Return only the simple class name, not the full package path
            if (fullClassName.contains(".")) {
                return fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
            }
            return fullClassName;
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
    
    private String sanitizeValueForMethodName(Object value) {
        if (value == null) {
            return "Null";
        }
        return value.toString().replaceAll("[^a-zA-Z0-9]", "");
    }
    
    private static class TestScenario {
        final Map<Integer, Object> argumentValues;
        final Map<Integer, Object> fieldValues;
        final int scenarioNumber;
        
        TestScenario(Map<Integer, Object> argumentValues, Map<Integer, Object> fieldValues, int scenarioNumber) {
            this.argumentValues = argumentValues;
            this.fieldValues = fieldValues;
            this.scenarioNumber = scenarioNumber;
        }
    }
}
