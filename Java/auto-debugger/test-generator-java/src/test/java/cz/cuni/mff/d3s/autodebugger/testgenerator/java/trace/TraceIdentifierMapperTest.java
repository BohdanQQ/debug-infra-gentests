package cz.cuni.mff.d3s.autodebugger.testgenerator.java.trace;

import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TraceIdentifierMapperTest {

    private Trace trace;
    private Map<Integer, JavaValueIdentifier> identifierMapping;
    private TraceIdentifierMapper mapper;

    @BeforeEach
    void setUp() {
        trace = new Trace();
        identifierMapping = new HashMap<>();
    }

    // Helper methods
    private JavaArgumentIdentifier createArgumentIdentifier(int slot, String type) {
        return new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(slot)
                .variableType(type)
                .build()
        );
    }

    private JavaFieldIdentifier createFieldIdentifier(String fieldName, String type) {
        JavaClassIdentifier ownerClass = new JavaClassIdentifier(
            ClassIdentifierParameters.builder()
                .className("TestClass")
                .packageIdentifier(new JavaPackageIdentifier("com.example"))
                .build()
        );
        return new JavaFieldIdentifier(
            FieldIdentifierParameters.builder()
                .variableName(fieldName)
                .variableType(type)
                .ownerClassIdentifier(ownerClass)
                .build()
        );
    }

    private JavaReturnValueIdentifier createReturnValueIdentifier(String methodName, String returnType) {
        JavaClassIdentifier ownerClass = new JavaClassIdentifier(
            ClassIdentifierParameters.builder()
                .className("TestClass")
                .packageIdentifier(new JavaPackageIdentifier("com.example"))
                .build()
        );
        JavaMethodIdentifier methodIdentifier = new JavaMethodIdentifier(
            MethodIdentifierParameters.builder()
                .ownerClassIdentifier(ownerClass)
                .methodName(methodName)
                .returnType(returnType)
                .parameterTypes(java.util.List.of())
                .build()
        );
        return new JavaReturnValueIdentifier(
            new ReturnValueIdentifierParameters(methodIdentifier)
        );
    }

    @Nested
    class A_CoreFunctionality {

        @Test
        void givenValidMapping_whenGettingSlots_thenReturnsAllSlotIds() {
            // Given
            identifierMapping.put(0, createArgumentIdentifier(0, "int"));
            identifierMapping.put(1, createArgumentIdentifier(1, "int"));
            identifierMapping.put(2, createFieldIdentifier("count", "int"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<Integer> slots = mapper.getSlots();

            // Then
            assertEquals(3, slots.size());
            assertTrue(slots.contains(0));
            assertTrue(slots.contains(1));
            assertTrue(slots.contains(2));
        }

        @Test
        void givenValidSlot_whenGettingExportableValue_thenReturnsCorrectIdentifier() {
            // Given
            JavaArgumentIdentifier argIdentifier = createArgumentIdentifier(0, "int");
            identifierMapping.put(0, argIdentifier);
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            JavaValueIdentifier result = mapper.getExportableValue(0);

            // Then
            assertNotNull(result);
            assertInstanceOf(JavaArgumentIdentifier.class, result);
            assertEquals("int", result.getType());
        }

        @Test
        void givenIntValues_whenGettingSlotValues_thenReturnsCorrectValues() {
            // Given
            trace.addIntValue(0, 10);
            trace.addIntValue(0, 20);
            trace.addIntValue(0, 30);
            identifierMapping.put(0, createArgumentIdentifier(0, "int"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(3, values.size());
            assertTrue(values.contains(10));
            assertTrue(values.contains(20));
            assertTrue(values.contains(30));
        }

        @Test
        void givenEmptySlot_whenGettingSlotValues_thenReturnsEmptySet() {
            // Given
            identifierMapping.put(0, createArgumentIdentifier(0, "int"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertNotNull(values);
            assertTrue(values.isEmpty());
        }
    }

    @Nested
    class B_TypeCoverage {

        @Test
        void givenByteType_whenGettingSlotValues_thenReturnsByteValues() {
            // Given
            trace.addByteValue(0, (byte) 1);
            trace.addByteValue(0, (byte) 2);
            identifierMapping.put(0, createArgumentIdentifier(0, "byte"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(2, values.size());
            assertTrue(values.contains((byte) 1));
            assertTrue(values.contains((byte) 2));
        }

        @Test
        void givenCharType_whenGettingSlotValues_thenReturnsCharValues() {
            // Given
            trace.addCharValue(0, 'A');
            trace.addCharValue(0, 'B');
            identifierMapping.put(0, createArgumentIdentifier(0, "char"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(2, values.size());
            assertTrue(values.contains('A'));
            assertTrue(values.contains('B'));
        }

        @Test
        void givenShortType_whenGettingSlotValues_thenReturnsShortValues() {
            // Given
            trace.addShortValue(0, (short) 100);
            trace.addShortValue(0, (short) 200);
            identifierMapping.put(0, createArgumentIdentifier(0, "short"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(2, values.size());
            assertTrue(values.contains((short) 100));
            assertTrue(values.contains((short) 200));
        }

        @Test
        void givenIntType_whenGettingSlotValues_thenReturnsIntValues() {
            // Given
            trace.addIntValue(0, 1000);
            trace.addIntValue(0, 2000);
            identifierMapping.put(0, createArgumentIdentifier(0, "int"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(2, values.size());
            assertTrue(values.contains(1000));
            assertTrue(values.contains(2000));
        }

        @Test
        void givenLongType_whenGettingSlotValues_thenReturnsLongValues() {
            // Given
            trace.addLongValue(0, 10000L);
            trace.addLongValue(0, 20000L);
            identifierMapping.put(0, createArgumentIdentifier(0, "long"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(2, values.size());
            assertTrue(values.contains(10000L));
            assertTrue(values.contains(20000L));
        }

        @Test
        void givenFloatType_whenGettingSlotValues_thenReturnsFloatValues() {
            // Given
            trace.addFloatValue(0, 1.5f);
            trace.addFloatValue(0, 2.5f);
            identifierMapping.put(0, createArgumentIdentifier(0, "float"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(2, values.size());
            assertTrue(values.contains(1.5f));
            assertTrue(values.contains(2.5f));
        }

        @Test
        void givenDoubleType_whenGettingSlotValues_thenReturnsDoubleValues() {
            // Given
            trace.addDoubleValue(0, 10.5);
            trace.addDoubleValue(0, 20.5);
            identifierMapping.put(0, createArgumentIdentifier(0, "double"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(2, values.size());
            assertTrue(values.contains(10.5));
            assertTrue(values.contains(20.5));
        }

        @Test
        void givenBooleanType_whenGettingSlotValues_thenReturnsBooleanValues() {
            // Given
            trace.addBooleanValue(0, true);
            trace.addBooleanValue(0, false);
            identifierMapping.put(0, createArgumentIdentifier(0, "boolean"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(2, values.size());
            assertTrue(values.contains(true));
            assertTrue(values.contains(false));
        }

        @Test
        void givenStringType_whenGettingSlotValues_thenReturnsStringValues() {
            // Given
            trace.addStringValue(0, "hello");
            trace.addStringValue(0, "world");
            identifierMapping.put(0, createArgumentIdentifier(0, "java.lang.String"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(2, values.size());
            assertTrue(values.contains("hello"));
            assertTrue(values.contains("world"));
        }
    }

    @Nested
    class C_EdgeCases {

        @Test
        void givenEmptyMapping_whenGettingSlots_thenReturnsEmptySet() {
            // Given
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<Integer> slots = mapper.getSlots();

            // Then
            assertNotNull(slots);
            assertTrue(slots.isEmpty());
        }

        @Test
        void givenMultipleSlotsWithSameType_whenGettingValues_thenReturnsCorrectValuesForEachSlot() {
            // Given
            trace.addIntValue(0, 10);
            trace.addIntValue(1, 20);
            trace.addIntValue(2, 30);
            identifierMapping.put(0, createArgumentIdentifier(0, "int"));
            identifierMapping.put(1, createArgumentIdentifier(1, "int"));
            identifierMapping.put(2, createFieldIdentifier("count", "int"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values0 = mapper.getSlotValues(0);
            Set<?> values1 = mapper.getSlotValues(1);
            Set<?> values2 = mapper.getSlotValues(2);

            // Then
            assertEquals(1, values0.size());
            assertTrue(values0.contains(10));
            assertEquals(1, values1.size());
            assertTrue(values1.contains(20));
            assertEquals(1, values2.size());
            assertTrue(values2.contains(30));
        }

        @Test
        void givenDuplicateValues_whenGettingSlotValues_thenReturnsUniqueValues() {
            // Given
            trace.addIntValue(0, 10);
            trace.addIntValue(0, 10);
            trace.addIntValue(0, 10);
            identifierMapping.put(0, createArgumentIdentifier(0, "int"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(1, values.size());
            assertTrue(values.contains(10));
        }

        @Test
        void givenMixedIdentifierTypes_whenGettingSlots_thenReturnsAllSlots() {
            // Given
            identifierMapping.put(0, createArgumentIdentifier(0, "int"));
            identifierMapping.put(1, createFieldIdentifier("field1", "long"));
            identifierMapping.put(2, createReturnValueIdentifier("testMethod", "boolean"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<Integer> slots = mapper.getSlots();

            // Then
            assertEquals(3, slots.size());
            assertTrue(slots.contains(0));
            assertTrue(slots.contains(1));
            assertTrue(slots.contains(2));
        }

        @Test
        void givenLargeNumberOfValues_whenGettingSlotValues_thenReturnsAllValues() {
            // Given
            for (int i = 0; i < 1000; i++) {
                trace.addIntValue(0, i);
            }
            identifierMapping.put(0, createArgumentIdentifier(0, "int"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then
            assertEquals(1000, values.size());
        }
    }

    @Nested
    class D_ErrorHandling {

        @Test
        void givenInvalidSlot_whenGettingExportableValue_thenReturnsNull() {
            // Given
            identifierMapping.put(0, createArgumentIdentifier(0, "int"));
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            JavaValueIdentifier result = mapper.getExportableValue(999);

            // Then
            assertNull(result);
        }

        @Test
        void givenObjectType_whenGettingSlotValues_thenReturnsObjectValues() {
            // Given - use a custom object type that's not a primitive
            JavaArgumentIdentifier objectIdentifier = new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                    .argumentSlot(0)
                    .variableType("java.util.List")
                    .build()
            );
            identifierMapping.put(0, objectIdentifier);
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            Set<?> values = mapper.getSlotValues(0);

            // Then - should return empty set from object values (no objects added to trace)
            assertNotNull(values);
            assertTrue(values.isEmpty());
        }

        @Test
        void givenNullIdentifierInMapping_whenGettingExportableValue_thenReturnsNull() {
            // Given
            identifierMapping.put(0, null);
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            JavaValueIdentifier result = mapper.getExportableValue(0);

            // Then
            assertNull(result);
        }
    }

    @Nested
    class E_IdentifierTypeIntegration {

        @Test
        void givenArgumentIdentifier_whenGettingExportableValue_thenReturnsArgumentIdentifier() {
            // Given
            JavaArgumentIdentifier argIdentifier = createArgumentIdentifier(0, "int");
            identifierMapping.put(0, argIdentifier);
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            JavaValueIdentifier result = mapper.getExportableValue(0);

            // Then
            assertNotNull(result);
            assertInstanceOf(JavaArgumentIdentifier.class, result);
            JavaArgumentIdentifier argResult = (JavaArgumentIdentifier) result;
            assertEquals(0, argResult.getArgumentSlot());
            assertEquals("int", argResult.getType());
        }

        @Test
        void givenFieldIdentifier_whenGettingExportableValue_thenReturnsFieldIdentifier() {
            // Given
            JavaFieldIdentifier fieldIdentifier = createFieldIdentifier("testField", "long");
            identifierMapping.put(0, fieldIdentifier);
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            JavaValueIdentifier result = mapper.getExportableValue(0);

            // Then
            assertNotNull(result);
            assertInstanceOf(JavaFieldIdentifier.class, result);
            JavaFieldIdentifier fieldResult = (JavaFieldIdentifier) result;
            assertEquals("testField", fieldResult.getFieldName());
            assertEquals("long", fieldResult.getType());
        }

        @Test
        void givenReturnValueIdentifier_whenGettingExportableValue_thenReturnsReturnValueIdentifier() {
            // Given
            JavaReturnValueIdentifier returnIdentifier = createReturnValueIdentifier("calculate", "double");
            identifierMapping.put(0, returnIdentifier);
            mapper = new TraceIdentifierMapper(trace, identifierMapping);

            // When
            JavaValueIdentifier result = mapper.getExportableValue(0);

            // Then
            assertNotNull(result);
            assertInstanceOf(JavaReturnValueIdentifier.class, result);
            JavaReturnValueIdentifier returnResult = (JavaReturnValueIdentifier) result;
            assertEquals("double", returnResult.getType());
            assertEquals("return_calculate", returnResult.getName());
        }
    }
}
