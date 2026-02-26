package cz.cuni.mff.d3s.autodebugger.model.java;

import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.IndexedTrace;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.TemporalTrace;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.*;

class TraceAdapterTest {

    private Map<Integer, JavaValueIdentifier> identifierMapping;
    private JavaArgumentIdentifier arg0Identifier;
    private JavaArgumentIdentifier arg1Identifier;
    private JavaFieldIdentifier fieldIdentifier;

    @BeforeEach
    void setUp() {
        identifierMapping = new HashMap<>();

        // Create test identifiers
        arg0Identifier = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(0)
                .variableType("int")
                .build()
        );

        arg1Identifier = new JavaArgumentIdentifier(
            ArgumentIdentifierParameters.builder()
                .argumentSlot(1)
                .variableType("int")
                .build()
        );

        JavaClassIdentifier testClass = new JavaClassIdentifier(
            ClassIdentifierParameters.builder()
                .className("TestClass")
                .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                .build()
        );

        fieldIdentifier = new JavaFieldIdentifier(
            FieldIdentifierParameters.builder()
                .variableName("testField")
                .ownerClassIdentifier(testClass)
                .variableType("int")
                .build()
        );

        // Set up identifier mapping
        identifierMapping.put(0, arg0Identifier);
        identifierMapping.put(1, arg1Identifier);
        identifierMapping.put(2, fieldIdentifier);
    }

    @Test
    void givenIndexedTrace_whenConvertFromIndexed_thenPreservesTrueEventIndices() {
        // given - Create an IndexedTrace with specific event indices (non-sequential)
        IndexedTrace indexedTrace = new IndexedTrace();
        indexedTrace.addValue(0, 0, 100);   // slot 0, event 0
        indexedTrace.addValue(0, 5, 200);   // slot 0, event 5
        indexedTrace.addValue(0, 10, 300);  // slot 0, event 10

        // Add identifier mapping for slot 0
        Map<Integer, JavaValueIdentifier> mapping = new HashMap<>();
        mapping.put(0, arg0Identifier);

        // when - Convert using convertFromIndexed
        TemporalTrace temporalTrace = TraceAdapter.convertFromIndexed(indexedTrace, mapping);

        // then - Verify the TemporalTrace has values at the EXACT event indices from the IndexedTrace
        assertNotNull(temporalTrace);
        SortedMap<Integer, Object> values = temporalTrace.getValues(arg0Identifier);
        assertEquals(3, values.size(), "Should have 3 values");
        assertEquals(100, values.get(0), "Value at event 0 should be 100");
        assertEquals(200, values.get(5), "Value at event 5 should be 200");
        assertEquals(300, values.get(10), "Value at event 10 should be 300");

        // Verify metadata
        assertEquals("indexed_trace", temporalTrace.getMetadata("converted_from"));
        assertEquals(true, temporalTrace.getMetadata("preserves_true_event_indices"));
    }

    @Test
    void givenIndexedTraceWithMultipleSlots_whenConvertFromIndexed_thenAllSlotsConverted() {
        // given - Create IndexedTrace with values in 3 different slots
        IndexedTrace indexedTrace = new IndexedTrace();
        indexedTrace.addValue(0, 0, 10);    // slot 0 (arg0)
        indexedTrace.addValue(0, 5, 20);
        indexedTrace.addValue(1, 2, 30);    // slot 1 (arg1)
        indexedTrace.addValue(1, 7, 40);
        indexedTrace.addValue(2, 3, 50);    // slot 2 (field)
        indexedTrace.addValue(2, 8, 60);

        // when - Convert and verify all slots are represented in TemporalTrace
        TemporalTrace temporalTrace = TraceAdapter.convertFromIndexed(indexedTrace, identifierMapping);

        // then - Verify all three slots were converted
        assertNotNull(temporalTrace);
        assertEquals(3, temporalTrace.getTrackedVariableCount(), "Should track 3 variables");

        // Verify slot 0 (arg0)
        SortedMap<Integer, Object> arg0Values = temporalTrace.getValues(arg0Identifier);
        assertEquals(2, arg0Values.size());
        assertEquals(10, arg0Values.get(0));
        assertEquals(20, arg0Values.get(5));

        // Verify slot 1 (arg1)
        SortedMap<Integer, Object> arg1Values = temporalTrace.getValues(arg1Identifier);
        assertEquals(2, arg1Values.size());
        assertEquals(30, arg1Values.get(2));
        assertEquals(40, arg1Values.get(7));

        // Verify slot 2 (field)
        SortedMap<Integer, Object> fieldValues = temporalTrace.getValues(fieldIdentifier);
        assertEquals(2, fieldValues.size());
        assertEquals(50, fieldValues.get(3));
        assertEquals(60, fieldValues.get(8));
    }

    @Test
    void givenIndexedTraceWithUnmappedSlot_whenConvertFromIndexed_thenSkipsUnmappedSlot() {
        // given - Create IndexedTrace with a slot that has no identifier mapping
        IndexedTrace indexedTrace = new IndexedTrace();
        indexedTrace.addValue(0, 0, 100);   // slot 0 - mapped
        indexedTrace.addValue(99, 5, 999);  // slot 99 - NOT mapped

        // Create mapping with only slot 0
        Map<Integer, JavaValueIdentifier> partialMapping = new HashMap<>();
        partialMapping.put(0, arg0Identifier);

        // when - Verify conversion succeeds and the unmapped slot is skipped
        TemporalTrace temporalTrace = TraceAdapter.convertFromIndexed(indexedTrace, partialMapping);

        // then
        assertNotNull(temporalTrace);
        assertEquals(1, temporalTrace.getTrackedVariableCount(), "Should only track mapped slot");
        SortedMap<Integer, Object> values = temporalTrace.getValues(arg0Identifier);
        assertEquals(1, values.size());
        assertEquals(100, values.get(0));

        // Verify metadata shows skipped slot
        assertEquals(1, temporalTrace.getMetadata("skipped_slot_count"));
    }

    @Test
    void givenEmptyIndexedTrace_whenConvertFromIndexed_thenReturnsEmptyTemporalTrace() {
        // given - Convert an empty IndexedTrace
        IndexedTrace emptyTrace = new IndexedTrace();

        // when - Verify result is an empty TemporalTrace
        TemporalTrace temporalTrace = TraceAdapter.convertFromIndexed(emptyTrace, identifierMapping);

        // then
        assertNotNull(temporalTrace);
        assertEquals(0, temporalTrace.getTrackedVariableCount(), "Should have no tracked variables");
        assertEquals(0, temporalTrace.getTotalEventCount(), "Should have no events");

        // Verify metadata
        assertEquals("indexed_trace", temporalTrace.getMetadata("converted_from"));
        assertEquals(0, temporalTrace.getMetadata("original_slot_count"));
    }
}

