package cz.cuni.mff.d3s.autodebugger.model.java;

import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.IndexedTrace;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.TemporalTrace;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaValueIdentifier;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * Adapter class that bridges between the legacy Trace implementation
 * and the new TemporalTrace implementation following the specification.
 * <p>
 * This allows gradual migration from the slot-based approach to the
 * event-based approach while maintaining backward compatibility.
 */
@Slf4j
public class TraceAdapter {

    /**
     * Converts a legacy Trace to a TemporalTrace using identifier mapping.
     *
     * @param legacyTrace The original slot-based trace
     * @param identifierMapping Mapping from slots to ExportableValue identifiers
     * @return TemporalTrace with converted data
     */
    public static TemporalTrace convertToEnhanced(Trace legacyTrace,
                                                 Map<Integer, JavaValueIdentifier> identifierMapping) {
        log.info("Converting legacy trace to enhanced trace with {} identifiers",
                identifierMapping.size());

        TemporalTrace enhancedTrace = new TemporalTrace();
        int eventIndex = 0;

        // Convert each slot's values to the enhanced format
        for (Map.Entry<Integer, JavaValueIdentifier> entry : identifierMapping.entrySet()) {
            Integer slot = entry.getKey();
            JavaValueIdentifier identifier = entry.getValue();

            // Convert values based on the identifier type
            String type = identifier.getType();
            eventIndex = convertSlotValues(legacyTrace, enhancedTrace, slot, identifier, type, eventIndex);
        }

        // Add metadata about the conversion
        enhancedTrace.addMetadata("converted_from", "legacy_trace");
        enhancedTrace.addMetadata("conversion_timestamp", System.currentTimeMillis());
        enhancedTrace.addMetadata("original_slot_count", identifierMapping.size());

        log.info("Conversion completed. Enhanced trace contains {} variables with {} total events",
                enhancedTrace.getTrackedVariableCount(), enhancedTrace.getTotalEventCount());

        return enhancedTrace;
    }

    /**
     * Converts an IndexedTrace to a TemporalTrace using identifier mapping.
     * <p>
     * Unlike {@link #convertToEnhanced(Trace, Map)} which creates synthetic sequential event indices,
     * this method preserves the TRUE event indices from the original trace collection.
     * This maintains the actual temporal ordering of events as they occurred during execution,
     * which is critical for accurate test generation and debugging.
     * <p>
     * The IndexedTrace format stores values indexed by slot ID and event index, where event indices
     * represent the actual execution timeline. This method maps slot IDs to JavaValueIdentifiers
     * and transfers all (eventIndex, value) pairs while preserving their temporal relationships.
     *
     * @param indexedTrace The IndexedTrace containing slot-based data with true event indices
     * @param identifierMapping Mapping from slot IDs to JavaValueIdentifier instances
     * @return TemporalTrace with all values transferred using their original event indices
     */
    public static TemporalTrace convertFromIndexed(IndexedTrace indexedTrace,
                                                   Map<Integer, JavaValueIdentifier> identifierMapping) {
        log.info("Converting IndexedTrace to TemporalTrace with {} identifiers",
                identifierMapping.size());

        TemporalTrace temporalTrace = new TemporalTrace();
        int totalValuesConverted = 0;
        int skippedSlots = 0;

        // Process each slot in the indexed trace
        for (Integer slot : indexedTrace.getAllSlots()) {
            JavaValueIdentifier identifier = identifierMapping.get(slot);

            if (identifier == null) {
                log.warn("No identifier mapping found for slot {}, skipping this slot", slot);
                skippedSlots++;
                continue;
            }

            // Get all (eventIndex, value) pairs for this slot
            NavigableMap<Integer, Object> slotValues = indexedTrace.getValues(slot);

            // Transfer each value with its original event index
            for (Map.Entry<Integer, Object> entry : slotValues.entrySet()) {
                Integer eventIndex = entry.getKey();
                Object value = entry.getValue();

                temporalTrace.addValue(identifier, eventIndex, value);
                totalValuesConverted++;

                log.debug("Converted slot {} -> identifier {} at event {}: {}",
                         slot, identifier.getName(), eventIndex, value);
            }
        }

        // Add metadata about the conversion
        temporalTrace.addMetadata("converted_from", "indexed_trace");
        temporalTrace.addMetadata("conversion_timestamp", System.currentTimeMillis());
        temporalTrace.addMetadata("original_slot_count", indexedTrace.getAllSlots().size());
        temporalTrace.addMetadata("mapped_slot_count", identifierMapping.size());
        temporalTrace.addMetadata("skipped_slot_count", skippedSlots);
        temporalTrace.addMetadata("preserves_true_event_indices", true);

        log.info("Conversion completed. TemporalTrace contains {} variables with {} total events (skipped {} unmapped slots)",
                temporalTrace.getTrackedVariableCount(), totalValuesConverted, skippedSlots);

        return temporalTrace;
    }

    /**
     * Converts values from a specific slot in the legacy trace to the enhanced trace.
     */
    private static int convertSlotValues(Trace legacyTrace, TemporalTrace enhancedTrace,
                                         Integer slot, JavaValueIdentifier identifier,
                                         String type, int startEventIndex) {
        int currentEventIndex = startEventIndex;

        switch (type.toLowerCase()) {
            case "byte":
                Set<Byte> byteValues = legacyTrace.getByteValues(slot);
                for (Byte value : byteValues) {
                    enhancedTrace.addValue(identifier, currentEventIndex++, value);
                }
                break;

            case "char":
                Set<Character> charValues = legacyTrace.getCharValues(slot);
                for (Character value : charValues) {
                    enhancedTrace.addValue(identifier, currentEventIndex++, value);
                }
                break;

            case "short":
                Set<Short> shortValues = legacyTrace.getShortValues(slot);
                for (Short value : shortValues) {
                    enhancedTrace.addValue(identifier, currentEventIndex++, value);
                }
                break;

            case "int":
                Set<Integer> intValues = legacyTrace.getIntValues(slot);
                for (Integer value : intValues) {
                    enhancedTrace.addValue(identifier, currentEventIndex++, value);
                }
                break;

            case "long":
                Set<Long> longValues = legacyTrace.getLongValues(slot);
                for (Long value : longValues) {
                    enhancedTrace.addValue(identifier, currentEventIndex++, value);
                }
                break;

            case "float":
                Set<Float> floatValues = legacyTrace.getFloatValues(slot);
                for (Float value : floatValues) {
                    enhancedTrace.addValue(identifier, currentEventIndex++, value);
                }
                break;

            case "double":
                Set<Double> doubleValues = legacyTrace.getDoubleValues(slot);
                for (Double value : doubleValues) {
                    enhancedTrace.addValue(identifier, currentEventIndex++, value);
                }
                break;

            case "boolean":
                Set<Boolean> booleanValues = legacyTrace.getBooleanValues(slot);
                for (Boolean value : booleanValues) {
                    enhancedTrace.addValue(identifier, currentEventIndex++, value);
                }
                break;

            case "string":
            case "java.lang.string":
                Set<String> stringValues = legacyTrace.getStringValues(slot);
                for (String value : stringValues) {
                    enhancedTrace.addValue(identifier, currentEventIndex++, value);
                }
                break;

            default:
                log.warn("Unknown type '{}' for identifier {}, skipping conversion", type, identifier);
                break;
        }

        return currentEventIndex;
    }

    /**
     * Creates a synthetic TemporalTrace for testing purposes with realistic data patterns.
     *
     * @param identifierMapping Mapping of identifiers to use
     * @return TemporalTrace with synthetic test data
     */
    public static TemporalTrace createSyntheticTrace(Map<Integer, JavaValueIdentifier> identifierMapping) {
        log.info("Creating synthetic enhanced trace for testing");

        TemporalTrace trace = new TemporalTrace();
        int eventIndex = 0;

        // Simulate a realistic execution sequence
        for (Map.Entry<Integer, JavaValueIdentifier> entry : identifierMapping.entrySet()) {
            JavaValueIdentifier identifier = entry.getValue();
            String type = identifier.getType();

            // Add multiple values over time to simulate real execution
            switch (type.toLowerCase()) {
                case "int":
                    trace.addValue(identifier, eventIndex++, 10);
                    trace.addValue(identifier, eventIndex++, 20);
                    trace.addValue(identifier, eventIndex++, 30);
                    break;

                case "boolean":
                    trace.addValue(identifier, eventIndex++, true);
                    trace.addValue(identifier, eventIndex++, false);
                    break;

                case "string":
                case "java.lang.string":
                    trace.addValue(identifier, eventIndex++, "initial");
                    trace.addValue(identifier, eventIndex++, "modified");
                    break;

                case "double":
                    trace.addValue(identifier, eventIndex++, 3.14);
                    trace.addValue(identifier, eventIndex++, 2.71);
                    break;

                default:
                    // Generic object
                    trace.addValue(identifier, eventIndex++, "value_" + eventIndex);
                    break;
            }
        }

        // Add metadata
        trace.addMetadata("synthetic", true);
        trace.addMetadata("creation_timestamp", System.currentTimeMillis());
        trace.addMetadata("purpose", "testing");

        log.info("Created synthetic trace with {} variables and {} events",
                trace.getTrackedVariableCount(), trace.getTotalEventCount());

        return trace;
    }

    /**
     * Merges multiple TemporalTrace instances into a single trace.
     * Event indices are adjusted to maintain chronological order.
     *
     * @param traces Array of traces to merge
     * @return Merged TemporalTrace
     */
    public static TemporalTrace mergeTraces(TemporalTrace... traces) {
        log.info("Merging {} traces", traces.length);

        TemporalTrace mergedTrace = new TemporalTrace();
        int[] eventOffsetArray = {0}; // Use array to make it effectively final

        for (int i = 0; i < traces.length; i++) {
            TemporalTrace trace = traces[i];
            final int traceIndex = i; // Make final for lambda

            // Copy all variables from this trace with adjusted event indices
            for (ExportableValue identifier : trace.getTrackedIdentifiers()) {
                var values = trace.getValues(identifier);
                for (Map.Entry<Integer, Object> entry : values.entrySet()) {
                    int adjustedEventIndex = entry.getKey() + eventOffsetArray[0];
                    mergedTrace.addValue(identifier, adjustedEventIndex, entry.getValue());
                }
            }

            // Update offset for next trace
            trace.getEventIndexRange().ifPresent(range -> {
                eventOffsetArray[0] = range[1] + 1; // Start next trace after this one
            });

            // Merge metadata
            trace.getAllMetadata().forEach((key, value) ->
                mergedTrace.addMetadata("trace_" + traceIndex + "_" + key, value));
        }

        mergedTrace.addMetadata("merged_from", traces.length + "_traces");
        mergedTrace.addMetadata("merge_timestamp", System.currentTimeMillis());

        log.info("Merged trace contains {} variables with {} total events",
                mergedTrace.getTrackedVariableCount(), mergedTrace.getTotalEventCount());

        return mergedTrace;
    }
}
