package cz.cuni.mff.d3s.autodebugger.model.common.trace;

import java.io.Serializable;
import java.util.*;

/**
 * IndexedTrace stores runtime values indexed by slot ID and event index.
 * This is an intermediate format designed for ShadowVM serialization that bridges
 * between raw trace collection and the full TemporalTrace used by generators.
 * 
 * <p>Unlike TemporalTrace which uses ExportableValue identifiers, IndexedTrace uses
 * simple integer slot IDs, making it compatible with ShadowVM's serialization constraints.
 * The event indices provide temporal ordering of collected values.
 * 
 * <p>Data structure: Map&lt;Integer, NavigableMap&lt;Integer, Object&gt;&gt; where:
 * <ul>
 *   <li>Outer key: slot ID (Integer) - identifies the variable/location being tracked</li>
 *   <li>Inner key: event index (Integer) - temporal position in execution</li>
 *   <li>Value: collected value (Object) - can be any primitive wrapper type</li>
 * </ul>
 * 
 * <p>This class is Serializable for ShadowVM compatibility and uses only simple Java types
 * (no ExportableValue or other complex identifiers).
 */
public class IndexedTrace implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * Core data structure mapping slot IDs to their temporal value histories.
     * TreeMap is used for the inner map to enable efficient range queries on event indices.
     */
    private final Map<Integer, NavigableMap<Integer, Object>> traceData;
    
    /**
     * Creates a new empty IndexedTrace.
     */
    public IndexedTrace() {
        this.traceData = new HashMap<>();
    }
    
    /**
     * Adds a captured runtime value to the trace.
     *
     * @param slot The slot ID identifying the variable/location being tracked.
     * @param eventIndex The point in the execution timeline when the value was captured.
     * @param value The runtime value (should be a primitive wrapper type).
     */
    public void addValue(int slot, int eventIndex, Object value) {
        traceData.computeIfAbsent(slot, k -> new TreeMap<>())
                 .put(eventIndex, value);
    }
    
    /**
     * Retrieves the complete history of values for a specific slot.
     *
     * @param slot The slot ID to query.
     * @return A NavigableMap of event indices to values, or an empty map if the slot
     *         was never tracked. The returned map is a copy and modifications won't
     *         affect the trace.
     */
    public NavigableMap<Integer, Object> getValues(int slot) {
        NavigableMap<Integer, Object> history = traceData.get(slot);
        return history != null ? new TreeMap<>(history) : new TreeMap<>();
    }
    
    /**
     * Gets all slot IDs that have been tracked in this trace.
     *
     * @return A set of all slot IDs. The returned set is a copy and modifications
     *         won't affect the trace.
     */
    public Set<Integer> getAllSlots() {
        return new HashSet<>(traceData.keySet());
    }
    
    /**
     * Gets the range of event indices recorded in this trace.
     *
     * @return An Optional containing an int array [min, max] of event indices,
     *         or empty if no data has been recorded.
     */
    public Optional<int[]> getEventIndexRange() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        boolean hasData = false;
        
        for (NavigableMap<Integer, Object> history : traceData.values()) {
            if (!history.isEmpty()) {
                hasData = true;
                min = Math.min(min, history.firstKey());
                max = Math.max(max, history.lastKey());
            }
        }
        
        return hasData ? Optional.of(new int[]{min, max}) : Optional.empty();
    }
    
    /**
     * Gets the total number of recorded events across all slots.
     *
     * @return Total number of value recordings.
     */
    public int getTotalEventCount() {
        return traceData.values().stream()
                       .mapToInt(Map::size)
                       .sum();
    }
    
    /**
     * Checks whether any data has been recorded in this trace.
     *
     * @return true if the trace is empty (no values recorded), false otherwise.
     */
    public boolean isEmpty() {
        return traceData.isEmpty() || 
               traceData.values().stream().allMatch(Map::isEmpty);
    }
    
    /**
     * Gets a summary of the trace contents for debugging.
     *
     * @return String summary of trace statistics.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("IndexedTrace Summary:\n");
        sb.append("  - Tracked slots: ").append(traceData.size()).append("\n");
        sb.append("  - Total events: ").append(getTotalEventCount()).append("\n");
        
        getEventIndexRange().ifPresentOrElse(
            range -> sb.append("  - Event range: [").append(range[0]).append(", ").append(range[1]).append("]\n"),
            () -> sb.append("  - Event range: empty\n")
        );
        
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return getSummary();
    }
}

