package cz.cuni.mff.d3s.autodebugger.model.java.identifiers;

import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.MethodIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.enums.ValueType;
import cz.cuni.mff.d3s.autodebugger.model.java.factories.IdentifierFactory;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@NoArgsConstructor
public class JavaReturnValueIdentifier extends JavaValueIdentifier implements ExportableValue, Serializable {
    private int internalId;
    private MethodIdentifier methodIdentifier;

    public JavaReturnValueIdentifier(ReturnValueIdentifierParameters parameters) {
        super(ValueType.RETURN_VALUE);
        this.methodIdentifier = parameters.methodIdentifier;
        this.internalId = IdentifierFactory.getNextId();
        this.type = parameters.variableType;
    }

    @Override
    public String getName() {
        return "return_" + methodIdentifier.getName();
    }

    @Override
    public int getInternalId() {
        return internalId;
    }

    @Override
    public String getType() {
        return type;
    }

    /**
     * Returns true if the associated method returns void.
     * Return values cannot be captured for void methods as there is no value on the stack.
     *
     * @return true if the method's return type is void
     */
    public boolean isVoidReturn() {
        return "void".equals(type) || type == null;
    }
}
