package cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling;

import static cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.Constants.dislMarker;
import static cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.Constants.dislScope;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.enums.ActivationTime;
import org.junit.jupiter.api.Test;

class DiSLAnnotationTests {

  @Test
  void givenValidDiSLAnnotation_whenGeneratingCode_thenCodeIsGenerated() {
    // given
    DiSLAnnotation dislAnnotation =
        new DiSLAnnotation(ActivationTime.BEFORE, dislMarker, dislScope);

    // when
    String code = dislAnnotation.emitCode();

    // then
    assertEquals("@Before(marker = BodyMarker.class, scope = \"targets.extraction.Test.test\")", code);
  }
}
