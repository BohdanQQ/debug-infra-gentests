package cz.cuni.mff.d3s.autodebugger.model.java.identifiers;

import lombok.Builder;
import lombok.experimental.SuperBuilder;

@SuperBuilder
public class FieldIdentifierParameters extends ExportableValueIdentifierParameters {
    public final JavaClassIdentifier ownerClassIdentifier;
    @Builder.Default
    public final boolean isStatic = false;
}
