package cz.cuni.mff.d3s.autodebugger.model.common.trace;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Represents a snapshot of an object's state at a particular point in execution.
 * Captures the class name and field values (primitives, Strings, or nested ObjectSnapshots).
 */
public class ObjectSnapshot implements Serializable {
  private static final long serialVersionUID = 1L;
  
  private final String className;
  private final LinkedHashMap<String, Object> fields;

  /**
   * Creates a new ObjectSnapshot with the given class name.
   * 
   * @param className the fully qualified class name
   */
  public ObjectSnapshot(String className) {
    this.className = className;
    this.fields = new LinkedHashMap<>();
  }

  /**
   * Gets the fully qualified class name.
   * 
   * @return the class name
   */
  public String getClassName() {
    return className;
  }

  /**
   * Gets the map of field names to values.
   * 
   * @return the fields map
   */
  public LinkedHashMap<String, Object> getFields() {
    return fields;
  }

  /**
   * Adds or updates a field value.
   * 
   * @param name the field name
   * @param value the field value (primitive, String, or ObjectSnapshot)
   */
  public void putField(String name, Object value) {
    fields.put(name, value);
  }

  /**
   * Gets the value of a field.
   * 
   * @param name the field name
   * @return the field value, or null if not present
   */
  public Object getField(String name) {
    return fields.get(name);
  }

  /**
   * Gets the simple class name (without package).
   * 
   * @return the simple class name
   */
  public String getSimpleClassName() {
    int lastDot = className.lastIndexOf('.');
    if (lastDot >= 0 && lastDot < className.length() - 1) {
      return className.substring(lastDot + 1);
    }
    return className;
  }

  /**
   * Parses a JSON string into an ObjectSnapshot.
   * 
   * @param json the JSON string to parse
   * @return the parsed ObjectSnapshot
   * @throws IllegalArgumentException if the JSON is invalid
   */
  public static ObjectSnapshot fromJson(String json) {
    return JsonObjectParser.parse(json);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getSimpleClassName()).append("{");
    boolean first = true;
    for (var entry : fields.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(entry.getKey()).append("=").append(entry.getValue());
    }
    sb.append("}");
    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ObjectSnapshot that = (ObjectSnapshot) o;
    return Objects.equals(className, that.className) && Objects.equals(fields, that.fields);
  }

  @Override
  public int hashCode() {
    return Objects.hash(className, fields);
  }
}

