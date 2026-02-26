package cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates generated Java test code for common issues and compilation errors.
 */
@Slf4j
public class CodeValidator {
    
    private static final Pattern CLASS_DECLARATION_PATTERN = Pattern.compile("public\\s+class\\s+\\w+");
    private static final Pattern TEST_METHOD_PATTERN = Pattern.compile("@Test\\s+(?:public\\s+)?void\\s+\\w+\\s*\\(");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("import\\s+[\\w\\.]+;");
    
    /**
     * Validates the generated Java test code.
     * 
     * @param code The Java code to validate
     * @return CodeValidationResult containing validation results
     */
    public CodeValidationResult validate(String code) {
        log.debug("Validating generated code (length: {} chars)", code.length());
        
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // Basic structure validation
        validateBasicStructure(code, errors, warnings);
        
        // Import validation
        validateImports(code, errors, warnings);
        
        // Test method validation
        validateTestMethods(code, errors, warnings);
        
        // Syntax validation
        validateSyntax(code, errors, warnings);
        
        // JUnit specific validation
        validateJUnitUsage(code, errors, warnings);
        
        boolean isValid = errors.isEmpty();
        
        log.debug("Code validation completed: {} errors, {} warnings", errors.size(), warnings.size());
        
        return CodeValidationResult.builder()
                .valid(isValid)
                .errors(errors)
                .warnings(warnings)
                .build();
    }
    
    /**
     * Validates basic Java code structure including class declarations and balanced delimiters.
     * Checks for proper class declaration syntax and matching braces/parentheses pairs.
     */
    private void validateBasicStructure(String code, List<String> errors, List<String> warnings) {
        // Check for class declaration
        if (!CLASS_DECLARATION_PATTERN.matcher(code).find()) {
            errors.add("No public class declaration found");
        }
        
        // Check for balanced braces
        int openBraces = countOccurrences(code, '{');
        int closeBraces = countOccurrences(code, '}');
        if (openBraces != closeBraces) {
            errors.add("Unbalanced braces: " + openBraces + " opening, " + closeBraces + " closing");
        }
        
        // Check for balanced parentheses
        int openParens = countOccurrences(code, '(');
        int closeParens = countOccurrences(code, ')');
        if (openParens != closeParens) {
            errors.add("Unbalanced parentheses: " + openParens + " opening, " + closeParens + " closing");
        }
    }
    
    /**
     * Validates import statements for essential JUnit dependencies.
     * Ensures required JUnit imports are present and identifies potentially unused imports.
     */
    private void validateImports(String code, List<String> errors, List<String> warnings) {
        // Check for essential JUnit imports
        if (code.contains("@Test") && !code.contains("import org.junit")) {
            errors.add("Missing JUnit import for @Test annotation");
        }
        
        if (code.contains("assertEquals") && !code.contains("import static org.junit.jupiter.api.Assertions")) {
            errors.add("Missing static import for JUnit assertions");
        }
        
        // Check for unused imports (warning only)
        String[] lines = code.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("import ")) {
                String importedClass = extractImportedClass(line);
                if (importedClass != null && !isImportUsed(code, importedClass)) {
                    warnings.add("Potentially unused import: " + importedClass);
                }
            }
        }
    }
    
    private void validateTestMethods(String code, List<String> errors, List<String> warnings) {
        // Check if there are any test methods
        if (!TEST_METHOD_PATTERN.matcher(code).find()) {
            warnings.add("No test methods found with @Test annotation");
        }
        
        // Check for test methods without assertions
        String[] lines = code.split("\n");
        boolean inTestMethod = false;
        boolean hasAssertion = false;
        String currentTestMethod = null;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            if (trimmed.startsWith("@Test")) {
                inTestMethod = true;
                hasAssertion = false;
                currentTestMethod = null;
            } else if (inTestMethod && trimmed.contains("void ") && trimmed.contains("(")) {
                currentTestMethod = extractMethodName(trimmed);
            } else if (inTestMethod && (trimmed.contains("assert") || trimmed.contains("verify") || 
                      trimmed.contains("fail(") || trimmed.contains("assertTrue") || 
                      trimmed.contains("assertFalse") || trimmed.contains("assertEquals"))) {
                hasAssertion = true;
            } else if (inTestMethod && trimmed.equals("}")) {
                if (!hasAssertion && currentTestMethod != null) {
                    warnings.add("Test method '" + currentTestMethod + "' has no assertions");
                }
                inTestMethod = false;
            }
        }
    }
    
    private void validateSyntax(String code, List<String> errors, List<String> warnings) {
        // Check for common syntax errors
        if (code.contains(";;")) {
            warnings.add("Double semicolons found - possible syntax error");
        }
        
        // Check for unclosed strings
        int singleQuotes = countOccurrences(code, '\'');
        int doubleQuotes = countOccurrences(code, '"');
        
        if (singleQuotes % 2 != 0) {
            errors.add("Unclosed single quotes detected");
        }
        
        // This is a simplified check - in practice, you'd need to handle escaped quotes
        if (doubleQuotes % 2 != 0) {
            warnings.add("Possible unclosed double quotes detected");
        }
        
        // Check for missing semicolons (basic check)
        String[] lines = code.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (needsSemicolon(line) && !line.endsWith(";") && !line.endsWith("{") && !line.endsWith("}")) {
                warnings.add("Line " + (i + 1) + " might be missing a semicolon: " + line);
            }
        }
    }
    
    private void validateJUnitUsage(String code, List<String> errors, List<String> warnings) {
        // Check for proper JUnit 5 usage
        if (code.contains("@Before") && !code.contains("@BeforeEach")) {
            warnings.add("Using JUnit 4 @Before annotation - consider @BeforeEach for JUnit 5");
        }
        
        if (code.contains("@After") && !code.contains("@AfterEach")) {
            warnings.add("Using JUnit 4 @After annotation - consider @AfterEach for JUnit 5");
        }
        
        // Check for test method visibility
        if (code.contains("public void test") && code.contains("@Test")) {
            warnings.add("Test methods don't need to be public in JUnit 5");
        }
    }
    
    private int countOccurrences(String text, char character) {
        return (int) text.chars()
                .filter(c -> c == character)
                .count();
    }
    
    private String extractImportedClass(String importLine) {
        String trimmed = importLine.trim();
        if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
            String importPath = trimmed.substring(7, trimmed.length() - 1);
            int lastDot = importPath.lastIndexOf('.');
            return lastDot > 0 ? importPath.substring(lastDot + 1) : importPath;
        }
        return null;
    }
    
    private boolean isImportUsed(String code, String className) {
        // Simple check - in practice, you'd want more sophisticated analysis
        return code.contains(className);
    }
    
    private String extractMethodName(String methodDeclaration) {
        String[] parts = methodDeclaration.split("\\s+");
        for (String part : parts) {
            if (part.contains("(")) {
                return part.substring(0, part.indexOf('('));
            }
        }
        return "unknown";
    }
    
    private boolean needsSemicolon(String line) {
        return !line.isEmpty() && 
               !line.startsWith("//") && 
               !line.startsWith("/*") && 
               !line.startsWith("*") && 
               !line.startsWith("@") &&
               !line.contains("if ") &&
               !line.contains("for ") &&
               !line.contains("while ") &&
               !line.contains("try ") &&
               !line.contains("catch ") &&
               !line.contains("finally ") &&
               !line.contains("class ") &&
               !line.contains("interface ") &&
               !line.contains("enum ");
    }
}
