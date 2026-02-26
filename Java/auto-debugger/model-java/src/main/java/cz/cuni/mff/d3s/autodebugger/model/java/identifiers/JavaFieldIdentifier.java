package cz.cuni.mff.d3s.autodebugger.model.java.identifiers;

import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import cz.cuni.mff.d3s.autodebugger.model.java.enums.ValueType;
import cz.cuni.mff.d3s.autodebugger.model.java.factories.IdentifierFactory;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@NoArgsConstructor
public class JavaFieldIdentifier extends JavaValueIdentifier implements ExportableValue, Serializable {
    private int internalId;
    private String fieldName;
    private JavaClassIdentifier ownerClassIdentifier;
    private boolean isStatic;

    public JavaFieldIdentifier(FieldIdentifierParameters parameters) {
        super(ValueType.FIELD);
        this.internalId = IdentifierFactory.getNextId();
        this.fieldName = parameters.variableName;
        this.ownerClassIdentifier = parameters.ownerClassIdentifier;
        this.type = parameters.variableType;
        this.isStatic = parameters.isStatic;
    }

    @Override
    public String getName() {
        return fieldName;
    }
}
