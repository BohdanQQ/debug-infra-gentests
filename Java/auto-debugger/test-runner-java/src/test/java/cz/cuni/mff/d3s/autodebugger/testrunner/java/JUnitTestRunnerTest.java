package cz.cuni.mff.d3s.autodebugger.testrunner.java;

import cz.cuni.mff.d3s.autodebugger.testrunner.common.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JUnitTestRunnerTest {
    
    @TempDir
    Path tempDir;
    
    private JUnitTestRunner testRunner;
    private TestRunnerConfiguration configuration;
    
    @BeforeEach
    void setUp() {
        testRunner = new JUnitTestRunner();
        
        configuration = TestRunnerConfiguration.builder()
                .workingDirectory(tempDir)
                .classpathEntry(Path.of(System.getProperty("java.class.path")))
                .executionTimeout(Duration.ofMinutes(1))
                .enableInstrumentation(false)
                .captureExecutionTraces(false)
                .testFramework("junit5")
                .build();
        
        testRunner.configure(configuration);
    }
    
    @Test
    void givenValidConfiguration_whenConfigure_thenSucceeds() {
        assertDoesNotThrow(() -> testRunner.configure(configuration));
    }
    
    @Test
    void givenEmptyList_whenExecuteTests_thenReportsZeroTests() {
        TestExecutionResult result = testRunner.executeTests(List.of());
        
        assertNotNull(result);
        assertEquals(TestSuiteStatus.PASSED, result.getOverallStatus());
        assertEquals(0, result.getTotalTestCount());
        assertTrue(result.getTestResults().isEmpty());
    }
    
    @Test
    void givenUnconfiguredRunner_whenExecuteTests_thenThrows() {
        JUnitTestRunner unconfiguredRunner = new JUnitTestRunner();
        
        assertThrows(IllegalStateException.class, () -> 
            unconfiguredRunner.executeTests(List.of()));
    }
    
    @Test
    void givenSimpleTestFile_whenExecuteTest_thenReturnsResult() throws Exception {
        // Create a simple test file
        Path testFile = createSimpleTestFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        assertNotNull(result);
        assertNotNull(result.getOverallStatus());
        assertNotNull(result.getTestResults());
        assertTrue(result.getTotalExecutionTime().toMillis() >= 0);
    }

    @Test
    void givenPassingTest_whenExecuteTest_thenStatusIsPassedWithValidTiming() throws Exception {
        Path testFile = createPassingTestFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        assertEquals(TestSuiteStatus.PASSED, result.getOverallStatus());
        assertEquals(1, result.getPassedCount());
        assertEquals(0, result.getFailedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(1, result.getTotalTestCount());
        assertTrue(result.allTestsPassed());

        assertFalse(result.getTestResults().isEmpty());
        TestResult testResult = result.getTestResults().get(0);
        assertEquals(TestStatus.PASSED, testResult.getStatus());
        assertTrue(testResult.isPassed());
        assertNotNull(testResult.getExecutionTime());
    }

    @Test
    void givenFailingTest_whenExecuteTest_thenStatusIsFailedWithErrorMessage() throws Exception {
        Path testFile = createFailingTestFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        assertEquals(TestSuiteStatus.FAILED, result.getOverallStatus());
        assertEquals(0, result.getPassedCount());
        assertEquals(1, result.getFailedCount());
        assertFalse(result.allTestsPassed());

        assertFalse(result.getTestResults().isEmpty());
        TestResult testResult = result.getTestResults().get(0);
        assertEquals(TestStatus.FAILED, testResult.getStatus());
        assertTrue(testResult.isFailed());
    }

    @Test
    void givenDisabledTest_whenExecuteTest_thenStatusIsSkipped() throws Exception {
        Path testFile = createSkippedTestFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        assertEquals(0, result.getFailedCount());
        assertEquals(1, result.getSkippedCount());

        assertFalse(result.getTestResults().isEmpty());
        TestResult testResult = result.getTestResults().get(0);
        assertEquals(TestStatus.SKIPPED, testResult.getStatus());
        assertTrue(testResult.isSkipped());
    }

    @Test
    void givenTestThrowingException_whenExecuteTest_thenExceptionCapturedAsFailure() throws Exception {
        Path testFile = createExceptionThrowingTestFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        assertEquals(TestSuiteStatus.FAILED, result.getOverallStatus());
        assertEquals(1, result.getFailedCount());

        TestResult testResult = result.getTestResults().get(0);
        assertEquals(TestStatus.FAILED, testResult.getStatus());
    }

    @Test
    void givenTestFileWithMultipleMethods_whenExecuteTest_thenAllMethodsExecuted() throws Exception {
        Path testFile = createMultipleTestMethodsFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        assertEquals(TestSuiteStatus.PASSED, result.getOverallStatus());
        assertEquals(3, result.getTotalTestCount());
        assertEquals(3, result.getPassedCount());
        assertEquals(0, result.getFailedCount());
        assertEquals(3, result.getTestResults().size());
    }

    @Test
    void givenMultipleTestFiles_whenExecuteTests_thenAllFilesExecuted() throws Exception {
        Path testFile1 = createPassingTestFile();
        Path testFile2 = createSecondPassingTestFile();

        TestExecutionResult result = testRunner.executeTests(List.of(testFile1, testFile2));

        assertEquals(TestSuiteStatus.PASSED, result.getOverallStatus());
        assertEquals(2, result.getTotalTestCount());
        assertEquals(2, result.getPassedCount());
    }

    @Test
    void givenMixedResults_whenExecuteTest_thenOverallStatusIsFailed() throws Exception {
        Path testFile = createMixedResultsTestFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        assertEquals(TestSuiteStatus.FAILED, result.getOverallStatus());
        assertEquals(1, result.getPassedCount());
        assertEquals(1, result.getFailedCount());
        assertEquals(1, result.getSkippedCount());
        assertFalse(result.allTestsPassed());
    }

    @Test
    void givenAnyTest_whenExecuteTest_thenTimingFieldsAreSet() throws Exception {
        Path testFile = createPassingTestFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        assertNotNull(result.getExecutionStartTime());
        assertNotNull(result.getExecutionEndTime());
        assertNotNull(result.getTotalExecutionTime());
        assertFalse(result.getExecutionEndTime().isBefore(result.getExecutionStartTime()));
    }

    @Test
    void givenMixedResults_whenExecuteTest_thenCountsMatchTestResults() throws Exception {
        Path testFile = createMixedResultsTestFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        long passedFromList = result.getTestResults().stream().filter(TestResult::isPassed).count();
        long failedFromList = result.getTestResults().stream().filter(TestResult::isFailed).count();
        long skippedFromList = result.getTestResults().stream().filter(TestResult::isSkipped).count();

        assertEquals(passedFromList, result.getPassedCount());
        assertEquals(failedFromList, result.getFailedCount());
        assertEquals(skippedFromList, result.getSkippedCount());
    }

    @Test
    void givenTestClassWithNoTestMethods_whenExecuteTest_thenReturnsEmptyResults() throws Exception {
        Path testFile = createEmptyTestClassFile();

        TestExecutionResult result = testRunner.executeTest(testFile);

        assertEquals(0, result.getTotalTestCount());
        assertTrue(result.getTestResults().isEmpty());
    }
    
    private Path createSimpleTestFile() throws Exception {
        String testContent = """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class SimpleTest {
                @Test
                void testSimpleAssertion() {
                    assertEquals(2, 1 + 1);
                }

                @Test
                void testTrueAssertion() {
                    assertTrue(true);
                }
            }
            """;

        Path testFile = tempDir.resolve("SimpleTest.java");
        Files.write(testFile, testContent.getBytes());
        return testFile;
    }

    private Path createPassingTestFile() throws Exception {
        String testContent = """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class PassingTest {
                @Test
                void testAlwaysPasses() {
                    assertTrue(true);
                }
            }
            """;

        Path testFile = tempDir.resolve("PassingTest.java");
        Files.write(testFile, testContent.getBytes());
        return testFile;
    }

    private Path createFailingTestFile() throws Exception {
        String testContent = """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class FailingTest {
                @Test
                void testAlwaysFails() {
                    assertEquals(1, 2, "Expected values to be equal");
                }
            }
            """;

        Path testFile = tempDir.resolve("FailingTest.java");
        Files.write(testFile, testContent.getBytes());
        return testFile;
    }

    private Path createSkippedTestFile() throws Exception {
        String testContent = """
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.Disabled;
            import static org.junit.jupiter.api.Assertions.*;

            public class SkippedTest {
                @Disabled("Test is disabled for demonstration")
                @Test
                void testIsSkipped() {
                    fail("This should not run");
                }
            }
            """;

        Path testFile = tempDir.resolve("SkippedTest.java");
        Files.write(testFile, testContent.getBytes());
        return testFile;
    }

    private Path createExceptionThrowingTestFile() throws Exception {
        String testContent = """
            import org.junit.jupiter.api.Test;

            public class ExceptionTest {
                @Test
                void testThrowsException() {
                    throw new RuntimeException("Intentional test exception");
                }
            }
            """;

        Path testFile = tempDir.resolve("ExceptionTest.java");
        Files.write(testFile, testContent.getBytes());
        return testFile;
    }

    private Path createMultipleTestMethodsFile() throws Exception {
        String testContent = """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class MultipleMethodsTest {
                @Test
                void testFirst() {
                    assertTrue(true);
                }

                @Test
                void testSecond() {
                    assertEquals(4, 2 + 2);
                }

                @Test
                void testThird() {
                    assertNotNull("hello");
                }
            }
            """;

        Path testFile = tempDir.resolve("MultipleMethodsTest.java");
        Files.write(testFile, testContent.getBytes());
        return testFile;
    }

    private Path createMixedResultsTestFile() throws Exception {
        String testContent = """
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.Disabled;
            import static org.junit.jupiter.api.Assertions.*;

            public class MixedResultsTest {
                @Test
                void testPasses() {
                    assertTrue(true);
                }

                @Test
                void testFails() {
                    fail("Intentional failure");
                }

                @Disabled
                @Test
                void testSkipped() {
                    fail("Should not run");
                }
            }
            """;

        Path testFile = tempDir.resolve("MixedResultsTest.java");
        Files.write(testFile, testContent.getBytes());
        return testFile;
    }

    private Path createEmptyTestClassFile() throws Exception {
        String testContent = """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class EmptyTestClass {
                void helperMethod() {
                    // Not a test method
                }
            }
            """;

        Path testFile = tempDir.resolve("EmptyTestClass.java");
        Files.write(testFile, testContent.getBytes());
        return testFile;
    }

    private Path createSecondPassingTestFile() throws Exception {
        String testContent = """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class SecondPassingTest {
                @Test
                void testAlsoPasses() {
                    assertFalse(false);
                }
            }
            """;

        Path testFile = tempDir.resolve("SecondPassingTest.java");
        Files.write(testFile, testContent.getBytes());
        return testFile;
    }
}
