package cz.cuni.mff.d3s.autodebugger.model.common.trace;

import org.junit.jupiter.api.Test;

import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IndexedTrace class.
 * Tests the core functionality of storing and retrieving runtime values
 * indexed by slot ID and event index.
 */
class IndexedTraceTest {

    @Test
    void givenSingleValue_whenAdded_thenCanBeRetrieved() {
        // given
        IndexedTrace trace = new IndexedTrace();
        
        // when
        trace.addValue(1, 0, 42);
        
        // then
        NavigableMap<Integer, Object> values = trace.getValues(1);
        assertEquals(1, values.size());
        assertEquals(42, values.get(0));
    }

    @Test
    void givenMultipleValuesPerSlot_whenAdded_thenAllValuesRetrievable() {
        // given
        IndexedTrace trace = new IndexedTrace();
        
        // when - add multiple values with different event indices to same slot
        trace.addValue(1, 0, 100);
        trace.addValue(1, 5, 200);
        trace.addValue(1, 10, 300);
        
        // then
        NavigableMap<Integer, Object> values = trace.getValues(1);
        assertEquals(3, values.size());
        assertEquals(100, values.get(0));
        assertEquals(200, values.get(5));
        assertEquals(300, values.get(10));
    }

    @Test
    void givenMultipleSlots_whenAdded_thenSlotsAreIsolated() {
        // given
        IndexedTrace trace = new IndexedTrace();
        
        // when - add values to different slots
        trace.addValue(1, 0, "slot1-value1");
        trace.addValue(1, 5, "slot1-value2");
        trace.addValue(2, 0, "slot2-value1");
        trace.addValue(3, 10, 42);
        
        // then - each slot has independent values
        NavigableMap<Integer, Object> slot1Values = trace.getValues(1);
        NavigableMap<Integer, Object> slot2Values = trace.getValues(2);
        NavigableMap<Integer, Object> slot3Values = trace.getValues(3);
        
        assertEquals(2, slot1Values.size());
        assertEquals(1, slot2Values.size());
        assertEquals(1, slot3Values.size());
        
        assertEquals("slot1-value1", slot1Values.get(0));
        assertEquals("slot1-value2", slot1Values.get(5));
        assertEquals("slot2-value1", slot2Values.get(0));
        assertEquals(42, slot3Values.get(10));
    }

    @Test
    void givenMultipleEvents_whenGetEventIndexRange_thenReturnsMinAndMax() {
        // given
        IndexedTrace trace = new IndexedTrace();
        trace.addValue(1, 5, "first");
        trace.addValue(2, 100, "last");
        trace.addValue(3, 50, "middle");
        
        // when
        Optional<int[]> range = trace.getEventIndexRange();
        
        // then
        assertTrue(range.isPresent());
        assertEquals(5, range.get()[0]);  // min
        assertEquals(100, range.get()[1]); // max
    }

    @Test
    void givenEmptyTrace_whenGetEventIndexRange_thenReturnsEmpty() {
        // given
        IndexedTrace trace = new IndexedTrace();
        
        // when
        Optional<int[]> range = trace.getEventIndexRange();
        
        // then
        assertFalse(range.isPresent());
    }

    @Test
    void givenMultipleValues_whenGetTotalEventCount_thenReturnsCorrectCount() {
        // given
        IndexedTrace trace = new IndexedTrace();
        trace.addValue(1, 0, "value1");
        trace.addValue(1, 5, "value2");
        trace.addValue(2, 10, "value3");
        trace.addValue(3, 15, "value4");
        trace.addValue(3, 20, "value5");
        
        // when
        int totalCount = trace.getTotalEventCount();
        
        // then
        assertEquals(5, totalCount);
    }

    @Test
    void givenEmptyTrace_whenGetTotalEventCount_thenReturnsZero() {
        // given
        IndexedTrace trace = new IndexedTrace();
        
        // when
        int totalCount = trace.getTotalEventCount();
        
        // then
        assertEquals(0, totalCount);
    }

    @Test
    void givenNewTrace_whenIsEmpty_thenReturnsTrue() {
        // given
        IndexedTrace trace = new IndexedTrace();
        
        // when/then
        assertTrue(trace.isEmpty());
    }

    @Test
    void givenTraceWithValues_whenIsEmpty_thenReturnsFalse() {
        // given
        IndexedTrace trace = new IndexedTrace();
        trace.addValue(1, 0, 42);

        // when/then
        assertFalse(trace.isEmpty());
    }

    @Test
    void givenValuesInTemporalOrder_whenRetrieved_thenNavigableMapPreservesOrder() {
        // given
        IndexedTrace trace = new IndexedTrace();

        // when - add values in non-sequential order
        trace.addValue(1, 10, "third");
        trace.addValue(1, 0, "first");
        trace.addValue(1, 5, "second");
        trace.addValue(1, 15, "fourth");

        // then - NavigableMap should preserve temporal order
        NavigableMap<Integer, Object> values = trace.getValues(1);
        assertEquals(4, values.size());

        // Verify order by checking first and last keys
        assertEquals(0, values.firstKey());
        assertEquals(15, values.lastKey());

        // Verify values are accessible in temporal order
        assertEquals("first", values.get(0));
        assertEquals("second", values.get(5));
        assertEquals("third", values.get(10));
        assertEquals("fourth", values.get(15));
    }

    @Test
    void givenMultipleSlots_whenGetAllSlots_thenReturnsAllSlotIds() {
        // given
        IndexedTrace trace = new IndexedTrace();
        trace.addValue(1, 0, "value1");
        trace.addValue(5, 10, "value2");
        trace.addValue(10, 20, "value3");

        // when
        Set<Integer> allSlots = trace.getAllSlots();

        // then
        assertEquals(3, allSlots.size());
        assertTrue(allSlots.contains(1));
        assertTrue(allSlots.contains(5));
        assertTrue(allSlots.contains(10));
    }

    @Test
    void givenEmptyTrace_whenGetAllSlots_thenReturnsEmptySet() {
        // given
        IndexedTrace trace = new IndexedTrace();

        // when
        Set<Integer> allSlots = trace.getAllSlots();

        // then
        assertNotNull(allSlots);
        assertTrue(allSlots.isEmpty());
    }

    @Test
    void givenNonExistentSlot_whenGetValues_thenReturnsEmptyMap() {
        // given
        IndexedTrace trace = new IndexedTrace();
        trace.addValue(1, 0, "value");

        // when
        NavigableMap<Integer, Object> values = trace.getValues(999);

        // then
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }

    @Test
    void givenSameEventIndexInDifferentSlots_whenAdded_thenBothValuesStored() {
        // given
        IndexedTrace trace = new IndexedTrace();

        // when - same event index in different slots
        trace.addValue(1, 0, "slot1-event0");
        trace.addValue(2, 0, "slot2-event0");
        trace.addValue(3, 0, 42);

        // then - all values should be stored independently
        assertEquals("slot1-event0", trace.getValues(1).get(0));
        assertEquals("slot2-event0", trace.getValues(2).get(0));
        assertEquals(42, trace.getValues(3).get(0));
    }

    @Test
    void givenValueOverwrite_whenSameSlotAndEventIndex_thenValueIsReplaced() {
        // given
        IndexedTrace trace = new IndexedTrace();
        trace.addValue(1, 0, "original");

        // when - overwrite with same slot and event index
        trace.addValue(1, 0, "updated");

        // then - value should be replaced
        NavigableMap<Integer, Object> values = trace.getValues(1);
        assertEquals(1, values.size());
        assertEquals("updated", values.get(0));
    }

    @Test
    void givenReturnedValuesMap_whenModified_thenOriginalTraceUnaffected() {
        // given
        IndexedTrace trace = new IndexedTrace();
        trace.addValue(1, 0, "original");

        // when - modify returned map
        NavigableMap<Integer, Object> values = trace.getValues(1);
        values.put(5, "modified");
        values.remove(0);

        // then - original trace should be unaffected
        NavigableMap<Integer, Object> freshValues = trace.getValues(1);
        assertEquals(1, freshValues.size());
        assertEquals("original", freshValues.get(0));
        assertFalse(freshValues.containsKey(5));
    }

    @Test
    void givenReturnedSlotsSet_whenModified_thenOriginalTraceUnaffected() {
        // given
        IndexedTrace trace = new IndexedTrace();
        trace.addValue(1, 0, "value");

        // when - modify returned set
        Set<Integer> slots = trace.getAllSlots();
        slots.add(999);

        // then - original trace should be unaffected
        Set<Integer> freshSlots = trace.getAllSlots();
        assertEquals(1, freshSlots.size());
        assertFalse(freshSlots.contains(999));
    }

    @Test
    void givenVariousValueTypes_whenAdded_thenAllTypesStored() {
        // given
        IndexedTrace trace = new IndexedTrace();

        // when - add different primitive wrapper types
        trace.addValue(1, 0, 42);                    // Integer
        trace.addValue(2, 0, "test");                // String
        trace.addValue(3, 0, 3.14);                  // Double
        trace.addValue(4, 0, true);                  // Boolean
        trace.addValue(5, 0, 'c');                   // Character
        trace.addValue(6, 0, 100L);                  // Long

        // then - all values should be retrievable
        assertEquals(42, trace.getValues(1).get(0));
        assertEquals("test", trace.getValues(2).get(0));
        assertEquals(3.14, trace.getValues(3).get(0));
        assertEquals(true, trace.getValues(4).get(0));
        assertEquals('c', trace.getValues(5).get(0));
        assertEquals(100L, trace.getValues(6).get(0));
    }
}
