package cz.cuni.mff.d3s.autodebugger.model.common.trace;

import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Map;
import java.util.SortedMap;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TemporalTrace serialization and deserialization.
 * Verifies that all trace data, metadata, and internal state are preserved
 * through round-trip serialization.
 */
class TemporalTraceSerializationTest {

    private ExportableValue argIdentifier;
    private ExportableValue fieldIdentifier;
    private ExportableValue returnIdentifier;

    /**
     * Simple test implementation of ExportableValue for testing purposes.
     */
    private static class TestExportableValue implements ExportableValue {
        private final int internalId;
        private final String name;

        public TestExportableValue(int internalId, String name) {
            this.internalId = internalId;
            this.name = name;
        }

        @Override
        public int getInternalId() {
            return internalId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestExportableValue that = (TestExportableValue) o;
            return internalId == that.internalId && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(internalId, name);
        }

        @Override
        public String toString() {
            return "TestExportableValue{id=" + internalId + ", name='" + name + "'}";
        }
    }

    @BeforeEach
    void setUp() {
        // Create test identifiers
        argIdentifier = new TestExportableValue(1, "arg0");
        fieldIdentifier = new TestExportableValue(2, "testField");
        returnIdentifier = new TestExportableValue(3, "returnValue");
    }

    /**
     * Helper method to perform round-trip serialization.
     */
    private TemporalTrace serializeAndDeserialize(TemporalTrace trace) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(trace);
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        try (ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (TemporalTrace) ois.readObject();
        }
    }

    @Nested
    class BasicRoundTrip {

        @Test
        void givenEmptyTrace_whenSerializedAndDeserialized_thenRemainsEmpty() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then
            assertNotNull(deserialized);
            assertEquals(0, deserialized.getTrackedVariableCount());
            assertEquals(0, deserialized.getTotalEventCount());
            assertFalse(deserialized.getEventIndexRange().isPresent());
        }

        @Test
        void givenSingleValue_whenSerializedAndDeserialized_thenValuePreserved() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addValue(argIdentifier, 0, 42);

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then
            assertNotNull(deserialized);
            assertEquals(1, deserialized.getTrackedVariableCount());
            assertEquals(1, deserialized.getTotalEventCount());
            
            SortedMap<Integer, Object> values = deserialized.getValues(argIdentifier);
            assertEquals(1, values.size());
            assertEquals(42, values.get(0));
        }

        @Test
        void givenMultipleValuesAtDifferentEventIndices_whenSerializedAndDeserialized_thenAllValuesPreserved() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addValue(argIdentifier, 0, 10);
            original.addValue(argIdentifier, 5, 20);
            original.addValue(argIdentifier, 10, 30);

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then
            SortedMap<Integer, Object> values = deserialized.getValues(argIdentifier);
            assertEquals(3, values.size());
            assertEquals(10, values.get(0));
            assertEquals(20, values.get(5));
            assertEquals(30, values.get(10));
        }

        @Test
        void givenMultipleIdentifiersWithSeparateHistories_whenSerializedAndDeserialized_thenAllHistoriesPreserved() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addValue(argIdentifier, 0, 100);
            original.addValue(argIdentifier, 5, 200);
            original.addValue(fieldIdentifier, 2, "hello");
            original.addValue(fieldIdentifier, 7, "world");
            original.addValue(returnIdentifier, 10, 42);

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then
            assertEquals(3, deserialized.getTrackedVariableCount());
            assertEquals(5, deserialized.getTotalEventCount());

            SortedMap<Integer, Object> argValues = deserialized.getValues(argIdentifier);
            assertEquals(2, argValues.size());
            assertEquals(100, argValues.get(0));
            assertEquals(200, argValues.get(5));

            SortedMap<Integer, Object> fieldValues = deserialized.getValues(fieldIdentifier);
            assertEquals(2, fieldValues.size());
            assertEquals("hello", fieldValues.get(2));
            assertEquals("world", fieldValues.get(7));

            SortedMap<Integer, Object> returnValues = deserialized.getValues(returnIdentifier);
            assertEquals(1, returnValues.size());
            assertEquals(42, returnValues.get(10));
        }
    }

    @Nested
    class MetadataSerialization {

        @Test
        void givenEmptyMetadata_whenSerializedAndDeserialized_thenMetadataPreserved() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addValue(argIdentifier, 0, 42);

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then
            Map<String, Object> metadata = deserialized.getAllMetadata();
            assertNotNull(metadata);
            assertTrue(metadata.isEmpty());
        }

        @Test
        void givenStringMetadata_whenSerializedAndDeserialized_thenMetadataPreserved() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addMetadata("testName", "MyTest");
            original.addMetadata("description", "Test description");
            original.addValue(argIdentifier, 0, 42);

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then
            assertEquals("MyTest", deserialized.getMetadata("testName"));
            assertEquals("Test description", deserialized.getMetadata("description"));
            assertEquals(2, deserialized.getAllMetadata().size());
        }

        @Test
        void givenMixedMetadataTypes_whenSerializedAndDeserialized_thenAllMetadataPreserved() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addMetadata("stringKey", "value");
            original.addMetadata("intKey", 123);
            original.addMetadata("boolKey", true);
            original.addMetadata("longKey", 999L);
            original.addValue(argIdentifier, 0, 42);

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then
            assertEquals("value", deserialized.getMetadata("stringKey"));
            assertEquals(123, deserialized.getMetadata("intKey"));
            assertEquals(true, deserialized.getMetadata("boolKey"));
            assertEquals(999L, deserialized.getMetadata("longKey"));
            assertEquals(4, deserialized.getAllMetadata().size());
        }
    }

    @Nested
    class EventIndexPreservation {

        @Test
        void givenNextEventIndex_whenSerializedAndDeserialized_thenNextEventIndexPreserved() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addValue(argIdentifier, 100);  // Auto-generated index 0
            original.addValue(argIdentifier, 200);  // Auto-generated index 1
            original.addValue(argIdentifier, 300);  // Auto-generated index 2

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then - next auto-generated index should be 3
            int nextIndex = deserialized.addValue(fieldIdentifier, "test");
            assertEquals(3, nextIndex);
        }

        @Test
        void givenExplicitEventIndices_whenSerializedAndDeserialized_thenIndicesPreserved() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addValue(argIdentifier, 100, "value1");
            original.addValue(argIdentifier, 200, "value2");
            original.addValue(argIdentifier, 300, "value3");

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then
            SortedMap<Integer, Object> values = deserialized.getValues(argIdentifier);
            assertTrue(values.containsKey(100));
            assertTrue(values.containsKey(200));
            assertTrue(values.containsKey(300));
            assertEquals("value1", values.get(100));
            assertEquals("value2", values.get(200));
            assertEquals("value3", values.get(300));
        }

        @Test
        void givenGapsInEventIndices_whenSerializedAndDeserialized_thenGapsPreserved() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addValue(argIdentifier, 0, "first");
            original.addValue(argIdentifier, 10, "second");  // Gap of 10
            original.addValue(argIdentifier, 100, "third");  // Gap of 90

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then
            SortedMap<Integer, Object> values = deserialized.getValues(argIdentifier);
            assertEquals(3, values.size());
            assertEquals("first", values.get(0));
            assertEquals("second", values.get(10));
            assertEquals("third", values.get(100));

            // Verify gaps exist (no values at intermediate indices)
            assertFalse(values.containsKey(5));
            assertFalse(values.containsKey(50));
        }
    }

    @Nested
    class NavigableMapBehavior {

        @Test
        void givenTreeMapOrdering_whenSerializedAndDeserialized_thenOrderingPreserved() throws Exception {
            // Given - add values in non-sequential order
            TemporalTrace original = new TemporalTrace();
            original.addValue(argIdentifier, 50, "middle");
            original.addValue(argIdentifier, 10, "first");
            original.addValue(argIdentifier, 100, "last");
            original.addValue(argIdentifier, 25, "second");

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then - values should be in sorted order
            SortedMap<Integer, Object> values = deserialized.getValues(argIdentifier);
            java.util.List<Integer> keys = new java.util.ArrayList<>(values.keySet());
            assertEquals(java.util.List.of(10, 25, 50, 100), keys);
        }

        @Test
        void givenNavigableMapOperations_whenPerformedOnDeserializedTrace_thenOperationsWorkCorrectly() throws Exception {
            // Given
            TemporalTrace original = new TemporalTrace();
            original.addValue(argIdentifier, 10, "value10");
            original.addValue(argIdentifier, 20, "value20");
            original.addValue(argIdentifier, 30, "value30");
            original.addValue(argIdentifier, 50, "value50");

            // When
            TemporalTrace deserialized = serializeAndDeserialize(original);

            // Then - test floorEntry behavior (getLatestValueBefore uses this)
            assertEquals(java.util.Optional.of("value20"),
                deserialized.getLatestValueBefore(argIdentifier, 25));
            assertEquals(java.util.Optional.of("value30"),
                deserialized.getLatestValueBefore(argIdentifier, 30));
            assertEquals(java.util.Optional.of("value30"),
                deserialized.getLatestValueBefore(argIdentifier, 40));
            assertEquals(java.util.Optional.empty(),
                deserialized.getLatestValueBefore(argIdentifier, 5));
        }
    }
}

