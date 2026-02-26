package cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PromptBuilder.
 * Tests prompt construction for LLM-based test generation.
 */
class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
    }

    @Test
    void givenBasicContext_whenBuildingPrompt_thenContainsRequiredSections() {
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode("public class Calculator { public int add(int a, int b) { return a + b; } }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertTrue(prompt.contains("## Requirements"), "Prompt should contain Requirements section");
        assertTrue(prompt.contains("## Source Code to Test"), "Prompt should contain Source Code section");
        assertTrue(prompt.contains("add(int, int)"), "Prompt should contain target method signature");
        assertTrue(prompt.contains("junit5"), "Prompt should contain test framework");
    }

    @Test
    void givenContextWithTraceData_whenBuildingPrompt_thenContainsRuntimeSection() {
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode("public class Calculator { public int add(int a, int b) { return a + b; } }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .traceData("add(2, 3) -> 5\nadd(0, 0) -> 0")
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertTrue(prompt.contains("## Runtime Execution Data"), 
                "Prompt should contain Runtime Execution Data section when trace is provided");
        assertTrue(prompt.contains("add(2, 3) -> 5"), "Prompt should contain trace data");
    }

    @Test
    void givenContextWithoutTraceData_whenBuildingPrompt_thenNoRuntimeSection() {
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode("public class Calculator { }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .traceData(null)
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertFalse(prompt.contains("## Runtime Execution Data"), 
                "Prompt should not contain Runtime Execution Data section when trace is null");
    }

    @Test
    void givenContextWithEmptyTraceData_whenBuildingPrompt_thenNoRuntimeSection() {
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode("public class Calculator { }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .traceData("")
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertFalse(prompt.contains("## Runtime Execution Data"), 
                "Prompt should not contain Runtime Execution Data section when trace is empty");
    }

    @Test
    void givenContextWithEdgeCasesEnabled_whenBuildingPrompt_thenContainsEdgeCaseRequirement() {
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode("public class Calculator { }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .generateEdgeCases(true)
                .generateNegativeTests(false)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertTrue(prompt.toLowerCase().contains("edge case") || prompt.toLowerCase().contains("boundary"), 
                "Prompt should mention edge cases or boundary conditions when enabled");
    }

    @Test
    void givenContextWithNegativeTestsEnabled_whenBuildingPrompt_thenContainsNegativeTestRequirement() {
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode("public class Calculator { }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .generateEdgeCases(false)
                .generateNegativeTests(true)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertTrue(prompt.toLowerCase().contains("negative") || prompt.toLowerCase().contains("error") 
                   || prompt.toLowerCase().contains("invalid"), 
                "Prompt should mention negative tests, error conditions, or invalid inputs when enabled");
    }

    @Test
    void givenContextWithAdditionalInstructions_whenBuildingPrompt_thenContainsAdditionalSection() {
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode("public class Calculator { }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .additionalInstructions("Focus on thread safety")
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertTrue(prompt.contains("## Additional Instructions"),
                "Prompt should contain Additional Instructions section");
        assertTrue(prompt.contains("Focus on thread safety"),
                "Prompt should contain the additional instructions text");
    }

    @Test
    void givenEmptyAdditionalInstructions_whenBuildingPrompt_thenNoAdditionalSection() {
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode("public class Calculator { }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .additionalInstructions("   ")  // whitespace only
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertFalse(prompt.contains("## Additional Instructions"),
                "Prompt should not contain Additional Instructions section for whitespace-only instructions");
    }

    @Test
    void givenSourceCodeWithJavaClass_whenBuildingPrompt_thenSourceCodeIsInCodeBlock() {
        String sourceCode = "public class Calculator { public int add(int a, int b) { return a + b; } }";
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode(sourceCode)
                .targetMethodSignature("add(int, int)")
                .testFramework("junit5")
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertTrue(prompt.contains("```java"), "Prompt should contain java code block marker");
        assertTrue(prompt.contains("```"), "Prompt should contain code block closing marker");
        assertTrue(prompt.contains(sourceCode), "Prompt should contain the source code");
    }

    @Test
    void givenDifferentTestFramework_whenBuildingPrompt_thenUsesCorrectFramework() {
        LLMPromptContext context = LLMPromptContext.builder()
                .sourceCode("public class Calculator { }")
                .targetMethodSignature("add(int, int)")
                .testFramework("junit4")
                .generateEdgeCases(false)
                .generateNegativeTests(false)
                .build();

        String prompt = promptBuilder.buildTestGenerationPrompt(context);

        assertTrue(prompt.contains("junit4"), "Prompt should contain specified test framework (junit4)");
    }
}

