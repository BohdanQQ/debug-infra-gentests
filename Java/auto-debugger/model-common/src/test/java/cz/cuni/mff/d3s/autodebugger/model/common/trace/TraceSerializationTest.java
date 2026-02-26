package cz.cuni.mff.d3s.autodebugger.model.common.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Trace serialization and deserialization.
 */
class TraceSerializationTest {
    
    @TempDir
    Path tempDir;
    
    @Test
    void givenTraceWithIntValues_whenSerializedAndDeserialized_thenValuesPreserved() throws Exception {
        // Given
        Trace original = new Trace();
        original.addIntValue(0, 42);
        original.addIntValue(0, 100);
        original.addIntValue(1, 200);
        
        Path traceFile = tempDir.resolve("trace.ser");
        
        // When - serialize
        try (FileOutputStream fos = new FileOutputStream(traceFile.toFile());
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(original);
        }
        
        // When - deserialize
        Trace deserialized;
        try (FileInputStream fis = new FileInputStream(traceFile.toFile());
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            deserialized = (Trace) ois.readObject();
        }
        
        // Then
        assertNotNull(deserialized);
        Set<Integer> slot0Values = deserialized.getIntValues(0);
        Set<Integer> slot1Values = deserialized.getIntValues(1);
        
        assertNotNull(slot0Values);
        assertTrue(slot0Values.contains(42));
        assertTrue(slot0Values.contains(100));
        assertEquals(2, slot0Values.size());
        
        assertNotNull(slot1Values);
        assertTrue(slot1Values.contains(200));
        assertEquals(1, slot1Values.size());
    }
    
    @Test
    void givenTraceWithAllPrimitiveTypes_whenSerializedAndDeserialized_thenAllValuesPreserved() throws Exception {
        // Given
        Trace original = new Trace();
        original.addByteValue(0, (byte) 1);
        original.addCharValue(0, 'A');
        original.addShortValue(0, (short) 100);
        original.addIntValue(0, 1000);
        original.addLongValue(0, 10000L);
        original.addFloatValue(0, 1.5f);
        original.addDoubleValue(0, 2.5);
        original.addBooleanValue(0, true);
        original.addStringValue(0, "test");

        Path traceFile = tempDir.resolve("trace.ser");

        // When - serialize and deserialize
        try (FileOutputStream fos = new FileOutputStream(traceFile.toFile());
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(original);
        }

        Trace deserialized;
        try (FileInputStream fis = new FileInputStream(traceFile.toFile());
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            deserialized = (Trace) ois.readObject();
        }

        // Then
        assertNotNull(deserialized);
        assertTrue(deserialized.getByteValues(0).contains((byte) 1));
        assertTrue(deserialized.getCharValues(0).contains('A'));
        assertTrue(deserialized.getShortValues(0).contains((short) 100));
        assertTrue(deserialized.getIntValues(0).contains(1000));
        assertTrue(deserialized.getLongValues(0).contains(10000L));
        assertTrue(deserialized.getFloatValues(0).contains(1.5f));
        assertTrue(deserialized.getDoubleValues(0).contains(2.5));
        assertTrue(deserialized.getBooleanValues(0).contains(true));
        assertTrue(deserialized.getStringValues(0).contains("test"));
    }
    
    @Test
    void givenEmptyTrace_whenSerializedAndDeserialized_thenRemainsEmpty() throws Exception {
        // Given
        Trace original = new Trace();
        Path traceFile = tempDir.resolve("trace.ser");

        // When - serialize and deserialize
        try (FileOutputStream fos = new FileOutputStream(traceFile.toFile());
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(original);
        }

        Trace deserialized;
        try (FileInputStream fis = new FileInputStream(traceFile.toFile());
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            deserialized = (Trace) ois.readObject();
        }

        // Then
        assertNotNull(deserialized);
        // Empty trace should have no values for any slot
        assertTrue(deserialized.getIntValues(0).isEmpty());
        assertTrue(deserialized.getIntValues(1).isEmpty());
    }

    @Test
    void givenTraceWithSpecialFloatValues_whenSerializedAndDeserialized_thenPreservesSpecialValues() throws Exception {
        // given - trace with special float values
        Trace original = new Trace();
        original.addFloatValue(1, Float.NaN);
        original.addFloatValue(1, Float.POSITIVE_INFINITY);
        original.addFloatValue(1, Float.NEGATIVE_INFINITY);
        original.addFloatValue(1, -0.0f);
        original.addFloatValue(1, Float.MAX_VALUE);
        original.addFloatValue(1, Float.MIN_VALUE);

        // when - serialize and deserialize
        Path traceFile = tempDir.resolve("special-floats.ser");
        try (var oos = new ObjectOutputStream(Files.newOutputStream(traceFile))) {
            oos.writeObject(original);
        }

        Trace deserialized;
        try (var ois = new ObjectInputStream(Files.newInputStream(traceFile))) {
            deserialized = (Trace) ois.readObject();
        }

        // then - special values are preserved
        Set<Float> values = deserialized.getFloatValues(1);
        assertEquals(6, values.size());
        assertTrue(values.stream().anyMatch(f -> Float.isNaN(f)), "Should contain NaN");
        assertTrue(values.contains(Float.POSITIVE_INFINITY), "Should contain +Infinity");
        assertTrue(values.contains(Float.NEGATIVE_INFINITY), "Should contain -Infinity");
        assertTrue(values.contains(Float.MAX_VALUE));
        assertTrue(values.contains(Float.MIN_VALUE));
    }

    @Test
    void givenTraceWithSpecialDoubleValues_whenSerializedAndDeserialized_thenPreservesSpecialValues() throws Exception {
        // given - trace with special double values
        Trace original = new Trace();
        original.addDoubleValue(1, Double.NaN);
        original.addDoubleValue(1, Double.POSITIVE_INFINITY);
        original.addDoubleValue(1, Double.NEGATIVE_INFINITY);
        original.addDoubleValue(1, -0.0d);
        original.addDoubleValue(1, Double.MAX_VALUE);
        original.addDoubleValue(1, Double.MIN_VALUE);

        // when - serialize and deserialize
        Path traceFile = tempDir.resolve("special-doubles.ser");
        try (var oos = new ObjectOutputStream(Files.newOutputStream(traceFile))) {
            oos.writeObject(original);
        }

        Trace deserialized;
        try (var ois = new ObjectInputStream(Files.newInputStream(traceFile))) {
            deserialized = (Trace) ois.readObject();
        }

        // then - special values are preserved
        Set<Double> values = deserialized.getDoubleValues(1);
        assertEquals(6, values.size());
        assertTrue(values.stream().anyMatch(d -> Double.isNaN(d)), "Should contain NaN");
        assertTrue(values.contains(Double.POSITIVE_INFINITY));
        assertTrue(values.contains(Double.NEGATIVE_INFINITY));
    }

    @Test
    void givenTraceWithBoundaryIntValues_whenSerializedAndDeserialized_thenPreservesBoundaryValues() throws Exception {
        // given - trace with boundary integer values
        Trace original = new Trace();
        original.addIntValue(1, Integer.MIN_VALUE);
        original.addIntValue(1, Integer.MAX_VALUE);
        original.addIntValue(1, 0);
        original.addIntValue(1, -1);
        original.addIntValue(1, 1);

        original.addLongValue(2, Long.MIN_VALUE);
        original.addLongValue(2, Long.MAX_VALUE);
        original.addLongValue(2, 0L);

        original.addShortValue(3, Short.MIN_VALUE);
        original.addShortValue(3, Short.MAX_VALUE);

        original.addByteValue(4, Byte.MIN_VALUE);
        original.addByteValue(4, Byte.MAX_VALUE);

        // when - serialize and deserialize
        Path traceFile = tempDir.resolve("boundary-ints.ser");
        try (var oos = new ObjectOutputStream(Files.newOutputStream(traceFile))) {
            oos.writeObject(original);
        }

        Trace deserialized;
        try (var ois = new ObjectInputStream(Files.newInputStream(traceFile))) {
            deserialized = (Trace) ois.readObject();
        }

        // then - boundary values are preserved
        assertTrue(deserialized.getIntValues(1).contains(Integer.MIN_VALUE));
        assertTrue(deserialized.getIntValues(1).contains(Integer.MAX_VALUE));
        assertEquals(5, deserialized.getIntValues(1).size());

        assertTrue(deserialized.getLongValues(2).contains(Long.MIN_VALUE));
        assertTrue(deserialized.getLongValues(2).contains(Long.MAX_VALUE));
        assertEquals(3, deserialized.getLongValues(2).size());

        assertTrue(deserialized.getShortValues(3).contains(Short.MIN_VALUE));
        assertTrue(deserialized.getShortValues(3).contains(Short.MAX_VALUE));

        assertTrue(deserialized.getByteValues(4).contains(Byte.MIN_VALUE));
        assertTrue(deserialized.getByteValues(4).contains(Byte.MAX_VALUE));
    }

    @Test
    void givenTraceWithSpecialCharValues_whenSerializedAndDeserialized_thenPreservesSpecialChars() throws Exception {
        // given - trace with special character values
        Trace original = new Trace();
        original.addCharValue(1, '\0');      // null char (same as Character.MIN_VALUE)
        original.addCharValue(1, '\n');      // newline
        original.addCharValue(1, '\t');      // tab
        original.addCharValue(1, '\r');      // carriage return
        original.addCharValue(1, '\\');      // backslash
        original.addCharValue(1, '"');       // quote
        original.addCharValue(1, Character.MAX_VALUE);

        // when - serialize and deserialize
        Path traceFile = tempDir.resolve("special-chars.ser");
        try (var oos = new ObjectOutputStream(Files.newOutputStream(traceFile))) {
            oos.writeObject(original);
        }

        Trace deserialized;
        try (var ois = new ObjectInputStream(Files.newInputStream(traceFile))) {
            deserialized = (Trace) ois.readObject();
        }

        // then - special chars are preserved
        Set<Character> values = deserialized.getCharValues(1);
        assertEquals(7, values.size());
        assertTrue(values.contains('\0'));
        assertTrue(values.contains('\n'));
        assertTrue(values.contains('\t'));
        assertTrue(values.contains(Character.MAX_VALUE));
    }

    @Test
    void givenVeryLargeTrace_whenSerializedAndDeserialized_thenCompletesSuccessfully() throws Exception {
        // given - trace with many values across multiple slots
        Trace original = new Trace();
        int numSlots = 100;
        int valuesPerSlot = 100;

        for (int slot = 0; slot < numSlots; slot++) {
            for (int i = 0; i < valuesPerSlot; i++) {
                original.addIntValue(slot, slot * 1000 + i);
                original.addLongValue(slot, (long) slot * 1000000L + i);
            }
        }

        // when - serialize and deserialize
        Path traceFile = tempDir.resolve("large-trace.ser");
        long startTime = System.currentTimeMillis();

        try (var oos = new ObjectOutputStream(Files.newOutputStream(traceFile))) {
            oos.writeObject(original);
        }

        Trace deserialized;
        try (var ois = new ObjectInputStream(Files.newInputStream(traceFile))) {
            deserialized = (Trace) ois.readObject();
        }

        long duration = System.currentTimeMillis() - startTime;

        // then - all values preserved and completes in reasonable time
        for (int slot = 0; slot < numSlots; slot++) {
            assertEquals(valuesPerSlot, deserialized.getIntValues(slot).size(),
                "Slot " + slot + " should have correct int count");
            assertEquals(valuesPerSlot, deserialized.getLongValues(slot).size(),
                "Slot " + slot + " should have correct long count");
        }

        assertTrue(duration < 5000, "Serialization/deserialization should complete within 5 seconds");
    }

    @Test
    void givenTraceWithDuplicateValuesAcrossSlots_whenQueried_thenReturnsCorrectSets() {
        // given - trace with same values in different slots
        Trace trace = new Trace();
        trace.addIntValue(1, 42);
        trace.addIntValue(2, 42);  // Same value, different slot
        trace.addIntValue(1, 42);  // Duplicate in same slot (should be deduplicated by Set)
        trace.addIntValue(1, 100);

        // when/then - each slot has independent set
        assertEquals(2, trace.getIntValues(1).size());  // 42 and 100
        assertEquals(1, trace.getIntValues(2).size());  // just 42
        assertTrue(trace.getIntValues(1).contains(42));
        assertTrue(trace.getIntValues(2).contains(42));
        assertTrue(trace.getIntValues(1).contains(100));
        assertFalse(trace.getIntValues(2).contains(100));
    }

    @Test
    void givenTraceWithData_whenQueryingEmptySlot_thenReturnsEmptySet() {
        // given - trace with data in slot 1
        Trace trace = new Trace();
        trace.addIntValue(1, 42);

        // when - query slot that has no data
        Set<Integer> emptySlotValues = trace.getIntValues(999);

        // then - returns empty set, not null
        assertNotNull(emptySlotValues);
        assertTrue(emptySlotValues.isEmpty());
    }
}

