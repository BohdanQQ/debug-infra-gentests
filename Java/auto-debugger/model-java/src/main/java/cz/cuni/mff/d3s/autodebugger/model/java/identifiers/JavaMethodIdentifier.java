package cz.cuni.mff.d3s.autodebugger.model.java.identifiers;

import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.MethodIdentifier;
import lombok.Getter;

import java.io.Serializable;
import java.util.stream.Collectors;

/**
 * Java-specific method identifier implementation.
 * Extends the base MethodIdentifier with Java-specific class ownership
 * information for precise method identification in Java applications.
 */
@Getter
public class JavaMethodIdentifier extends MethodIdentifier implements Serializable {
    private final JavaClassIdentifier ownerClassIdentifier;
    private final boolean isStatic;

    public JavaMethodIdentifier(MethodIdentifierParameters parameters) {
        super(parameters.methodName, parameters.returnType, parameters.parameterTypes);
        this.ownerClassIdentifier = parameters.ownerClassIdentifier;
        this.isStatic = parameters.isStatic;
    }

    /**
     * Gets the package name from the owner class identifier.
     *
     * @return The package name, or empty string if in default package
     */
    public String getPackageName() {
        if (ownerClassIdentifier == null || ownerClassIdentifier.getPackageIdentifier() == null) {
            return "";
        }
        return ownerClassIdentifier.getPackageIdentifier().getPackageName();
    }

    /**
     * Gets the simple class name (without package) from the owner class identifier.
     *
     * @return The simple class name
     */
    public String getClassName() {
        if (ownerClassIdentifier == null) {
            return "UnknownClass";
        }
        return ownerClassIdentifier.getClassName();
    }

    /**
     * Gets the fully qualified class name (package + class name).
     *
     * @return The fully qualified class name
     */
    public String getFullyQualifiedClassName() {
        if (ownerClassIdentifier == null) {
            return "UnknownClass";
        }

        String packageName = getPackageName();
        String className = getClassName();

        if (packageName.isEmpty()) {
            return className;
        }
        return packageName + "." + className;
    }

    /**
     * Gets a simple method signature in the format "methodName(param1, param2)".
     *
     * @return The simple method signature
     */
    public String getSimpleSignature() {
        if (parameterTypes == null || parameterTypes.isEmpty()) {
            return methodName + "()";
        }

        String params = parameterTypes.stream()
                .map(this::getSimpleTypeName)
                .collect(Collectors.joining(", "));

        return methodName + "(" + params + ")";
    }

    /**
     * Gets a fully qualified method signature in the format "package.Class.methodName(param1, param2)".
     *
     * @return The fully qualified method signature
     */
    public String getFullyQualifiedSignature() {
        return getFullyQualifiedClassName() + "." + getSimpleSignature();
    }

    /**
     * Gets a method signature suitable for test generation in the format "Class.methodName(param1, param2)".
     * This excludes the package name for cleaner test method names.
     *
     * @return The test-friendly method signature
     */
    public String getTestFriendlySignature() {
        return getClassName() + "." + getSimpleSignature();
    }

    /**
     * Extracts the simple type name from a fully qualified type name.
     * For example, "java.lang.String" becomes "String".
     * Handles generic types like "java.util.Map<java.lang.String,java.lang.Object>" -> "Map".
     *
     * @param fullyQualifiedType The fully qualified type name
     * @return The simple type name
     */
    private String getSimpleTypeName(String fullyQualifiedType) {
        if (fullyQualifiedType == null || fullyQualifiedType.isEmpty()) {
            return fullyQualifiedType;
        }

        // Handle generic types by removing everything after '<'
        String baseType = fullyQualifiedType;
        int genericStart = baseType.indexOf('<');
        if (genericStart != -1) {
            baseType = baseType.substring(0, genericStart);
        }

        // Extract simple name from the base type
        int lastDot = baseType.lastIndexOf('.');
        if (lastDot == -1) {
            return baseType;
        }

        return baseType.substring(lastDot + 1);
    }
}
