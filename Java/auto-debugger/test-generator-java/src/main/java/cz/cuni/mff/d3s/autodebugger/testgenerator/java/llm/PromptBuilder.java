package cz.cuni.mff.d3s.autodebugger.testgenerator.java.llm;

import cz.cuni.mff.d3s.autodebugger.testgenerator.common.TestNamingStrategy;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds prompts for LLM-based test generation optimized for Claude Sonnet.
 * Constructs context-aware prompts that work well with Claude's reasoning capabilities.
 * The system message (role instruction) is handled by LLMTestGenerationClient,
 * so this builder focuses on creating clear, structured user prompts.
 */
@Slf4j
public class PromptBuilder {
    
    /**
     * Builds the main test generation prompt optimized for Claude Sonnet.
     * Creates a clear, structured prompt that leverages Claude's reasoning abilities.
     */
    public String buildTestGenerationPrompt(LLMPromptContext context) {
        StringBuilder prompt = new StringBuilder();

        // Task definition - clear and direct for Claude
        prompt.append("Generate a suite of ").append(context.getTestFramework())
              .append(" tests for the method `").append(context.getTargetMethodSignature()).append("`.\n\n");

        // Requirements section - Claude works well with structured requirements
        prompt.append("## Requirements\n");
        prompt.append("- Create test cases that cover all possible valid combinations of argument values based on provided runtime data, and nothing else\n");
        prompt.append("- Use the provided runtime data to create assertions that verify observed outcomes\n");
        prompt.append("- Generate test methods with descriptive, meaningful names\n");
        prompt.append("- Do not add the annotation @DisplayName to test methods\n");
        prompt.append("- Follow ").append(context.getTestFramework()).append(" best practices and conventions\n");
        prompt.append("- Include proper imports and package declaration\n");

        if (context.isGenerateEdgeCases()) {
            prompt.append("- Include also additional tests for edge cases and boundary conditions not present in runtime data\n");
        }

        if (context.isGenerateNegativeTests()) {
            prompt.append("- Include negative test cases for error conditions and invalid inputs\n");
        }

        prompt.append("- Create the source code of a public Java class that contains ").append(context.getTestFramework()).append(" test methods\n");

        prompt.append("- Return only the complete Java test class without explanations and markdown\n\n");

        // Context sections with clear structure that Claude can parse well

        // Relevant information about source code of the Java class that contains target method
        // The full source code is typically too long ("eats tokens/credit for LLM"), so we put there just signatures of public methods (including constructors)
        // We have removed (omitted) the opening code block marker "```java" (and the closing marker "```") to see if it helps
        prompt.append("## Java Source Code to Test (signatures of pubiic methods and constructors)\n");
        //prompt.append("```java\n");
        prompt.append(context.getSourceCodeInfo());
        //prompt.append("\n```\n\n");
        prompt.append("\n\n");

        // Runtime trace data if available - Claude is good at analyzing execution patterns
        if (context.getTraceData() != null && !context.getTraceData().isEmpty()) {
            prompt.append("## Runtime Execution Data\n");
            prompt.append("Use this data to understand the method's behavior and create accurate assertions:\n");
            prompt.append("```\n");
            prompt.append(context.getTraceData());
            prompt.append("\n```\n\n");
        }

        // Additional context if provided
        if (context.getAdditionalInstructions() != null && !context.getAdditionalInstructions().trim().isEmpty()) {
            prompt.append("## Additional Instructions\n");
            prompt.append(context.getAdditionalInstructions());
            prompt.append("\n\n");
        }

        // Final instruction - clear and direct
        prompt.append("Generate the complete test class:");

        return prompt.toString();
    }
    
    /**
     * Builds a refinement prompt for improving generated code, optimized for Claude's analytical abilities.
     */
    public String buildRefinementPrompt(String currentCode, CodeValidationResult validation, LLMPromptContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Please analyze and fix the issues in the following Java test code.\n\n");
        
        prompt.append("## Current Code with Issues\n");
        prompt.append("```java\n");
        prompt.append(currentCode);
        prompt.append("\n```\n\n");

        if (!validation.getErrors().isEmpty()) {
            prompt.append("## Compilation Errors to Fix\n");
            for (String error : validation.getErrors()) {
                prompt.append("- ").append(error).append("\n");
            }
            prompt.append("\n");
        }

        if (!validation.getWarnings().isEmpty()) {
            prompt.append("## Warnings to Address\n");
            for (String warning : validation.getWarnings()) {
                prompt.append("- ").append(warning).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("## Fix Requirements\n");
        prompt.append("- Resolve all compilation errors and warnings\n");
        prompt.append("- Ensure all imports are correct and complete\n");
        prompt.append("- Use proper ").append(context.getTestFramework()).append(" annotations and assertions\n");
        prompt.append("- Preserve the original test logic and intent\n");
        prompt.append("- Follow Java coding best practices and conventions\n");
        prompt.append("- Ensure the test class is complete and runnable\n\n");

        prompt.append("Return the complete corrected Java test class:");
        
        return prompt.toString();
    }
    
    /**
     * Builds a prompt for fixing specific validation errors.
     */
    public String buildErrorFixPrompt(String code, CodeValidationResult validation, LLMPromptContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Fix the following compilation errors in this Java test code:\n\n");
        
        prompt.append("## Code with Errors\n");
        prompt.append("```java\n");
        prompt.append(code);
        prompt.append("\n```\n\n");
        
        prompt.append("## Compilation Errors\n");
        for (String error : validation.getErrors()) {
            prompt.append("- ").append(error).append("\n");
        }
        
        prompt.append("\n## Instructions\n");
        prompt.append("- Fix only the compilation errors\n");
        prompt.append("- Do not change the test logic unless necessary\n");
        prompt.append("- Ensure all required imports are included\n");
        prompt.append("- Use ").append(context.getTestFramework()).append(" framework correctly\n\n");
        
        prompt.append("Return only the corrected Java code without explanations and markdown\n");
        
        return prompt.toString();
    }
    
    /**
     * Builds a prompt for generating additional edge case tests.
     */
    public String buildEdgeCasePrompt(LLMPromptContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Generate additional edge case tests for the method `")
              .append(context.getTargetMethodSignature())
              .append("`. Focus on boundary conditions, null values, empty collections, ");
        prompt.append("extreme values, and other edge cases that might cause unexpected behavior.\n\n");
        
        // Relevant information about source code of the Java class that contains target method
        // We have removed (omitted) the opening code block marker "```java" (and the closing marker "```") to see if it helps
        prompt.append("## Source Code (signatures of public methods and constructors\n");
        //prompt.append("```java\n");
        prompt.append(context.getSourceCodeInfo());
        //prompt.append("\n```\n\n");
        
        prompt.append("Generate 5-10 additional test methods that cover edge cases not typically ");
        prompt.append("encountered during normal execution. Use ").append(context.getTestFramework());
        prompt.append(" framework and follow the same naming conventions.\n\n");
        
        prompt.append("Return only the test methods (not the full class):\n");
        
        return prompt.toString();
    }
}
