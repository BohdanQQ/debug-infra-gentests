package cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.ArgumentIdentifierParameters;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaArgumentIdentifier;

class JavaVariableTests {

    @Test
    void givenValidJavaVariable_whenGeneratingCode_thenCodeIsGenerated() {
        // given - create fresh identifiers to avoid reliance on shared static state
        JavaArgumentIdentifier identifier = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(0)
                .variableType("int")  // Use simple type to avoid FQN issues
                .build()
        );
        JavaVariable javaVariable = new JavaVariable(3, identifier);

        // when
        String code = javaVariable.emitCode();

        // then - verify the structure of the generated code
        // The variable name includes a generated ID which varies, so we check the pattern
        assertTrue(code.matches("int generatedVariable\\d+ = di\\.getLocalVariableValue\\(3, int\\.class\\);"),
            "Expected pattern 'int generatedVariable<N> = di.getLocalVariableValue(3, int.class);' but got: " + code);
    }

    @Test
    void givenVariableWithDifferentSlot_whenEmittingCode_thenSlotIsUsed() {
        // given
        JavaArgumentIdentifier identifier = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(0)
                .variableType("long")
                .build()
        );
        JavaVariable javaVariable = new JavaVariable(5, identifier);

        // when
        String code = javaVariable.emitCode();

        // then - verify slot 5 is used in the generated code
        assertTrue(code.contains("di.getLocalVariableValue(5, long.class)"),
            "Expected slot 5 in generated code but got: " + code);
    }
}
