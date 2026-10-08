package cz.cuni.mff.d3s.autodebugger.testrunner.common;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.Optional;

/**
 * Represents the result of executing a single test method.
 */
@Builder
@Getter
public class TestResult {
    
    /**
     * Name of the test method.
     */
    private final String testName;
    
    /**
     * Fully qualified class name containing the test.
     */
    private final String testClassName;
    
    /**
     * Execution status of the test.
     */
    private final TestStatus status;
    
    /**
     * Time taken to execute the test.
     */
    private final Duration executionTime;
    
    /**
     * Error message if the test failed.
     */
    private final String errorMessage;
    
    /**
     * Stack trace if the test failed with an exception.
     */
    private final String stackTrace;
    
    /**
     * Expected value for assertion failures.
     */
    private final String expectedValue;
    
    /**
     * Actual value for assertion failures.
     */
    private final String actualValue;
    
    /**
     * Additional details about the test execution.
     */
    private final String details;
    
    /**
     * Gets the full test identifier (className: testName), where testName is either a custom display name (defined in annotation) or a method name defined in the source code.
     */
    public String getFullTestName() {
        return testClassName + ": " + testName;
    }
    
    /**
     * Checks if the test passed.
     */
    public boolean isPassed() {
        return status == TestStatus.PASSED;
    }
    
    /**
     * Checks if the test failed.
     */
    public boolean isFailed() {
        return status == TestStatus.FAILED;
    }
    
    /**
     * Checks if the test was skipped.
     */
    public boolean isSkipped() {
        return status == TestStatus.SKIPPED;
    }
    
    /**
     * Gets the error message if available.
     */
    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }
    
    /**
     * Gets the stack trace if available.
     */
    public Optional<String> getStackTrace() {
        return Optional.ofNullable(stackTrace);
    }
}
