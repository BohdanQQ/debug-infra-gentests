package cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.Test;

class JavaValueCollectorEmissionTests {

  private JavaArgument argOfType(String type) {
    var id = new JavaArgumentIdentifier(
        ArgumentIdentifierParameters.builder().argumentSlot(0).variableType(type).build());
    return new JavaArgument(0, id);
  }

  @Test
  void givenIntArgument_whenEmitCollectorCode_thenUsesCollectInt() {
    var id = new JavaArgumentIdentifier(
        ArgumentIdentifierParameters.builder().argumentSlot(0).variableType("int").build());
    var arg = new JavaArgument(0, id);
    String call = arg.emitCollectorCode();
    assertTrue(call.contains("collectInt("));
  }

  @Test
  void givenStringField_whenEmitCollectorCode_thenUsesCollectString() {
    var owner = new JavaClassIdentifier(
        ClassIdentifierParameters.builder().className("C").packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE).build());
    var id = new JavaFieldIdentifier(
        FieldIdentifierParameters.builder().variableName("name").variableType("java.lang.String").ownerClassIdentifier(owner).build());
    var field = new JavaField("name", owner.getName(), id.isStatic(), id);
    String call = field.emitCollectorCode();
    assertTrue(call.contains("collectString("));
  }

  @Test
  void givenObjectReturn_whenEmitCollectorCode_thenUsesCollectObject() {
    var owner = new JavaClassIdentifier(
        ClassIdentifierParameters.builder().className("C").packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE).build());
    var mid = new JavaMethodIdentifier(
        MethodIdentifierParameters.builder().ownerClassIdentifier(owner).methodName("m").returnType("java.lang.Object").build());
    var id = new JavaReturnValueIdentifier(new ReturnValueIdentifierParameters(mid));
    var ret = new JavaReturnValue(id);
    String call = ret.emitCollectorCode();
    assertTrue(call.contains("collectObject("));
  }

  @Test void givenByteArg_thenCollectByte() { assertTrue(argOfType("byte").emitCollectorCode().contains("collectByte(")); }
  @Test void givenCharArg_thenCollectChar() { assertTrue(argOfType("char").emitCollectorCode().contains("collectChar(")); }
  @Test void givenShortArg_thenCollectShort() { assertTrue(argOfType("short").emitCollectorCode().contains("collectShort(")); }
  @Test void givenLongArg_thenCollectLong() { assertTrue(argOfType("long").emitCollectorCode().contains("collectLong(")); }
  @Test void givenFloatArg_thenCollectFloat() { assertTrue(argOfType("float").emitCollectorCode().contains("collectFloat(")); }
  @Test void givenDoubleArg_thenCollectDouble() { assertTrue(argOfType("double").emitCollectorCode().contains("collectDouble(")); }
  @Test void givenBooleanArg_thenCollectBoolean() { assertTrue(argOfType("boolean").emitCollectorCode().contains("collectBoolean(")); }
}

