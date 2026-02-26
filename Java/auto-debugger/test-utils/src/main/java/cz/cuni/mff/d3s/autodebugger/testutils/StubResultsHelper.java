package cz.cuni.mff.d3s.autodebugger.testutils;

import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.ArgumentIdentifierParameters;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaArgumentIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaValueIdentifier;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Utility to create stub test files and mock trace data for integration tests. */
public final class StubResultsHelper {
  private StubResultsHelper() {}

  /**
   * Writes a minimal stub test file and records its path in the provided results list file.
   * This is used by tests to simulate the output of the test generation process.
   *
   * @param resultsListPath The exact path where the results list file should be created.
   *                        This should match the path from InstrumentationResult.getResultsListPath()
   * @return Path to the created stub test file
   * @throws Exception if file creation fails
   */
  public static Path writeMinimalStubTestAndResults(Path resultsListPath) throws Exception {
    // Create parent directory for results file
    Files.createDirectories(resultsListPath.getParent());

    // Create stub test directory
    Path stubDir = resultsListPath.getParent().resolve("stub-tests");
    Files.createDirectories(stubDir);
    Path stub = stubDir.resolve("StubTest.java");

    String java = """
      import org.junit.jupiter.api.BeforeEach;
      import org.junit.jupiter.api.Test;
      import static org.junit.jupiter.api.Assertions.*;
      public class StubTest {
        @BeforeEach void setup(){ Object o = new Object(); assertNotNull(o); }
        @Test void testExample(){
          // Arrange
          int a = 1;
          // Act
          int b = a + 1;
          // Assert
          assertTrue(b > a);
        }
      }
      """;

    Files.writeString(stub, java);
    Files.write(resultsListPath, List.of(stub.toAbsolutePath().toString()));
    return stub;
  }

  /**
   * Serializes a Trace object to a file using Java serialization.
   * This matches the format used by the real Collector in DiSL instrumentation.
   *
   * @param traceFilePath Path where the trace file should be created
   * @param trace The Trace object to serialize
   * @throws Exception if serialization fails
   */
  public static void writeSerializedTrace(Path traceFilePath, Trace trace) throws Exception {
    Files.createDirectories(traceFilePath.getParent());
    try (FileOutputStream fileOutput = new FileOutputStream(traceFilePath.toFile());
         ObjectOutputStream objectStream = new ObjectOutputStream(fileOutput)) {
      objectStream.writeObject(trace);
    }
  }

  /**
   * Serializes an identifier mapping to a file using Java serialization.
   * This matches the format used by DiSLInstrumentor.serializeIdentifiers().
   *
   * @param identifierMappingPath Path where the identifier mapping file should be created
   * @param mapping The identifier mapping to serialize (slot ID -> identifier)
   * @throws Exception if serialization fails
   */
  public static void writeSerializedIdentifierMapping(
      Path identifierMappingPath,
      Map<Integer, JavaValueIdentifier> mapping) throws Exception {
    Files.createDirectories(identifierMappingPath.getParent());
    try (FileOutputStream fileOutput = new FileOutputStream(identifierMappingPath.toFile());
         ObjectOutputStream objectStream = new ObjectOutputStream(fileOutput)) {
      objectStream.writeObject(mapping);
    }
  }

  /**
   * Creates a minimal mock Trace with sample data for testing.
   * The trace contains two int values representing a simple method with two int parameters:
   * - Slot 1: value 42 (first parameter)
   * - Slot 2: value 17 (second parameter)
   *
   * @return A Trace object with minimal mock data
   */
  public static Trace createMinimalMockTrace() {
    Trace trace = new Trace();
    trace.addIntValue(1, 42);  // First argument
    trace.addIntValue(2, 17);  // Second argument
    return trace;
  }

  /**
   * Creates a minimal identifier mapping for testing.
   * The mapping contains two JavaArgumentIdentifier entries matching the slots
   * in createMinimalMockTrace():
   * - Slot 1: int argument at position 0
   * - Slot 2: int argument at position 1
   *
   * @return A Map from slot IDs to JavaValueIdentifier instances
   */
  public static Map<Integer, JavaValueIdentifier> createMinimalIdentifierMapping() {
    Map<Integer, JavaValueIdentifier> mapping = new HashMap<>();

    mapping.put(1, new JavaArgumentIdentifier(
        ArgumentIdentifierParameters.builder()
            .argumentSlot(0)
            .variableType("int")
            .build()
    ));

    mapping.put(2, new JavaArgumentIdentifier(
        ArgumentIdentifierParameters.builder()
            .argumentSlot(1)
            .variableType("int")
            .build()
    ));

    return mapping;
  }
}

