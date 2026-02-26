package cz.cuni.mff.d3s.autodebugger.testrunner.common;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Represents the result of executing a test suite, including individual test results,
 * execution traces, and performance metrics.
 */
@Builder
@Getter
public class TestExecutionResult {

    @Override
    public String toString() {
        return String.format(
            "TestExecutionResult{status=%s, passed=%d, failed=%d, skipped=%d, total=%d, duration=%s}",
            overallStatus, passedCount, failedCount, skippedCount, getTotalTestCount(), totalExecutionTime
        );
    }
    
    /**
     * Overall execution status of the test suite.
     */
    private final TestSuiteStatus overallStatus;
    
    /**
     * Individual test results for each executed test.
     */
    @Singular
    private final List<TestResult> testResults;
    
    /**
     * Total execution time for the entire test suite.
     */
    private final Duration totalExecutionTime;
    
    /**
     * Timestamp when the test execution started.
     */
    private final LocalDateTime executionStartTime;
    
    /**
     * Timestamp when the test execution completed.
     */
    private final LocalDateTime executionEndTime;
    
    /**
     * Number of tests that passed.
     */
    private final int passedCount;
    
    /**
     * Number of tests that failed.
     */
    private final int failedCount;
    
    /**
     * Number of tests that were skipped.
     */
    private final int skippedCount;
    
    /**
     * Additional metadata about the test execution.
     */
    @Singular("metadataEntry")
    private final Map<String, Object> metadata;
    
    /**
     * Raw output from the test execution (stdout/stderr).
     */
    private final String rawOutput;
    
    /**
     * Execution traces collected during test runs (if instrumentation was enabled).
     */
    @Singular
    private final List<ExecutionTrace> executionTraces;
    
    /**
     * Gets the total number of tests executed.
     */
    public int getTotalTestCount() {
        return passedCount + failedCount + skippedCount;
    }
    
    /**
     * Checks if all tests passed.
     */
    public boolean allTestsPassed() {
        return overallStatus == TestSuiteStatus.PASSED && failedCount == 0;
    }
    
    /**
     * Gets the success rate as a percentage.
     */
    public double getSuccessRate() {
        int total = getTotalTestCount();
        return total > 0 ? (double) passedCount / total * 100.0 : 0.0;
    }
}
