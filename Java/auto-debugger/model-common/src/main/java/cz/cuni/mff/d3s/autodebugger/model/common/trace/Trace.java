package cz.cuni.mff.d3s.autodebugger.model.common.trace;

import java.io.Serializable;
import java.util.*;

public class Trace implements Serializable {
  private static final long serialVersionUID = 1L;
  private final Map<Integer, Set<Byte>> byteValues = new HashMap<>();
  private final Map<Integer, Set<Character>> charValues = new HashMap<>();
  private final Map<Integer, Set<Short>> shortValues = new HashMap<>();
  private final Map<Integer, Set<Integer>> intValues = new HashMap<>();
  private final Map<Integer, Set<Long>> longValues = new HashMap<>();
  private final Map<Integer, Set<Float>> floatValues = new HashMap<>();
  private final Map<Integer, Set<Double>> doubleValues = new HashMap<>();
  private final Map<Integer, Set<Boolean>> booleanValues = new HashMap<>();
  private final Map<Integer, Set<String>> stringValues = new HashMap<>();
  private final Map<Integer, Set<ObjectSnapshot>> objectValues = new HashMap<>();

  public void addByteValue(int slotId, byte value) {
    if (byteValues.containsKey(slotId)) {
      byteValues.get(slotId).add(value);
    } else {
      byteValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public void addCharValue(int slotId, char value) {
    if (charValues.containsKey(slotId)) {
      charValues.get(slotId).add(value);
    } else {
      charValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public void addShortValue(int slotId, short value) {
    if (shortValues.containsKey(slotId)) {
      shortValues.get(slotId).add(value);
    } else {
      shortValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public void addIntValue(int slotId, int value) {
    if (intValues.containsKey(slotId)) {
      intValues.get(slotId).add(value);
    } else {
      intValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public void addLongValue(int slotId, long value) {
    if (longValues.containsKey(slotId)) {
      longValues.get(slotId).add(value);
    } else {
      longValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public void addFloatValue(int slotId, float value) {
    if (floatValues.containsKey(slotId)) {
      floatValues.get(slotId).add(value);
    } else {
      floatValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public void addDoubleValue(int slotId, double value) {
    if (doubleValues.containsKey(slotId)) {
      doubleValues.get(slotId).add(value);
    } else {
      doubleValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public void addBooleanValue(int slotId, boolean value) {
    if (booleanValues.containsKey(slotId)) {
      booleanValues.get(slotId).add(value);
    } else {
      booleanValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public void addStringValue(int slotId, String value) {
    if (stringValues.containsKey(slotId)) {
      stringValues.get(slotId).add(value);
    } else {
      stringValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public void addObjectValue(int slotId, ObjectSnapshot value) {
    if (objectValues.containsKey(slotId)) {
      objectValues.get(slotId).add(value);
    } else {
      objectValues.put(slotId, new HashSet<>(List.of(value)));
    }
  }

  public Set<Byte> getByteValues(int slotId) {
    return byteValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<Character> getCharValues(int slotId) {
    return charValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<Short> getShortValues(int slotId) {
    return shortValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<Integer> getIntValues(int slotId) {
    return intValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<Long> getLongValues(int slotId) {
    return longValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<Float> getFloatValues(int slotId) {
    return floatValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<Double> getDoubleValues(int slotId) {
    return doubleValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<Boolean> getBooleanValues(int slotId) {
    return booleanValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<String> getStringValues(int slotId) {
    return stringValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<ObjectSnapshot> getObjectValues(int slotId) {
    return objectValues.getOrDefault(slotId, Collections.emptySet());
  }

  public Set<Integer> getAllSlotIDs() {
    Set<Integer> allSlotIDs = new HashSet<>();

    // Clearly it would be more efficient to create the set of all slot IDs gradually, add new element when some value is to be recorded
      // However, performance is not really an issue for us now

    allSlotIDs.addAll(byteValues.keySet());
    allSlotIDs.addAll(charValues.keySet());
    allSlotIDs.addAll(shortValues.keySet());
    allSlotIDs.addAll(intValues.keySet());
    allSlotIDs.addAll(longValues.keySet());
    allSlotIDs.addAll(floatValues.keySet());
    allSlotIDs.addAll(doubleValues.keySet());
    allSlotIDs.addAll(booleanValues.keySet());
    allSlotIDs.addAll(stringValues.keySet());
    allSlotIDs.addAll(objectValues.keySet());

    return allSlotIDs;
  }

  public void printSlotValues() {
    printSlotValues(byteValues);
    printSlotValues(charValues);
    printSlotValues(shortValues);
    printSlotValues(intValues);
    printSlotValues(longValues);
    printSlotValues(floatValues);
    printSlotValues(doubleValues);
    printSlotValues(booleanValues);
    printSlotValues(stringValues);
    printSlotValues(objectValues);
  }

  private <T> void printSlotValues(final Map<Integer, Set<T>> slotValues) {
    for (Map.Entry<Integer, Set<T>> entry : slotValues.entrySet()) {
      System.out.println("Slot ID: " + entry.getKey() + " values: " + entry.getValue());
    }
  }
}
