package cz.cuni.mff.d3s.autodebugger.runner.orchestrator;

import cz.cuni.mff.d3s.autodebugger.model.common.TargetLanguage;
import cz.cuni.mff.d3s.autodebugger.model.common.tests.TestSuite;
import cz.cuni.mff.d3s.autodebugger.runner.args.Arguments;
import cz.cuni.mff.d3s.autodebugger.testrunner.common.TestExecutionResult;
import cz.cuni.mff.d3s.autodebugger.testrunner.common.TestSuiteStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Phase 4 (Test Generation) to Phase 5 (Test Execution) handoff.
 * These tests verify that generated tests can be compiled and executed successfully.
 */
class Phase4To5IntegrationTest {

    @TempDir
    Path tempDir;

    private Path outputDir;
    private Arguments testArguments;

    @BeforeEach
    void setUp() throws IOException {
        outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);

        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);

        Path dislHome = tempDir.resolve("disl");
        Files.createDirectories(dislHome);
        Files.createDirectories(dislHome.resolve("bin"));
        Files.createDirectories(dislHome.resolve("output"));
        Files.createDirectories(dislHome.resolve("output").resolve("lib"));
        Files.createFile(dislHome.resolve("bin").resolve("disl.py"));

        Path appJar = tempDir.resolve("app.jar");
        Files.createFile(appJar);

        testArguments = new Arguments();
        testArguments.applicationJarPath = appJar.toString();
        testArguments.sourceCodePath = sourceDir.toString();
        testArguments.dislHomePath = dislHome.toString();
        testArguments.outputDirectory = outputDir.toString();
        testArguments.targetMethodReference = "Calculator.add(int,int)";
        testArguments.targetParameters = List.of("0:int", "1:int");
        testArguments.targetFields = List.of();
        testArguments.language = TargetLanguage.JAVA;
        testArguments.testGenerationStrategy = "trace-based-basic";
        testArguments.classpath = List.of();
        testArguments.runtimeArguments = List.of();
    }

    @Test
    void givenGeneratedTestSuite_whenRunTests_thenExecutesSuccessfully() throws Exception {
        // given - create a manually constructed test file that should pass
        Path testFile = outputDir.resolve("PassingTest.java");
        Files.writeString(testFile, """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            
            public class PassingTest {
                @Test
                void testSimple() {
                    assertEquals(5, 2 + 3);
                }
            }
            """);

        TestSuite testSuite = TestSuite.builder()
            .baseDirectory(outputDir)
            .testFile(testFile)
            .build();

        // when - create orchestrator and run tests
        Orchestrator orchestrator = OrchestratorFactory.create(testArguments);
        TestExecutionResult result = orchestrator.runTests(testSuite);

        // then - tests should execute successfully
        assertNotNull(result);
        assertEquals(TestSuiteStatus.PASSED, result.getOverallStatus(),
            "Test suite should pass");
        assertEquals(1, result.getTotalTestCount(), "Should have 1 test");
        assertEquals(1, result.getPassedCount(), "Should have 1 passed test");
        assertEquals(0, result.getFailedCount(), "Should have 0 failed tests");
    }

    @Test
    void givenTestSuiteWithFailingTest_whenRunTests_thenReportsFailed() throws Exception {
        // given - create a test file that will fail
        Path testFile = outputDir.resolve("FailingTest.java");
        Files.writeString(testFile, """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            
            public class FailingTest {
                @Test
                void testThatFails() {
                    assertEquals(10, 5, "This test intentionally fails");
                }
            }
            """);

        TestSuite testSuite = TestSuite.builder()
            .baseDirectory(outputDir)
            .testFile(testFile)
            .build();

        // when
        Orchestrator orchestrator = OrchestratorFactory.create(testArguments);
        TestExecutionResult result = orchestrator.runTests(testSuite);

        // then - test should report failure
        assertNotNull(result);
        assertEquals(TestSuiteStatus.FAILED, result.getOverallStatus(),
            "Test suite should fail");
        assertEquals(1, result.getTotalTestCount());
        assertEquals(0, result.getPassedCount());
        assertEquals(1, result.getFailedCount());
    }

    @Test
    void givenTestSuiteWithSyntaxError_whenRunTests_thenReportsError() throws Exception {
        // given - create a test file with syntax error
        Path testFile = outputDir.resolve("SyntaxErrorTest.java");
        Files.writeString(testFile, """
            import org.junit.jupiter.api.Test;

            public class SyntaxErrorTest {
                @Test
                void testWithSyntaxError() {
                    // Missing semicolon and closing brace
                    int x = 5
                }
            """);

        TestSuite testSuite = TestSuite.builder()
            .baseDirectory(outputDir)
            .testFile(testFile)
            .build();

        // when
        Orchestrator orchestrator = OrchestratorFactory.create(testArguments);
        TestExecutionResult result = orchestrator.runTests(testSuite);

        // then - should return error status for uncompilable code
        assertNotNull(result);
        assertEquals(TestSuiteStatus.ERROR, result.getOverallStatus(),
            "Should report ERROR status for compilation failure");
    }

    @Test
    void givenTestSuiteWithMultipleTests_whenRunTests_thenAllAreExecuted() throws Exception {
        // given - create multiple test files
        Path testFile1 = outputDir.resolve("Test1.java");
        Files.writeString(testFile1, """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class Test1 {
                @Test
                void testOne() {
                    assertTrue(true);
                }

                @Test
                void testTwo() {
                    assertEquals(4, 2 + 2);
                }
            }
            """);

        Path testFile2 = outputDir.resolve("Test2.java");
        Files.writeString(testFile2, """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class Test2 {
                @Test
                void testThree() {
                    assertNotNull("hello");
                }
            }
            """);

        TestSuite testSuite = TestSuite.builder()
            .baseDirectory(outputDir)
            .testFile(testFile1)
            .testFile(testFile2)
            .build();

        // when
        Orchestrator orchestrator = OrchestratorFactory.create(testArguments);
        TestExecutionResult result = orchestrator.runTests(testSuite);

        // then - all tests should be executed
        assertNotNull(result);
        assertEquals(TestSuiteStatus.PASSED, result.getOverallStatus());
        assertEquals(3, result.getTotalTestCount(), "Should have 3 total tests");
        assertEquals(3, result.getPassedCount(), "All 3 tests should pass");
    }

    @Test
    void givenEmptyTestSuite_whenRunTests_thenHandlesGracefully() {
        // given - empty test suite
        TestSuite testSuite = TestSuite.builder()
            .baseDirectory(outputDir)
            .build();

        // when
        Orchestrator orchestrator = OrchestratorFactory.create(testArguments);
        TestExecutionResult result = orchestrator.runTests(testSuite);

        // then - should handle empty suite gracefully
        assertNotNull(result);
        assertEquals(0, result.getTotalTestCount());
    }

    @Test
    void givenMixedTestResults_whenRunTests_thenReportsCorrectCounts() throws Exception {
        // given - test file with mixed results
        Path testFile = outputDir.resolve("MixedTest.java");
        Files.writeString(testFile, """
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.Disabled;
            import static org.junit.jupiter.api.Assertions.*;

            public class MixedTest {
                @Test
                void passingTest() {
                    assertTrue(true);
                }

                @Test
                void failingTest() {
                    fail("Intentional failure");
                }

                @Test
                @Disabled("Skipped for testing")
                void skippedTest() {
                    assertTrue(true);
                }
            }
            """);

        TestSuite testSuite = TestSuite.builder()
            .baseDirectory(outputDir)
            .testFile(testFile)
            .build();

        // when
        Orchestrator orchestrator = OrchestratorFactory.create(testArguments);
        TestExecutionResult result = orchestrator.runTests(testSuite);

        // then - should report correct counts
        assertNotNull(result);
        assertEquals(TestSuiteStatus.FAILED, result.getOverallStatus(),
            "Suite with failures should report FAILED");
        assertEquals(3, result.getTotalTestCount(), "Should count all tests including skipped");
        assertEquals(1, result.getPassedCount());
        assertEquals(1, result.getFailedCount());
        assertEquals(1, result.getSkippedCount());
    }
}

