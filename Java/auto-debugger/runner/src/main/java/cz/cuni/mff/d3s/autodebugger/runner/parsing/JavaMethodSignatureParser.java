package cz.cuni.mff.d3s.autodebugger.runner.parsing;

import cz.cuni.mff.d3s.autodebugger.instrumentor.common.Instrumentor;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.DiSLInstrumentor;
import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.MethodIdentifier;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import cz.cuni.mff.d3s.autodebugger.model.java.parsing.MethodSignature;
import cz.cuni.mff.d3s.autodebugger.model.java.parsing.SignatureState;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java-specific implementation of the method signature parsing strategy.
 * Uses the existing JavaMethodSignatureParser from the model-java module.
 */
@Slf4j
public class JavaMethodSignatureParser {
    
    // Pattern for parameter: "slot:type" or "type:name"
    private static final Pattern PARAMETER_PATTERN = Pattern.compile("^(\\d+):(.+)$|^([^:]+):([^:]+)$");
    
    // Pattern for field: "type:name" or "static:type:name"
    private static final Pattern FIELD_PATTERN = Pattern.compile("^([^:]+):([^:]+)$");
    private static final Pattern STATIC_FIELD_PATTERN = Pattern.compile("^static:([^:]+):([^:]+)$");
    
    public JavaMethodIdentifier parseMethodReference(String methodReference) {
        return parseMethodReference(methodReference, false, false);
    }

    public JavaMethodIdentifier parseMethodReference(String methodReference, boolean isStaticMethod) {
        return parseMethodReference(methodReference, isStaticMethod, false);
    }

    public JavaMethodIdentifier parseMethodReference(String methodReference, boolean isStaticMethod, boolean isVoidMethod) {
        log.debug("Parsing Java method reference: {}", methodReference);

        MethodSignature signature = cz.cuni.mff.d3s.autodebugger.model.java.parsing.JavaMethodSignatureParser.parseMethodSignature(methodReference);

        if (signature.getState() != SignatureState.FULL_METHOD) {
            throw new IllegalArgumentException(
                "Invalid method reference format. Expected complete method signature like 'package.Class.method(param1,param2)'. Got: " + methodReference);
        }

        // Create package identifier
        JavaPackageIdentifier packageIdentifier = signature.isInDefaultPackage() ?
            JavaPackageIdentifier.DEFAULT_PACKAGE :
            new JavaPackageIdentifier(signature.getPackageName());

        // Create class identifier
        JavaClassIdentifier classIdentifier = new JavaClassIdentifier(
            ClassIdentifierParameters.builder()
                .packageIdentifier(packageIdentifier)
                .className(signature.getSimpleClassName())
                .build()
        );

        // Create method identifier
        JavaMethodIdentifier methodIdentifier = new JavaMethodIdentifier(
            MethodIdentifierParameters.builder()
                .ownerClassIdentifier(classIdentifier)
                .methodName(signature.getMethodName())
                .returnType(isVoidMethod ? "void" : null) // Set to "void" if explicitly marked, otherwise unknown
                .parameterTypes(signature.getParameterTypes())
                .isStatic(isStaticMethod)
                .build()
        );

        log.debug("Successfully parsed method: package={}, class={}, method={}, parameters={}, isStatic={}, isVoid={}",
                 signature.getPackageName(), signature.getSimpleClassName(),
                 signature.getMethodName(), signature.getParameterTypes(), isStaticMethod, isVoidMethod);

        return methodIdentifier;
    }
    
    public List<JavaValueIdentifier> parseTargetParameters(List<String> targetParameters, MethodIdentifier methodIdentifier) {
        if (targetParameters == null || targetParameters.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<JavaValueIdentifier> exportableValues = new ArrayList<>();
        
        for (int i = 0; i < targetParameters.size(); i++) {
            String parameterString = targetParameters.get(i).trim();
            if (parameterString.isEmpty()) {
                continue;
            }
            
            JavaArgumentIdentifier argumentIdentifier = parseParameter(parameterString, i);
            exportableValues.add(argumentIdentifier);
            
            log.debug("Converted parameter '{}' to ArgumentIdentifier with slot {} and type {}", 
                     parameterString, argumentIdentifier.getArgumentSlot(), argumentIdentifier.getType());
        }
        
        return exportableValues;
    }
    
    public List<JavaValueIdentifier> parseTargetFields(List<String> targetFields, JavaMethodIdentifier methodIdentifier) {
        if (targetFields == null || targetFields.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<JavaValueIdentifier> exportableValues = new ArrayList<>();
        JavaClassIdentifier ownerClass = methodIdentifier.getOwnerClassIdentifier();
        
        for (String fieldString : targetFields) {
            fieldString = fieldString.trim();
            if (fieldString.isEmpty()) {
                continue;
            }
            
            JavaFieldIdentifier fieldIdentifier = parseField(fieldString, ownerClass);
            exportableValues.add(fieldIdentifier);
            
            log.debug("Converted field '{}' to FieldIdentifier with name {} and type {}", 
                     fieldString, fieldIdentifier.getFieldName(), fieldIdentifier.getType());
        }
        
        return exportableValues;
    }
    
    /**
     * Parses a single parameter string into an ArgumentIdentifier.
     */
    private JavaArgumentIdentifier parseParameter(String parameterString, int defaultSlot) {
        Matcher matcher = PARAMETER_PATTERN.matcher(parameterString);
        
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Invalid parameter format. Expected 'slot:type' or 'type:name'. Got: " + parameterString);
        }
        
        int slot;
        String type;
        
        if (matcher.group(1) != null) {
            // Format: "slot:type"
            slot = Integer.parseInt(matcher.group(1));
            type = matcher.group(2);
        } else {
            // Format: "type:name" - use position as slot
            slot = defaultSlot;
            type = matcher.group(3);
        }
        
        type = normalizeType(type);
        
        return new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(slot)
                .variableType(type)
                .build()
        );
    }
    
    /**
     * Parses a single field string into a FieldIdentifier.
     * Supports formats: "type:name" for instance fields, "static:type:name" for static fields.
     */
    private JavaFieldIdentifier parseField(String fieldString, JavaClassIdentifier ownerClass) {
        // First try static field pattern
        var staticMatcher = STATIC_FIELD_PATTERN.matcher(fieldString);
        if (staticMatcher.matches()) {
            var type = normalizeType(staticMatcher.group(1));
            var name = staticMatcher.group(2);

            return new JavaFieldIdentifier(
                FieldIdentifierParameters.builder()
                    .variableType(type)
                    .variableName(name)
                    .ownerClassIdentifier(ownerClass)
                    .isStatic(true)
                    .build()
            );
        }

        // Try regular field pattern
        var matcher = FIELD_PATTERN.matcher(fieldString);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Invalid field format. Expected 'type:name' or 'static:type:name'. Got: " + fieldString);
        }

        var type = normalizeType(matcher.group(1));
        var name = matcher.group(2);

        return new JavaFieldIdentifier(
            FieldIdentifierParameters.builder()
                .variableType(type)
                .variableName(name)
                .ownerClassIdentifier(ownerClass)
                .isStatic(false)
                .build()
        );
    }
    
    /**
     * Normalizes type names to their fully qualified form when possible.
     */
    private String normalizeType(String type) {
        return switch (type) {
            case "String" -> "java.lang.String";
            case "Object" -> "java.lang.Object";
            case "List" -> "java.util.List";
            case "Map" -> "java.util.Map";
            case "Set" -> "java.util.Set";
            case "Integer" -> "java.lang.Integer";
            case "Long" -> "java.lang.Long";
            case "Double" -> "java.lang.Double";
            case "Float" -> "java.lang.Float";
            case "Boolean" -> "java.lang.Boolean";
            case "Character" -> "java.lang.Character";
            case "Byte" -> "java.lang.Byte";
            case "Short" -> "java.lang.Short";
            default -> type; // Keep as-is for primitives and other types
        };
    }
}
