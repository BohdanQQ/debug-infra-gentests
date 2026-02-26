package cz.cuni.mff.d3s.autodebugger.testutils;

import cz.cuni.mff.d3s.autodebugger.model.common.trace.IndexedTrace;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for the TraceVerifier utility class.
 * Tests both Trace (naive mode) and IndexedTrace (temporal mode) assertions.
 */
class TraceVerifierTest {

    @Nested
    class TraceAssertions {

        @Test
        void givenSlotWithIntValues_whenAssertSlotNotEmpty_thenPasses() {
            // given
            Trace trace = new Trace();
            trace.addIntValue(0, 42);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertSlotNotEmpty(trace, 0));
        }

        @Test
        void givenEmptySlot_whenAssertSlotNotEmpty_thenThrowsAssertionError() {
            // given
            Trace trace = new Trace();

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertSlotNotEmpty(trace, 0));
            assertTrue(error.getMessage().contains("Expected slot 0 to contain values, but it was empty"));
        }

        @Test
        void givenSlotWithExpectedIntValues_whenAssertSlotContainsIntValues_thenPasses() {
            // given
            Trace trace = new Trace();
            trace.addIntValue(0, 42);
            trace.addIntValue(0, 17);
            trace.addIntValue(0, 99);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertSlotContainsIntValues(trace, 0, 42, 17));
        }

        @Test
        void givenSlotMissingExpectedIntValue_whenAssertSlotContainsIntValues_thenThrowsAssertionError() {
            // given
            Trace trace = new Trace();
            trace.addIntValue(0, 42);

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertSlotContainsIntValues(trace, 0, 42, 99));
            assertTrue(error.getMessage().contains("Expected slot 0 to contain int values"));
            assertTrue(error.getMessage().contains("99"));
        }

        @Test
        void givenSlotWithExactIntValues_whenAssertSlotContainsExactlyIntValues_thenPasses() {
            // given
            Trace trace = new Trace();
            trace.addIntValue(0, 42);
            trace.addIntValue(0, 17);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertSlotContainsExactlyIntValues(trace, 0, 42, 17));
        }

        @Test
        void givenSlotWithExtraIntValue_whenAssertSlotContainsExactlyIntValues_thenThrowsAssertionError() {
            // given
            Trace trace = new Trace();
            trace.addIntValue(0, 42);
            trace.addIntValue(0, 17);
            trace.addIntValue(0, 99);

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertSlotContainsExactlyIntValues(trace, 0, 42, 17));
            assertTrue(error.getMessage().contains("Expected slot 0 to contain exactly int values"));
        }

        @Test
        void givenSlotWithLongValues_whenAssertSlotContainsExactlyLongValues_thenPasses() {
            // given
            Trace trace = new Trace();
            trace.addLongValue(1, 1000000000L);
            trace.addLongValue(1, 2000000000L);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertSlotContainsExactlyLongValues(trace, 1, 1000000000L, 2000000000L));
        }

        @Test
        void givenSlotWithDoubleValues_whenAssertSlotContainsDoubleValues_thenPasses() {
            // given
            Trace trace = new Trace();
            trace.addDoubleValue(2, 3.14);
            trace.addDoubleValue(2, 2.71);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertSlotContainsDoubleValues(trace, 2, 3.14, 2.71));
        }

        @Test
        void givenSlotWithBooleanValues_whenAssertSlotContainsBooleanValues_thenPasses() {
            // given
            Trace trace = new Trace();
            trace.addBooleanValue(3, true);
            trace.addBooleanValue(3, false);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertSlotContainsBooleanValues(trace, 3, true, false));
        }

        @Test
        void givenSlotWithSufficientValues_whenAssertSlotContainsAtLeast_thenPasses() {
            // given
            Trace trace = new Trace();
            trace.addIntValue(0, 1);
            trace.addIntValue(0, 2);
            trace.addIntValue(0, 3);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertSlotContainsAtLeast(trace, 0, 3));
            assertDoesNotThrow(() -> TraceVerifier.assertSlotContainsAtLeast(trace, 0, 2));
        }

        @Test
        void givenSlotWithInsufficientValues_whenAssertSlotContainsAtLeast_thenThrowsAssertionError() {
            // given
            Trace trace = new Trace();
            trace.addIntValue(0, 1);
            trace.addIntValue(0, 2);

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertSlotContainsAtLeast(trace, 0, 5));
            assertTrue(error.getMessage().contains("Expected slot 0 to contain at least 5 values, but found 2"));
        }

        @Test
        void givenSlotWithValues_whenGetSlotSummary_thenReturnsNonEmptyString() {
            // given
            Trace trace = new Trace();
            trace.addIntValue(0, 42);
            trace.addLongValue(0, 1000L);

            // when
            String summary = TraceVerifier.getSlotSummary(trace, 0);

            // then
            assertNotNull(summary);
            assertFalse(summary.isEmpty());
            assertTrue(summary.contains("Slot 0 summary"));
        }

        @Test
        void givenEmptySlot_whenGetSlotSummary_thenReturnsEmptyIndicator() {
            // given
            Trace trace = new Trace();

            // when
            String summary = TraceVerifier.getSlotSummary(trace, 0);

            // then
            assertNotNull(summary);
            assertTrue(summary.contains("(empty)"));
        }
    }

    @Nested
    class IndexedTraceAssertions {

        @Test
        void givenSlotWithEvents_whenAssertSlotNotEmpty_thenPasses() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 0, 42);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertSlotNotEmpty(trace, 0));
        }

        @Test
        void givenEmptySlot_whenAssertSlotNotEmpty_thenThrowsAssertionError() {
            // given
            IndexedTrace trace = new IndexedTrace();

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertSlotNotEmpty(trace, 0));
            assertTrue(error.getMessage().contains("Expected slot 0 to contain values, but it was empty"));
        }

        @Test
        void givenEventWithExpectedValue_whenAssertEventContainsValue_thenPasses() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 5, 42);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertEventContainsValue(trace, 0, 5, 42));
        }

        @Test
        void givenEventWithWrongValue_whenAssertEventContainsValue_thenThrowsAssertionError() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 5, 42);

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertEventContainsValue(trace, 0, 5, 99));
            assertTrue(error.getMessage().contains("Expected slot 0 at event 5 to contain value 99, but found 42"));
        }

        @Test
        void givenMissingEvent_whenAssertEventContainsValue_thenThrowsAssertionError() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 5, 42);

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertEventContainsValue(trace, 0, 10, 42));
            assertTrue(error.getMessage().contains("Expected slot 0 to have event at index 10, but it was not found"));
        }

        @Test
        void givenCorrectSequence_whenAssertEventSequence_thenPasses() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 0, 10);
            trace.addValue(0, 1, 20);
            trace.addValue(0, 2, 30);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertEventSequence(trace, 0, 0, 10, 20, 30));
        }

        @Test
        void givenIncorrectSequence_whenAssertEventSequence_thenThrowsAssertionError() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 0, 10);
            trace.addValue(0, 1, 20);
            trace.addValue(0, 2, 99);

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertEventSequence(trace, 0, 0, 10, 20, 30));
            assertTrue(error.getMessage().contains("Expected slot 0 at event 2"));
            assertTrue(error.getMessage().contains("to contain value 30, but found 99"));
        }

        @Test
        void givenMissingEventInSequence_whenAssertEventSequence_thenThrowsAssertionError() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 0, 10);
            trace.addValue(0, 2, 30);

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertEventSequence(trace, 0, 0, 10, 20, 30));
            assertTrue(error.getMessage().contains("Expected slot 0 to have event at index 1"));
        }

        @Test
        void givenSufficientEvents_whenAssertSlotHasAtLeastEvents_thenPasses() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 0, 10);
            trace.addValue(0, 1, 20);
            trace.addValue(0, 2, 30);

            // when/then
            assertDoesNotThrow(() -> TraceVerifier.assertSlotHasAtLeastEvents(trace, 0, 3));
            assertDoesNotThrow(() -> TraceVerifier.assertSlotHasAtLeastEvents(trace, 0, 2));
        }

        @Test
        void givenInsufficientEvents_whenAssertSlotHasAtLeastEvents_thenThrowsAssertionError() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 0, 10);
            trace.addValue(0, 1, 20);

            // when/then
            AssertionError error = assertThrows(AssertionError.class,
                    () -> TraceVerifier.assertSlotHasAtLeastEvents(trace, 0, 5));
            assertTrue(error.getMessage().contains("Expected slot 0 to have at least 5 events, but found 2"));
        }

        @Test
        void givenSlotWithEvents_whenGetSlotSummary_thenReturnsNonEmptyString() {
            // given
            IndexedTrace trace = new IndexedTrace();
            trace.addValue(0, 0, 42);
            trace.addValue(0, 1, 17);

            // when
            String summary = TraceVerifier.getSlotSummary(trace, 0);

            // then
            assertNotNull(summary);
            assertFalse(summary.isEmpty());
            assertTrue(summary.contains("Slot 0 summary"));
            assertTrue(summary.contains("2 events"));
        }

        @Test
        void givenEmptySlot_whenGetSlotSummary_thenReturnsEmptyIndicator() {
            // given
            IndexedTrace trace = new IndexedTrace();

            // when
            String summary = TraceVerifier.getSlotSummary(trace, 0);

            // then
            assertNotNull(summary);
            assertTrue(summary.contains("(empty)"));
        }
    }
}

