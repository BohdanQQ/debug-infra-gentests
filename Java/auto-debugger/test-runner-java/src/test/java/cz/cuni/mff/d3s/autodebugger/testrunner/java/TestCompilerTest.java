package cz.cuni.mff.d3s.autodebugger.testrunner.java;

import cz.cuni.mff.d3s.autodebugger.testrunner.common.TestRunnerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestCompilerTest {

    @TempDir
    Path tempDir;

    private TestCompiler compiler;
    private TestRunnerConfiguration configuration;

    @BeforeEach
    void setUp() {
        compiler = new TestCompiler();
        configuration = TestRunnerConfiguration.builder()
                .workingDirectory(tempDir)
                .executionTimeout(Duration.ofMinutes(1))
                .testFramework("junit5")
                .build();
    }

    // ==================== Happy Path Tests ====================

    @Test
    void givenSimpleTestFileWithoutPackage_whenCompileTest_thenClassFileIsCreated() throws Exception {
        Path testFile = writeTestFile("SimpleTest.java", """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            
            public class SimpleTest {
                @Test
                void testSimple() {
                    assertTrue(true);
                }
            }
            """);

        Path compiledClass = compiler.compileTest(testFile, configuration);

        assertNotNull(compiledClass, "Compiled class path should not be null");
        assertTrue(Files.exists(compiledClass), "Compiled .class file should exist");
        assertTrue(compiledClass.toString().endsWith("SimpleTest.class"),
                  "Compiled file should be SimpleTest.class");
    }

    @Test
    void givenTestFileWithPackage_whenCompileTest_thenClassFileCreatedInPackageDirectory() throws Exception {
        Path testFile = writeTestFile("PackagedTest.java", """
            package com.example.tests;
            
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            
            public class PackagedTest {
                @Test
                void testPackaged() {
                    assertEquals(4, 2 + 2);
                }
            }
            """);

        Path compiledClass = compiler.compileTest(testFile, configuration);

        assertNotNull(compiledClass);
        assertTrue(Files.exists(compiledClass), "Compiled .class file should exist");
        // Check that the path contains package directory structure (OS-independent)
        String pathStr = compiledClass.toString().replace('\\', '/');
        assertTrue(pathStr.contains("com/example/tests/PackagedTest.class"),
                  "Class should be in package directory structure, got: " + pathStr);
    }

    @Test
    void givenMultipleTestFiles_whenCompileTests_thenAllClassFilesCreated() throws Exception {
        Path test1 = writeTestFile("FirstTest.java", """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            
            public class FirstTest {
                @Test void test1() { assertTrue(true); }
            }
            """);
        
        Path test2 = writeTestFile("SecondTest.java", """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            
            public class SecondTest {
                @Test void test2() { assertFalse(false); }
            }
            """);

        List<Path> compiledClasses = compiler.compileTests(List.of(test1, test2), configuration);

        assertEquals(2, compiledClasses.size(), "Should compile two test files");
        assertTrue(compiledClasses.stream().allMatch(Files::exists),
                  "All compiled class files should exist");
    }

    // ==================== Error Handling Tests ====================

    @Test
    void givenTestFileWithSyntaxError_whenCompileTest_thenThrowsRuntimeException() throws Exception {
        Path testFile = writeTestFile("BrokenTest.java", """
            import org.junit.jupiter.api.Test;
            
            public class BrokenTest {
                @Test
                void testBroken() {
                    int x = 1  // missing semicolon
                }
            }
            """);

        assertThrows(RuntimeException.class,
            () -> compiler.compileTest(testFile, configuration),
            "Should throw exception for syntax error");
    }

    @Test
    void givenTestFileReferencingMissingClass_whenCompileTest_thenThrowsRuntimeException() throws Exception {
        Path testFile = writeTestFile("MissingRefTest.java", """
            import org.junit.jupiter.api.Test;
            
            public class MissingRefTest {
                @Test
                void testMissing() {
                    NonExistentClass obj = new NonExistentClass();
                }
            }
            """);

        assertThrows(RuntimeException.class,
            () -> compiler.compileTest(testFile, configuration),
            "Should throw exception when referencing non-existent class");
    }

    @Test
    void givenNonExistentFile_whenCompileTest_thenThrowsException() {
        Path nonExistentFile = tempDir.resolve("DoesNotExist.java");

        assertThrows(Exception.class,
            () -> compiler.compileTest(nonExistentFile, configuration),
            "Should throw exception for non-existent file");
    }

    // ==================== Edge Case Tests ====================

    @Test
    void givenTestFileWithDefaultPackage_whenCompileTest_thenClassFileCreated() throws Exception {
        Path testFile = writeTestFile("DefaultPackageTest.java", """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            
            public class DefaultPackageTest {
                @Test
                void testDefault() {
                    assertNotNull(new Object());
                }
            }
            """);

        Path compiledClass = compiler.compileTest(testFile, configuration);

        assertNotNull(compiledClass);
        assertTrue(Files.exists(compiledClass));
        assertEquals("DefaultPackageTest.class", compiledClass.getFileName().toString());
    }

    @Test
    void givenTestFileWithNestedClass_whenCompileTest_thenBothClassFilesCreated() throws Exception {
        Path testFile = writeTestFile("OuterTest.java", """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            public class OuterTest {

                static class Inner {
                    int getValue() { return 42; }
                }

                @Test
                void testNested() {
                    Inner inner = new Inner();
                    assertEquals(42, inner.getValue());
                }
            }
            """);

        Path compiledClass = compiler.compileTest(testFile, configuration);

        assertNotNull(compiledClass);
        assertTrue(Files.exists(compiledClass), "Outer class should be compiled");
        Path innerClass = compiledClass.getParent().resolve("OuterTest$Inner.class");
        assertTrue(Files.exists(innerClass), "Inner class should also be compiled");
    }

    @Test
    void givenNullSourceFile_whenCompileTest_thenThrowsRuntimeException() {
        // when/then - NullPointerException is wrapped in RuntimeException
        var exception = assertThrows(RuntimeException.class, () -> {
            compiler.compileTest(null, configuration);
        });

        // Verify the cause is NullPointerException
        assertNotNull(exception.getCause());
        assertTrue(exception.getCause() instanceof NullPointerException,
            "Expected NullPointerException as cause, got: " + exception.getCause().getClass());
    }

    @Test
    void givenNullSourceFiles_whenCompileTests_thenThrowsNullPointerException() {
        // when/then - compileTests doesn't wrap the exception
        assertThrows(NullPointerException.class, () -> {
            compiler.compileTests(null, configuration);
        });
    }

    @Test
    void givenEmptySourceFilesList_whenCompileTests_thenReturnsEmptyList() {
        // when
        var result = compiler.compileTests(List.of(), configuration);

        // then
        assertTrue(result.isEmpty());
    }

    // ==================== Helper Methods ====================

    private Path writeTestFile(String fileName, String content) throws Exception {
        Path testFile = tempDir.resolve(fileName);
        Files.writeString(testFile, content);
        return testFile;
    }
}

