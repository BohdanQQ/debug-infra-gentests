package cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling;

import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaValueIdentifier;

public class JavaReturnValue extends JavaValue {

    public JavaReturnValue(JavaValueIdentifier exportableValue) {
        super(exportableValue);
    }

    @Override
    public String emitCode() {
        append(exportedValueIdentifier.getType());
        append(" ");
        append(instrumentationVariableIdentifier.getName());
        append(" = ");
        append("di.getStackValue(");
        append("0");
        append(", ");
        append(exportedValueIdentifier.getType());
        append(".class);");
        return getCode();
    }
}
