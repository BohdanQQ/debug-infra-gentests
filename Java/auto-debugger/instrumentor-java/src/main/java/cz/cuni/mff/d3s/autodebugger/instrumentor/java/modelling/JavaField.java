package cz.cuni.mff.d3s.autodebugger.instrumentor.java.modelling;

import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaValueIdentifier;
import lombok.Getter;

@Getter
public class JavaField extends JavaValue {
    private final String name;
    private final String ownerType;
    private final boolean isStatic;

    public JavaField(String name, String ownerType, boolean isStatic, JavaValueIdentifier exportableValue) {
        super(exportableValue);
        this.name = name;
        // TODO: Use fully qualified name for ownerType
        this.ownerType = ownerType;
        this.isStatic = isStatic;
    }

    @Override
    public String emitCode() {
        append(exportedValueIdentifier.getType());
        append(" ");
        append(instrumentationVariableIdentifier.getName());
        append(" = ");

        if (isStatic) {
            append("di.getStaticFieldValue(");
            append(ownerType);
            append(".class, \"");
            append(name);
            append("\", ");
            append(exportedValueIdentifier.getType());
            append(".class);");
        } else {
            append("di.getInstanceFieldValue(di.getThis(), ");
            append(ownerType);
            append(".class, \"");
            append(name);
            append("\", ");
            append(exportedValueIdentifier.getType());
            append(".class);");
        }

        return getCode();
    }
}
