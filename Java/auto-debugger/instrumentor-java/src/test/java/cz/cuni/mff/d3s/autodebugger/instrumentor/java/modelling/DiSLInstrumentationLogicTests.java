package cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling;

import static cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.Constants.normalizeVariableNames;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaArgumentIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.ArgumentIdentifierParameters;
import org.junit.jupiter.api.Test;

import java.util.List;

class DiSLInstrumentationLogicTests {

  @Test
  void givenValidSerializationInstrumentationLogic_whenGeneratingCode_thenCodeIsGenerated() {
    // given
    DiSLInstrumentationLogic logic =
        new SerializationDiSLInstrumentationLogic(
            Constants.instrumentationLogicMethodIdentifier,
            Constants.dislAnnotation,
            Constants.instrumentationLogicExports);

    // when
    String code = logic.emitCode();

    // then
    String expectedCode = """
      @Before(marker = BodyMarker.class, scope = "targets.extraction.Test.test")
      public static void generatedMethod1(DynamicContext di) {
      FileOutputStream fileOut;ObjectOutputStream out;
      try {
      } catch (IOException e) {
      e.printStackTrace();
      }
      }
      """;
    assertEquals(normalizeVariableNames(expectedCode), normalizeVariableNames(code));
  }

  @Test
  void givenValidShadowInstrumentationLogic_whenGeneratingCode_thenCodeIsGenerated() {
    // given
    DiSLInstrumentationLogic logic =
        new ShadowDiSLInstrumentationLogic(
            Constants.instrumentationLogicMethodIdentifier,
            Constants.dislAnnotation,
            Constants.instrumentationLogicExports);

    // when
    String code = logic.emitCode();

    // then
    String expectedCode = """
      @Before(marker = BodyMarker.class, scope = "targets.extraction.Test.test")
      public static void generatedMethod1(DynamicContext di) {
      System.out.println("[Instrumentation process] PID: " + ProcessHandle.current().pid());
      CollectorRE.startEvent();
      }
      """;
    assertEquals(normalizeVariableNames(expectedCode), normalizeVariableNames(code));
  }

  @Test
  void givenMultipleExports_whenSerializing_thenWritesEachValue() {
    // given
    var idParams = Constants.instrumentationLogicIdentifierParameters;
    var methodId = Constants.instrumentationLogicMethodIdentifier;
    var annotation = Constants.dislAnnotation;

    var arg1 = new JavaArgument(0, new JavaArgumentIdentifier(
        ArgumentIdentifierParameters.builder().argumentSlot(0).variableType("int").build()));
    var arg2 = new JavaArgument(1, new JavaArgumentIdentifier(
        ArgumentIdentifierParameters.builder().argumentSlot(1).variableType("java.lang.String").build()));

    var logic = new SerializationDiSLInstrumentationLogic(
        methodId, annotation, List.of(arg1, arg2));

    // when
    String code = logic.emitCode();

    // then
    // Expect two writeObject blocks present in the emitted code
    int occurrences = code.split("out\\.writeObject\\(").length - 1;
    org.junit.jupiter.api.Assertions.assertTrue(occurrences >= 2, "Expected at least two out.writeObject(...) calls, got: " + occurrences);
  }

}
