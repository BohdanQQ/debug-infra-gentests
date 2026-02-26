package cz.cuni.mff.d3s.autodebugger.model.java.identifiers;

import java.util.List;
import lombok.Builder;

@Builder
public class MethodIdentifierParameters {
  public JavaClassIdentifier ownerClassIdentifier;
  public String methodName;
  public String returnType;
  public List<String> parameterTypes;

  @Builder.Default
  public boolean isStatic = false;
}
