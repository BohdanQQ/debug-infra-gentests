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
import cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace.exceptions.TestGenerationWorkflowException;

import lombok.extern.slf4j.Slf4j;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.File;
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
        log.info("Generating naive trace-based tests without additional context");
        throw new TestGenerationWorkflowException("Naive trace-based test generation requires source code path and context. Use generateTests(Trace, Path, TestGenerationContext) instead.");
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
            return generateTests(trace, configuration.getSourceCodePath(), context);
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
     * @param sourceCodeDirectory The source code directory containing the target class
     * @param context The test generation context
     * @return List of generated test file paths
     */
    @Override
    public List<Path> generateTests(Trace trace, Path sourceCodeDirectory, TestGenerationContext context) {
        log.info("Generating naive trace-based tests with source code path: {}", sourceCodeDirectory);

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
            String testClassContent = generateTestClass(scenarios, sourceCodeDirectory);

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
        for (int slotId = 0; slotId < 100; slotId++) {
            if (!trace.getIntValues(slotId).isEmpty() ||
                !trace.getLongValues(slotId).isEmpty() ||
                !trace.getBooleanValues(slotId).isEmpty() ||
                !trace.getFloatValues(slotId).isEmpty() ||
                !trace.getDoubleValues(slotId).isEmpty() ||
                !trace.getCharValues(slotId).isEmpty() ||
                !trace.getByteValues(slotId).isEmpty() ||
                !trace.getShortValues(slotId).isEmpty() ||
                !trace.getStringValues(slotId).isEmpty() ||
                !trace.getObjectValues(slotId).isEmpty()) {
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

        for (Integer slotId : mapper.getSlotIDs()) {
            ExportableValue value = mapper.getExportableValue(slotId);
            if (value instanceof JavaArgumentIdentifier arg) {
                arguments.put(slotId, arg);
            } else if (value instanceof JavaFieldIdentifier field) {
                fields.put(slotId, field);
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
            Integer slotId = entry.getKey();
            Set<?> values = mapper.getSlotValues(slotId);
            fieldValues.put(slotId, new ArrayList<>(values));
        }

        // Generate combinations (limit configurable via context to avoid explosion)
        if (!fieldValues.isEmpty()) {
            List<Integer> slotIDs = new ArrayList<>(fieldValues.keySet());
            int maxCombinations = context != null ? context.getMaxFieldCombinations() : Integer.MAX_VALUE;
            generateCombinationsRecursive(slotIDs, 0, new HashMap<>(), fieldValues, combinations, maxCombinations);
        }

        return combinations;
    }
    
    private List<Map<Integer, Object>> generateArgumentCombinations(Map<Integer, JavaArgumentIdentifier> arguments,
                                                                   TraceIdentifierMapper mapper) {
        List<Map<Integer, Object>> combinations = new ArrayList<>();
        
        // Get all possible values for each argument
        Map<Integer, List<Object>> argumentValues = new HashMap<>();
        for (Map.Entry<Integer, JavaArgumentIdentifier> entry : arguments.entrySet()) {
            Integer slotId = entry.getKey();
            Set<?> values = mapper.getSlotValues(slotId);
            argumentValues.put(slotId, new ArrayList<>(values));
        }
        
        // Generate combinations (limit configurable via context)
        if (!argumentValues.isEmpty()) {
            List<Integer> slotIDs = new ArrayList<>(argumentValues.keySet());
            int maxCombinations = context != null ? context.getMaxArgumentCombinations() : Integer.MAX_VALUE;
            generateCombinationsRecursive(slotIDs, 0, new HashMap<>(), argumentValues, combinations, maxCombinations);
        }
        
        return combinations;
    }
    
    private void generateCombinationsRecursive(List<Integer> slotIDs, int index, 
                                             Map<Integer, Object> current,
                                             Map<Integer, List<Object>> allValues,
                                             List<Map<Integer, Object>> combinations,
                                             int maxCombinations) {
        if (combinations.size() >= maxCombinations) {
            return;
        }
        
        if (index >= slotIDs.size()) {
            combinations.add(new HashMap<>(current));
            return;
        }
        
        Integer slotId = slotIDs.get(index);
        List<Object> values = allValues.get(slotId);
        
        for (Object value : values) {
            current.put(slotId, value);
            generateCombinationsRecursive(slotIDs, index + 1, current, allValues, combinations, maxCombinations);
            current.remove(slotId);
            
            if (combinations.size() >= maxCombinations) {
                break;
            }
        }
    }
    
    private Map<Integer, Object> extractFieldValues(Map<Integer, JavaFieldIdentifier> fields,
                                                   TraceIdentifierMapper mapper) {
        Map<Integer, Object> fieldValues = new HashMap<>();
        
        for (Map.Entry<Integer, JavaFieldIdentifier> entry : fields.entrySet()) {
            Integer slotId = entry.getKey();
            Set<?> values = mapper.getSlotValues(slotId);
            if (!values.isEmpty()) {
                fieldValues.put(slotId, values.iterator().next()); // Take first value
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
    
    private String generateTestClass(List<TestScenario> scenarios, Path sourceCodeDirectory) {
        // First, generate all test methods to collect object imports
        String targetClass = context.getTargetMethod() != null ? context.getTargetMethod().getFullyQualifiedClassName() : "UnknownClass";
        String instanceName = targetClass.substring(targetClass.lastIndexOf('.') + 1).toLowerCase();

        // look for the appropriate constructor (factory method, etc) based on field types and signature
        List<String> fieldTypes = new ArrayList<>();
        for (Integer slotId : identifierMapping.keySet()) {
            ExportableValue value = identifierMapping.get(slotId);
            if (value instanceof JavaFieldIdentifier field) {
                fieldTypes.add(field.getType());
            }
        }
		String instanceCreatingStatement = buildMatchingInstanceCreationStatement(targetClass, sourceCodeDirectory, fieldTypes);

        StringBuilder testMethodsBuilder = new StringBuilder();
        for (TestScenario scenario : scenarios) {
            testMethodsBuilder.append(generateTestMethod(scenario, instanceCreatingStatement, instanceName));
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

            // We are doing setup of the object instance in each test method, and not here via common setUp method, because we need to consider field values in the respective test scenario
        }

        // Add the pre-generated test methods
        sb.append(testMethods);

        sb.append("}\n");

        return sb.toString();
    }
    
    private String generateTestMethod(TestScenario scenario, String instanceCreatingStatement, String instanceName) {
        StringBuilder sb = new StringBuilder();

        String methodName = generateTestMethodName(scenario);
        boolean isVoidMethod = isVoidReturnType();

        sb.append("    @Test\n");
        sb.append("    void ").append(methodName).append("() {\n");
        sb.append("        // Arrange\n");

        if ( ! isStaticMethod() ) {
            // Create the object instance (that means, instance variable for the class under test)
            // Set up field values if any (through arguments of the constructor with the right signature)
            sb.append("        // Initialize ").append(instanceName).append(" with appropriate constructor (factory method, etc)\n");
            sb.append("        ").append(instanceName).append(" = ").append(instanceCreatingStatement).append("(");
            boolean first = true;
            for (Map.Entry<Integer, Object> field : scenario.fieldValues.entrySet()) {
                if (!first) sb.append(", ");
                ExportableValue fieldId = identifierMapping.get(field.getKey());
                if (fieldId instanceof JavaFieldIdentifier fieldIdentifier) {
                    // Setting the field value (field name is not important here)
                    sb.append(formatValueForCode(field.getValue()));
                }
                first = false;
            }
            sb.append(");\n");
        }

        sb.append("\n        // Act\n");

        /// We gemerate a try-catch block around the method call (execution of the tested method) and checks (asserts) performed over the result
        // This is mainly for the methods that declare a checked exception possibly thrown during their execution
        sb.append("\n        try {\n");

        // Generate method call
        String methodCall = generateMethodCall(scenario, instanceName, isVoidMethod);
        sb.append("            ").append(methodCall).append("\n");

        sb.append("\n            // Assert\n");
        if (isVoidMethod) {
            sb.append("            // Method returns void - test verifies execution completes without exception\n");
            sb.append("            // Note: Add assertions to verify side effects (e.g., field changes)\n");
        } else {
            sb.append("            // Basic assertion to verify method execution completed without exception\n");
            sb.append("            assertNotNull(result, \"Method should return a non-null result\");\n");
            sb.append("            // Note: Add more specific assertions based on expected behavior\n");
        }

        // An exception causes a test failure
        sb.append("\n        } catch (Exception ex) {\n");
        sb.append("\n            // No exception should be thrown during method execution\n");
        sb.append("\n            // Here we check and enforce this property, effectively\n");
        sb.append("\n            fail(\"Exception: \" + ex.getMessage());\n");
        sb.append("\n        }\n");

        sb.append("    }\n");

        return sb.toString();
    }

    private String buildMatchingInstanceCreationStatement(String targetClass, Path sourceCodeDirectory, List<String> fieldTypes) {
        Path targetClassSourceFilePath = sourceCodeDirectory.resolve(targetClass.replace('.', File.separatorChar) + ".java");
        String targetClassSimpleName = targetClass.substring(targetClass.lastIndexOf('.') + 1);

        try {
            StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

            CompilationUnit sourceCU = StaticJavaParser.parse(targetClassSourceFilePath);

            ClassOrInterfaceDeclaration targetClsDecl = sourceCU.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(cls -> cls.getFullyQualifiedName().get().equals(targetClass)).findFirst()
                    .orElseThrow(() -> new TestGenerationWorkflowException("Cannot find the class " + targetClass + " in source file " + targetClassSourceFilePath));

            // Having the right class, we are looking for (in this order):
            //   1) Public insstance constructor with a signature of parameters that matches given field types
            //   2) Static factory method that returns an object of the given type and its signature of parameters matches field types

            for (MethodDeclaration mthDecl : targetClsDecl.findAll(MethodDeclaration.class).stream().filter(MethodDeclaration::isPublic).toList()) {
                boolean isConstructor = false;
                boolean isFactoryMth = false;

                // constructor with the same name as the class
                if (mthDecl.getName().asString().equals(targetClassSimpleName)) isConstructor = true;

                // factory method that returns objects of the class
                if (mthDecl.getType().asString().equals(targetClassSimpleName)) isFactoryMth = true;

                if (isConstructor || isFactoryMth) {
                    boolean matchingParamSignature = true;
                    if (fieldTypes.size() == mthDecl.getParameters().size()) {
                        for (int i = 0; i < fieldTypes.size(); i++) {
                            if ( ! fieldTypes.get(i).equals(mthDecl.getParameters().get(i).getType().asString()) ) matchingParamSignature = false;
                        }
                    }
                    else matchingParamSignature = false;

                    if (isConstructor && matchingParamSignature) return "new " + mthDecl.getName();
                    if (isFactoryMth && matchingParamSignature) return targetClassSimpleName + "." + mthDecl.getName();
                }
            }
        } catch (Exception ex) {
            // We just log the error and return the fallback result in this case (otherwise many tests depending on temporary files/directories would crash)
            log.error("Cannot parse the source code file " + targetClassSourceFilePath + " when looking for constructors and factory methods in a target class: " + ex.getMessage());
        }

        // fallback: call of the default non-parametric constructor
        return "new " + targetClassSimpleName;
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
            if (doubleStr.equals("NaN")) {
                doubleStr = "Double.NaN";
            }
            else if (doubleStr.equals("Infinity")) {
                doubleStr = "Double.POSITIVE_INFINITY";
            }
            else if (doubleStr.equals("-Infinity")) {
                doubleStr = "Double.NEGATIVE_INFINITY";
            }
            else if (!doubleStr.contains(".") && !doubleStr.contains("E") && !doubleStr.contains("e")) {
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
