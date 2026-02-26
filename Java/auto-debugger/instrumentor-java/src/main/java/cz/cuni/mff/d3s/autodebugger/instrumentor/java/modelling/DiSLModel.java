package cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling;

import cz.cuni.mff.d3s.autodebugger.instrumentor.common.modelling.InstrumentationModel;
import cz.cuni.mff.d3s.autodebugger.model.java.factories.IdentifierFactory;
import cz.cuni.mff.d3s.autodebugger.model.java.factories.MethodIdentifierFactory;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.factories.ExportableValueFactory;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.enums.ActivationTime;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling.enums.MarkerType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * DiSL-specific instrumentation model for Java applications.
 * Extends the base InstrumentationModel to provide DiSL-specific code generation
 * with predefined imports, annotations, and instrumentation logic patterns.
 */
@Slf4j
public class DiSLModel extends InstrumentationModel {

  @Getter
  private final JavaMethodIdentifier targetMethod;

  private final String DEFAULT_PACKAGE_NAME = "cz.cuni.mff.d3s.autodebugger.analyzer.disl";
  private final JavaPackage DEFAULT_PACKAGE =
      new JavaPackage(
          IdentifierFactory.createFrom(
              new IdentifierParameters(new PackageIdentifierParameters(DEFAULT_PACKAGE_NAME))));

  private static final List<String> BASE_DISL_LIBRARY_IMPORTS =
      List.of(
          "ch.usi.dag.disl.annotation.After",
          "ch.usi.dag.disl.annotation.Before",
          "ch.usi.dag.disl.dynamiccontext.DynamicContext",
          "ch.usi.dag.disl.marker.BodyMarker");

  private static final String AFTER_RETURNING_IMPORT = "ch.usi.dag.disl.annotation.AfterReturning";

  private static final List<String> JAVA_IMPORTS =
      List.of(
          "java.io.FileNotFoundException",
          "java.io.IOException",
          "java.io.FileOutputStream",
          "java.io.ObjectOutputStream");

  public DiSLModel(JavaMethodIdentifier targetMethod, List<JavaValueIdentifier> exportedValues) {
    this.targetMethod = targetMethod;
    var classBuilder = DiSLClass.builder();

    // Build mutable list of DiSL imports that can be extended
    List<String> dislImports = new ArrayList<>(BASE_DISL_LIBRARY_IMPORTS);

    // Separate exports by capture timing:
    // - beforeExports: values captured at method entry (arguments, fields, static fields)
    // - afterExports: values captured at method exit (return values + fields for state tracking)
    List<JavaValue> beforeExports = new ArrayList<>();
    List<JavaValue> afterExports = new ArrayList<>();
    boolean hasReturnValues = false;

    for (var identifier : exportedValues) {
      if (identifier instanceof JavaValueIdentifier valueIdentifier) {
        // Filter out void return values - they cannot be captured
        if (valueIdentifier instanceof JavaReturnValueIdentifier returnValueId
            && returnValueId.isVoidReturn()) {
          log.warn("Skipping return value capture for void method: {}",
              returnValueId.getMethodIdentifier().getName());
          continue;
        }

        JavaValue export = ExportableValueFactory.createFrom(valueIdentifier);
        getImport(valueIdentifier).ifPresent(i -> {
          // We'll add imports later after building the list
        });

        if (valueIdentifier.requiresAfterCapture()) {
          // Return values only go in after hook
          afterExports.add(export);
          hasReturnValues = true;
        } else {
          // Arguments, fields, static fields go in BOTH hooks
          // This enables state change tracking (value at entry vs exit)
          beforeExports.add(export);
          afterExports.add(export);
        }
      } else {
        log.error("Variable {} is not a ExportableIdentifier", identifier);
      }
    }

    // Add AfterReturning import only if we have return values to capture
    if (hasReturnValues) {
      dislImports.add(AFTER_RETURNING_IMPORT);
    }

    // Build imports list
    List<JavaPackageImport> imports =
        Stream.concat(dislImports.stream(), JAVA_IMPORTS.stream())
            .map(PackageIdentifierParameters::new)
            .map(IdentifierParameters::new)
            .map(IdentifierFactory::createFrom)
            .map(JavaPackageImport::new)
            .collect(Collectors.toList());

    // Add type-specific imports (e.g., for field owner classes)
    for (var identifier : exportedValues) {
      if (identifier instanceof JavaValueIdentifier valueIdentifier) {
        getImport(valueIdentifier).ifPresent(i -> imports.add(new JavaPackageImport(i)));
      }
    }

    classBuilder.imports(imports);

    // Build method identifier parameters for instrumentation methods
    var parameters =
        MethodIdentifierParameters.builder()
            .returnType("void")
            .ownerClassIdentifier(
                new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                        .className("DiSLClass")
                        .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                        .build()))
            .parameterTypes(List.of("DynamicContext"))
            .build();

    // Conditionally create instrumentation methods based on what needs to be captured
    List<DiSLInstrumentationLogic> instrumentationMethods = new ArrayList<>();

    // Create @Before method if there are values to capture at method entry
    if (!beforeExports.isEmpty()) {
      var beforeAnnotation =
          new DiSLAnnotation(
              ActivationTime.BEFORE, new DiSLMarker(MarkerType.BODY), new DiSLScope(targetMethod));
      instrumentationMethods.add(new ShadowDiSLInstrumentationLogic(
          MethodIdentifierFactory.getInstance().generateIdentifier(parameters),
          beforeAnnotation,
          beforeExports));
    }

    // Create @After or @AfterReturning method if there are values to capture at method exit
    if (!afterExports.isEmpty()) {
      // Use @AfterReturning if we're capturing return values, @After otherwise
      // @AfterReturning only fires on normal returns, ensuring return value is on stack
      ActivationTime afterActivation = hasReturnValues
          ? ActivationTime.AFTER_RETURNING
          : ActivationTime.AFTER;

      var afterAnnotation =
          new DiSLAnnotation(
              afterActivation, new DiSLMarker(MarkerType.BODY), new DiSLScope(targetMethod));
      instrumentationMethods.add(new ShadowDiSLInstrumentationLogic(
          MethodIdentifierFactory.getInstance().generateIdentifier(parameters),
          afterAnnotation,
          afterExports));
    }

    classBuilder.instrumentationMethods(instrumentationMethods);
    rootClass = classBuilder.build();
  }

  @Override
  public String transform() {
    var result = super.transform();
    return addIndentation(result);
  }

  private Optional<JavaPackageIdentifier> getImport(
      JavaValueIdentifier valueIdentifier) {
    if (valueIdentifier instanceof JavaFieldIdentifier fieldIdentifier) {
      // Don't generate import for classes in the default package
      JavaClassIdentifier ownerClass = fieldIdentifier.getOwnerClassIdentifier();
      if (ownerClass.getPackageIdentifier() == null ||
          ownerClass.getPackageIdentifier().getPackageName().isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(ownerClass.getAsImportablePackage());
    }
    return Optional.empty();
  }

  private String addIndentation(String code) {
    StringBuilder indentedCode = new StringBuilder();
    int indentLevel = 0;
    for (char c : code.toCharArray()) {
      if (c == '{') {
        indentLevel++;
      }
      if (c == '}') {
        indentLevel--;
        assert (indentLevel >= 0);
        assert (indentedCode.length() - 1 >= 0);
        if (indentedCode.charAt(indentedCode.length() - 1) == '\t') {
          indentedCode.deleteCharAt(indentedCode.length() - 1);
        }
      }
      if (c == '\n') {
        indentedCode.append(c);
        indentedCode.append("\t".repeat(indentLevel));
        continue;
      }
      indentedCode.append(c);
    }
    return indentedCode.toString();
  }
}
