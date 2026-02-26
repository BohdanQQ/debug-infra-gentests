package cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling;

import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.enums.ActivationTime;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.enums.MarkerType;
import cz.cuni.mff.d3s.autodebugger.model.java.factories.MethodIdentifierFactory;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;

import java.nio.file.Path;
import java.util.List;

/**
 * Test constants for instrumentor Java module testing.
 * Provides shared test data including target class information, identifiers,
 * and utility methods for consistent test setup across test classes.
 */
public class Constants {
    public static final String targetClassName = "Test";
    public static final String targetMethodName = "test";
    public static final Path targetJarPath = Path.of("src/test/resources/targets/extraction/Test.jar");
    public static final JavaPackageIdentifier packageIdentifier = new JavaPackageIdentifier("targets.extraction");
    public static final JavaClassIdentifier testClassIdentifier =
            new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .className(targetClassName)
                            .packageIdentifier(packageIdentifier)
                            .build());

    public static final MethodIdentifierParameters targetMethodIdentifierParameters =
            MethodIdentifierParameters.builder()
                    .ownerClassIdentifier(testClassIdentifier)
                    .methodName(targetMethodName)
                    .returnType("void")
                    .build();
  public static final MethodIdentifierParameters instrumentationLogicIdentifierParameters =
      MethodIdentifierParameters.builder()
          .returnType("void")
          .ownerClassIdentifier(
                  new JavaClassIdentifier(
                          ClassIdentifierParameters
                                  .builder()
                                  .className("DiSLClass")
                                  .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                                  .build()))
          .parameterTypes(List.of("DynamicContext"))
          .build();

    public static final JavaMethodIdentifier targetMethodIdentifier = new JavaMethodIdentifier(targetMethodIdentifierParameters);
    public static final JavaMethodIdentifier instrumentationLogicMethodIdentifier = MethodIdentifierFactory.getInstance().generateIdentifier(instrumentationLogicIdentifierParameters);

    public static final DiSLMarker dislMarker = new DiSLMarker(MarkerType.BODY);

    public static final DiSLScope dislScope = new DiSLScope(targetMethodIdentifier);

    public static final DiSLAnnotation dislAnnotation = new DiSLAnnotation(ActivationTime.BEFORE, dislMarker, dislScope);

    public static final List<JavaValue> instrumentationLogicExports = List.of();

    private static final JavaArgumentIdentifier stringArgumentIdentifier =
      new JavaArgumentIdentifier(
              ArgumentIdentifierParameters.builder()
                      .argumentSlot(0)
                      .variableType("java.lang.String")
                      .build());
    public static final JavaArgument javaArgument = new JavaArgument(0, stringArgumentIdentifier);

    private static final JavaFieldIdentifier stringFieldIdentifier =
      new JavaFieldIdentifier(
              FieldIdentifierParameters.builder()
                      .variableName("testField")
                      .variableType("java.lang.String")
                      .ownerClassIdentifier(testClassIdentifier)
                      .build());
    public static final JavaField javaField = new JavaField(stringFieldIdentifier.getFieldName(), stringFieldIdentifier.getOwnerClassIdentifier().getName(), stringFieldIdentifier.isStatic(), stringFieldIdentifier);

    public static final JavaVariable javaVariable = new JavaVariable(0, stringArgumentIdentifier);

    // TODO
    //public static final JavaVariable javaVariable = new JavaVariable(0, "String");

    public static final JavaPackageImport javaPackageImport =
      new JavaPackageImport(new JavaPackageIdentifier("java.util.List"));

    public static final JavaPackage javaPackage = new JavaPackage(new JavaPackageIdentifier("cz.cuni.mff.d3s.test"));

    public static String normalizeVariableNames(String code) {
        return code
            .replaceAll("generatedVariable\\d+", "generatedVariableX")
            .replaceAll("generatedMethod\\d+", "generatedMethodX");
    }
}
