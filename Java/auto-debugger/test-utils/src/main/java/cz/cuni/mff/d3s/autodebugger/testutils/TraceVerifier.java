package cz.cuni.mff.d3s.autodebugger.testutils;

import cz.cuni.mff.d3s.autodebugger.model.common.trace.IndexedTrace;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Utility class providing assertion methods for verifying trace content in tests.
 * Supports both Trace (naive mode) and IndexedTrace (temporal mode).
 */
public class TraceVerifier {

    // ========== Trace (Naive Mode) Assertions ==========

    /**
     * Asserts that a slot in the trace is not empty (contains at least one value).
     */
    public static void assertSlotNotEmpty(Trace trace, int slotId) {
        boolean hasValues = !trace.getIntValues(slotId).isEmpty() ||
                           !trace.getLongValues(slotId).isEmpty() ||
                           !trace.getDoubleValues(slotId).isEmpty() ||
                           !trace.getFloatValues(slotId).isEmpty() ||
                           !trace.getBooleanValues(slotId).isEmpty() ||
                           !trace.getCharValues(slotId).isEmpty() ||
                           !trace.getShortValues(slotId).isEmpty() ||
                           !trace.getByteValues(slotId).isEmpty();
        
        assertTrue(hasValues, 
            String.format("Expected slot %d to contain values, but it was empty. %s", 
                slotId, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains at least the specified minimum number of values.
     */
    public static void assertSlotContainsAtLeast(Trace trace, int slotId, int minCount) {
        int totalCount = trace.getIntValues(slotId).size() +
                        trace.getLongValues(slotId).size() +
                        trace.getDoubleValues(slotId).size() +
                        trace.getFloatValues(slotId).size() +
                        trace.getBooleanValues(slotId).size() +
                        trace.getCharValues(slotId).size() +
                        trace.getShortValues(slotId).size() +
                        trace.getByteValues(slotId).size();
        
        assertTrue(totalCount >= minCount,
            String.format("Expected slot %d to contain at least %d values, but found %d. %s",
                slotId, minCount, totalCount, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified int values (subset check).
     */
    public static void assertSlotContainsIntValues(Trace trace, int slotId, int... expected) {
        Set<Integer> actual = trace.getIntValues(slotId);
        Set<Integer> expectedSet = Arrays.stream(expected).boxed().collect(Collectors.toSet());
        
        assertTrue(actual.containsAll(expectedSet),
            String.format("Expected slot %d to contain int values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains exactly the specified int values (exact match).
     */
    public static void assertSlotContainsExactlyIntValues(Trace trace, int slotId, int... expected) {
        Set<Integer> actual = trace.getIntValues(slotId);
        Set<Integer> expectedSet = Arrays.stream(expected).boxed().collect(Collectors.toSet());
        
        assertEquals(expectedSet, actual,
            String.format("Expected slot %d to contain exactly int values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified long values (subset check).
     */
    public static void assertSlotContainsLongValues(Trace trace, int slotId, long... expected) {
        Set<Long> actual = trace.getLongValues(slotId);
        Set<Long> expectedSet = Arrays.stream(expected).boxed().collect(Collectors.toSet());
        
        assertTrue(actual.containsAll(expectedSet),
            String.format("Expected slot %d to contain long values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains exactly the specified long values (exact match).
     */
    public static void assertSlotContainsExactlyLongValues(Trace trace, int slotId, long... expected) {
        Set<Long> actual = trace.getLongValues(slotId);
        Set<Long> expectedSet = Arrays.stream(expected).boxed().collect(Collectors.toSet());
        
        assertEquals(expectedSet, actual,
            String.format("Expected slot %d to contain exactly long values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified double values (subset check).
     */
    public static void assertSlotContainsDoubleValues(Trace trace, int slotId, double... expected) {
        Set<Double> actual = trace.getDoubleValues(slotId);
        Set<Double> expectedSet = Arrays.stream(expected).boxed().collect(Collectors.toSet());
        
        assertTrue(actual.containsAll(expectedSet),
            String.format("Expected slot %d to contain double values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains exactly the specified double values (exact match).
     */
    public static void assertSlotContainsExactlyDoubleValues(Trace trace, int slotId, double... expected) {
        Set<Double> actual = trace.getDoubleValues(slotId);
        Set<Double> expectedSet = Arrays.stream(expected).boxed().collect(Collectors.toSet());
        
        assertEquals(expectedSet, actual,
            String.format("Expected slot %d to contain exactly double values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified float values (subset check).
     */
    public static void assertSlotContainsFloatValues(Trace trace, int slotId, float... expected) {
        Set<Float> actual = trace.getFloatValues(slotId);
        Set<Float> expectedSet = new HashSet<>();
        for (float f : expected) {
            expectedSet.add(f);
        }
        
        assertTrue(actual.containsAll(expectedSet),
            String.format("Expected slot %d to contain float values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains exactly the specified float values (exact match).
     */
    public static void assertSlotContainsExactlyFloatValues(Trace trace, int slotId, float... expected) {
        Set<Float> actual = trace.getFloatValues(slotId);
        Set<Float> expectedSet = new HashSet<>();
        for (float f : expected) {
            expectedSet.add(f);
        }

        assertEquals(expectedSet, actual,
            String.format("Expected slot %d to contain exactly float values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified boolean values (subset check).
     */
    public static void assertSlotContainsBooleanValues(Trace trace, int slotId, boolean... expected) {
        Set<Boolean> actual = trace.getBooleanValues(slotId);
        Set<Boolean> expectedSet = new HashSet<>();
        for (boolean b : expected) {
            expectedSet.add(b);
        }

        assertTrue(actual.containsAll(expectedSet),
            String.format("Expected slot %d to contain boolean values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains exactly the specified boolean values (exact match).
     */
    public static void assertSlotContainsExactlyBooleanValues(Trace trace, int slotId, boolean... expected) {
        Set<Boolean> actual = trace.getBooleanValues(slotId);
        Set<Boolean> expectedSet = new HashSet<>();
        for (boolean b : expected) {
            expectedSet.add(b);
        }

        assertEquals(expectedSet, actual,
            String.format("Expected slot %d to contain exactly boolean values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified char values (subset check).
     */
    public static void assertSlotContainsCharValues(Trace trace, int slotId, char... expected) {
        Set<Character> actual = trace.getCharValues(slotId);
        Set<Character> expectedSet = new HashSet<>();
        for (char c : expected) {
            expectedSet.add(c);
        }

        assertTrue(actual.containsAll(expectedSet),
            String.format("Expected slot %d to contain char values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains exactly the specified char values (exact match).
     */
    public static void assertSlotContainsExactlyCharValues(Trace trace, int slotId, char... expected) {
        Set<Character> actual = trace.getCharValues(slotId);
        Set<Character> expectedSet = new HashSet<>();
        for (char c : expected) {
            expectedSet.add(c);
        }

        assertEquals(expectedSet, actual,
            String.format("Expected slot %d to contain exactly char values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified short values (subset check).
     */
    public static void assertSlotContainsShortValues(Trace trace, int slotId, short... expected) {
        Set<Short> actual = trace.getShortValues(slotId);
        Set<Short> expectedSet = new HashSet<>();
        for (short s : expected) {
            expectedSet.add(s);
        }

        assertTrue(actual.containsAll(expectedSet),
            String.format("Expected slot %d to contain short values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains exactly the specified short values (exact match).
     */
    public static void assertSlotContainsExactlyShortValues(Trace trace, int slotId, short... expected) {
        Set<Short> actual = trace.getShortValues(slotId);
        Set<Short> expectedSet = new HashSet<>();
        for (short s : expected) {
            expectedSet.add(s);
        }

        assertEquals(expectedSet, actual,
            String.format("Expected slot %d to contain exactly short values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified byte values (subset check).
     */
    public static void assertSlotContainsByteValues(Trace trace, int slotId, byte... expected) {
        Set<Byte> actual = trace.getByteValues(slotId);
        Set<Byte> expectedSet = new HashSet<>();
        for (byte b : expected) {
            expectedSet.add(b);
        }

        assertTrue(actual.containsAll(expectedSet),
            String.format("Expected slot %d to contain byte values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains exactly the specified byte values (exact match).
     */
    public static void assertSlotContainsExactlyByteValues(Trace trace, int slotId, byte... expected) {
        Set<Byte> actual = trace.getByteValues(slotId);
        Set<Byte> expectedSet = new HashSet<>();
        for (byte b : expected) {
            expectedSet.add(b);
        }

        assertEquals(expectedSet, actual,
            String.format("Expected slot %d to contain exactly byte values %s, but found %s. %s",
                slotId, expectedSet, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Returns a debug summary of a slot's contents in a Trace.
     */
    public static String getSlotSummary(Trace trace, int slotId) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Slot %d summary: ", slotId));

        Set<Integer> intVals = trace.getIntValues(slotId);
        Set<Long> longVals = trace.getLongValues(slotId);
        Set<Double> doubleVals = trace.getDoubleValues(slotId);
        Set<Float> floatVals = trace.getFloatValues(slotId);
        Set<Boolean> boolVals = trace.getBooleanValues(slotId);
        Set<Character> charVals = trace.getCharValues(slotId);
        Set<Short> shortVals = trace.getShortValues(slotId);
        Set<Byte> byteVals = trace.getByteValues(slotId);

        if (!intVals.isEmpty()) sb.append(String.format("int=%s ", intVals));
        if (!longVals.isEmpty()) sb.append(String.format("long=%s ", longVals));
        if (!doubleVals.isEmpty()) sb.append(String.format("double=%s ", doubleVals));
        if (!floatVals.isEmpty()) sb.append(String.format("float=%s ", floatVals));
        if (!boolVals.isEmpty()) sb.append(String.format("boolean=%s ", boolVals));
        if (!charVals.isEmpty()) sb.append(String.format("char=%s ", charVals));
        if (!shortVals.isEmpty()) sb.append(String.format("short=%s ", shortVals));
        if (!byteVals.isEmpty()) sb.append(String.format("byte=%s ", byteVals));

        if (intVals.isEmpty() && longVals.isEmpty() && doubleVals.isEmpty() &&
            floatVals.isEmpty() && boolVals.isEmpty() && charVals.isEmpty() &&
            shortVals.isEmpty() && byteVals.isEmpty()) {
            sb.append("(empty)");
        }

        return sb.toString().trim();
    }

    // ========== IndexedTrace (Temporal Mode) Assertions ==========

    /**
     * Asserts that a slot in the indexed trace is not empty (contains at least one event).
     */
    public static void assertSlotNotEmpty(IndexedTrace trace, int slotId) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        assertFalse(values.isEmpty(),
            String.format("Expected slot %d to contain values, but it was empty. %s",
                slotId, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a specific event in a slot contains the expected value.
     */
    public static void assertEventContainsValue(IndexedTrace trace, int slotId, int eventIndex, Object expected) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        assertTrue(values.containsKey(eventIndex),
            String.format("Expected slot %d to have event at index %d, but it was not found. %s",
                slotId, eventIndex, getSlotSummary(trace, slotId)));

        Object actual = values.get(eventIndex);
        assertEquals(expected, actual,
            String.format("Expected slot %d at event %d to contain value %s, but found %s. %s",
                slotId, eventIndex, expected, actual, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a sequence of events starting at a specific index contains the expected values.
     */
    public static void assertEventSequence(IndexedTrace trace, int slotId, int startEventIndex, Object... expectedValues) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);

        for (int i = 0; i < expectedValues.length; i++) {
            int eventIndex = startEventIndex + i;
            assertTrue(values.containsKey(eventIndex),
                String.format("Expected slot %d to have event at index %d (sequence position %d), but it was not found. %s",
                    slotId, eventIndex, i, getSlotSummary(trace, slotId)));

            Object expected = expectedValues[i];
            Object actual = values.get(eventIndex);
            assertEquals(expected, actual,
                String.format("Expected slot %d at event %d (sequence position %d) to contain value %s, but found %s. %s",
                    slotId, eventIndex, i, expected, actual, getSlotSummary(trace, slotId)));
        }
    }

    /**
     * Asserts that a slot has at least the specified number of events.
     */
    public static void assertSlotHasAtLeastEvents(IndexedTrace trace, int slotId, int minEvents) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        int actualCount = values.size();

        assertTrue(actualCount >= minEvents,
            String.format("Expected slot %d to have at least %d events, but found %d. %s",
                slotId, minEvents, actualCount, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified int value at any event.
     */
    public static void assertSlotContainsIntValue(IndexedTrace trace, int slotId, int expected) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        boolean found = values.values().stream()
            .anyMatch(v -> v instanceof Integer && (Integer) v == expected);

        assertTrue(found,
            String.format("Expected slot %d to contain int value %d at any event, but it was not found. %s",
                slotId, expected, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified long value at any event.
     */
    public static void assertSlotContainsLongValue(IndexedTrace trace, int slotId, long expected) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        boolean found = values.values().stream()
            .anyMatch(v -> v instanceof Long && (Long) v == expected);

        assertTrue(found,
            String.format("Expected slot %d to contain long value %d at any event, but it was not found. %s",
                slotId, expected, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified double value at any event.
     */
    public static void assertSlotContainsDoubleValue(IndexedTrace trace, int slotId, double expected) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        boolean found = values.values().stream()
            .anyMatch(v -> v instanceof Double && (Double) v == expected);

        assertTrue(found,
            String.format("Expected slot %d to contain double value %f at any event, but it was not found. %s",
                slotId, expected, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified float value at any event.
     */
    public static void assertSlotContainsFloatValue(IndexedTrace trace, int slotId, float expected) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        boolean found = values.values().stream()
            .anyMatch(v -> v instanceof Float && (Float) v == expected);

        assertTrue(found,
            String.format("Expected slot %d to contain float value %f at any event, but it was not found. %s",
                slotId, expected, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified boolean value at any event.
     */
    public static void assertSlotContainsBooleanValue(IndexedTrace trace, int slotId, boolean expected) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        boolean found = values.values().stream()
            .anyMatch(v -> v instanceof Boolean && (Boolean) v == expected);

        assertTrue(found,
            String.format("Expected slot %d to contain boolean value %b at any event, but it was not found. %s",
                slotId, expected, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified char value at any event.
     */
    public static void assertSlotContainsCharValue(IndexedTrace trace, int slotId, char expected) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        boolean found = values.values().stream()
            .anyMatch(v -> v instanceof Character && (Character) v == expected);

        assertTrue(found,
            String.format("Expected slot %d to contain char value '%c' at any event, but it was not found. %s",
                slotId, expected, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified short value at any event.
     */
    public static void assertSlotContainsShortValue(IndexedTrace trace, int slotId, short expected) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        boolean found = values.values().stream()
            .anyMatch(v -> v instanceof Short && (Short) v == expected);

        assertTrue(found,
            String.format("Expected slot %d to contain short value %d at any event, but it was not found. %s",
                slotId, expected, getSlotSummary(trace, slotId)));
    }

    /**
     * Asserts that a slot contains the specified byte value at any event.
     */
    public static void assertSlotContainsByteValue(IndexedTrace trace, int slotId, byte expected) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);
        boolean found = values.values().stream()
            .anyMatch(v -> v instanceof Byte && (Byte) v == expected);

        assertTrue(found,
            String.format("Expected slot %d to contain byte value %d at any event, but it was not found. %s",
                slotId, expected, getSlotSummary(trace, slotId)));
    }

    /**
     * Returns a debug summary of a slot's contents in an IndexedTrace.
     */
    public static String getSlotSummary(IndexedTrace trace, int slotId) {
        NavigableMap<Integer, Object> values = trace.getValues(slotId);

        if (values.isEmpty()) {
            return String.format("Slot %d summary: (empty)", slotId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Slot %d summary: %d events [", slotId, values.size()));

        // Show first few events
        int count = 0;
        int maxToShow = 5;
        for (Map.Entry<Integer, Object> entry : values.entrySet()) {
            if (count > 0) sb.append(", ");
            if (count >= maxToShow) {
                sb.append("...");
                break;
            }
            sb.append(String.format("event[%d]=%s", entry.getKey(), entry.getValue()));
            count++;
        }
        sb.append("]");

        return sb.toString();
    }
}
