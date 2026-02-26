package cz.cuni.mff.d3s.autodebugger.model.java.identifiers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JavaReturnValueIdentifier and related return value handling.
 */
class ReturnValueIdentifierTests {

    @Nested
    class IsVoidReturnTests {

        @Test
        void givenVoidReturnType_whenIsVoidReturn_thenReturnsTrue() {
            // given
            JavaClassIdentifier clazz = new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .className("TestClass")
                            .packageIdentifier(new JavaPackageIdentifier("com.example"))
                            .build());

            JavaMethodIdentifier method = new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                            .ownerClassIdentifier(clazz)
                            .methodName("doSomething")
                            .returnType("void")
                            .build());

            JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                    new ReturnValueIdentifierParameters(method));

            // when & then
            assertTrue(returnValue.isVoidReturn());
        }

        @Test
        void givenIntReturnType_whenIsVoidReturn_thenReturnsFalse() {
            // given
            JavaClassIdentifier clazz = new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .className("TestClass")
                            .packageIdentifier(new JavaPackageIdentifier("com.example"))
                            .build());

            JavaMethodIdentifier method = new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                            .ownerClassIdentifier(clazz)
                            .methodName("getCount")
                            .returnType("int")
                            .build());

            JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                    new ReturnValueIdentifierParameters(method));

            // when & then
            assertFalse(returnValue.isVoidReturn());
        }

        @Test
        void givenStringReturnType_whenIsVoidReturn_thenReturnsFalse() {
            // given
            JavaClassIdentifier clazz = new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .className("TestClass")
                            .packageIdentifier(new JavaPackageIdentifier("com.example"))
                            .build());

            JavaMethodIdentifier method = new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                            .ownerClassIdentifier(clazz)
                            .methodName("getName")
                            .returnType("java.lang.String")
                            .build());

            JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                    new ReturnValueIdentifierParameters(method));

            // when & then
            assertFalse(returnValue.isVoidReturn());
        }
    }

    @Nested
    class RequiresAfterCaptureTests {

        @Test
        void givenReturnValueIdentifier_whenRequiresAfterCapture_thenReturnsTrue() {
            // given
            JavaClassIdentifier clazz = new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .className("TestClass")
                            .packageIdentifier(new JavaPackageIdentifier("com.example"))
                            .build());

            JavaMethodIdentifier method = new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                            .ownerClassIdentifier(clazz)
                            .methodName("calculate")
                            .returnType("int")
                            .build());

            JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                    new ReturnValueIdentifierParameters(method));

            // when & then
            assertTrue(returnValue.requiresAfterCapture());
        }

        @Test
        void givenArgumentIdentifier_whenRequiresAfterCapture_thenReturnsFalse() {
            // given
            JavaArgumentIdentifier arg = new JavaArgumentIdentifier(
                    ArgumentIdentifierParameters.builder()
                            .argumentSlot(0)
                            .variableType("int")
                            .build());

            // when & then
            assertFalse(arg.requiresAfterCapture());
        }

        @Test
        void givenFieldIdentifier_whenRequiresAfterCapture_thenReturnsFalse() {
            // given
            JavaClassIdentifier clazz = new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .className("TestClass")
                            .packageIdentifier(new JavaPackageIdentifier("com.example"))
                            .build());

            JavaFieldIdentifier field = new JavaFieldIdentifier(
                    FieldIdentifierParameters.builder()
                            .ownerClassIdentifier(clazz)
                            .variableName("counter")
                            .variableType("int")
                            .isStatic(false)
                            .build());

            // when & then
            assertFalse(field.requiresAfterCapture());
        }

        @Test
        void givenStaticFieldIdentifier_whenRequiresAfterCapture_thenReturnsFalse() {
            // given
            JavaClassIdentifier clazz = new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .className("TestClass")
                            .packageIdentifier(new JavaPackageIdentifier("com.example"))
                            .build());

            JavaFieldIdentifier staticField = new JavaFieldIdentifier(
                    FieldIdentifierParameters.builder()
                            .ownerClassIdentifier(clazz)
                            .variableName("INSTANCE_COUNT")
                            .variableType("int")
                            .isStatic(true)
                            .build());

            // when & then
            assertFalse(staticField.requiresAfterCapture());
        }
    }

    @Nested
    class ReturnValueNameTests {

        @Test
        void givenReturnValueIdentifier_whenGetName_thenReturnsReturnPrefixedMethodName() {
            // given
            JavaClassIdentifier clazz = new JavaClassIdentifier(
                    ClassIdentifierParameters.builder()
                            .className("Calculator")
                            .packageIdentifier(new JavaPackageIdentifier("com.example"))
                            .build());

            JavaMethodIdentifier method = new JavaMethodIdentifier(
                    MethodIdentifierParameters.builder()
                            .ownerClassIdentifier(clazz)
                            .methodName("add")
                            .returnType("int")
                            .parameterTypes(List.of("int", "int"))
                            .build());

            JavaReturnValueIdentifier returnValue = new JavaReturnValueIdentifier(
                    new ReturnValueIdentifierParameters(method));

            // when
            String name = returnValue.getName();

            // then
            assertEquals("return_add", name);
        }
    }
}

