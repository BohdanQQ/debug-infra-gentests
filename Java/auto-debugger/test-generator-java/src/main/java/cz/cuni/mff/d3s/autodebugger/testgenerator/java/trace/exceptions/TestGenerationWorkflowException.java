package cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace.exceptions;

/**
 * Exception thrown when there are issues in the test generation workflow.
 * This includes problems with source code processing, test file writing, code validation, etc.
 * This is a runtime exception since workflow errors are typically unrecoverable.
 */
public class TestGenerationWorkflowException extends RuntimeException {

    public TestGenerationWorkflowException(String message) {
        super(message);
    }
}
