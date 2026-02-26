import ch.usi.dag.dislre.REDispatch;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class CollectorRE {
  static {
    System.out.println("*** CollectorRE CLASS LOADED ***");
  }

  // Configuration constants for object serialization
  private static final int MAX_OBJECT_DEPTH = 2;
  private static final int MAX_FIELDS_PER_OBJECT = 50;
  private static final int MAX_JSON_LENGTH = 64 * 1024;  // 64KB

  // Types to skip (cause issues or are not useful for test generation)
  private static final Set<String> SKIP_TYPE_PREFIXES = Set.of(
    "java.lang.Thread",
    "java.lang.ClassLoader",
    "java.io.InputStream",
    "java.io.OutputStream",
    "java.io.Reader",
    "java.io.Writer",
    "java.net.Socket",
    "java.net.ServerSocket",
    "java.sql.Connection",
    "java.sql.Statement",
    "java.sql.ResultSet",
    "sun.",
    "jdk.",
    "com.sun."
  );

  // Thread-local visited set for cycle detection
  private static final ThreadLocal<IdentityHashMap<Object, Boolean>> VISITED =
    ThreadLocal.withInitial(IdentityHashMap::new);

  private static short startEventId = registerMethodWithDebug("Collector.startEvent");
  private static short collectByteId = registerMethodWithDebug("Collector.collectByte");
  private static short collectCharId = registerMethodWithDebug("Collector.collectChar");
  private static short collectShortId = registerMethodWithDebug("Collector.collectShort");
  private static short collectIntId = registerMethodWithDebug("Collector.collectInt");
  private static short collectLongId = registerMethodWithDebug("Collector.collectLong");
  private static short collectFloatId = registerMethodWithDebug("Collector.collectFloat");
  private static short collectDoubleId = registerMethodWithDebug("Collector.collectDouble");
  private static short collectBooleanId = registerMethodWithDebug("Collector.collectBoolean");
  private static short collectStringId = registerMethodWithDebug("Collector.collectString");
  private static short collectObjectId = registerMethodWithDebug("Collector.collectObject");
  private static short collectObjectJsonId = registerMethodWithDebug("Collector.collectObjectJson");

  private static short registerMethodWithDebug(String methodName) {
    System.out.println("*** CollectorRE: Registering method " + methodName + " ***");
    short id = REDispatch.registerMethod(methodName);
    System.out.println("*** CollectorRE: Method " + methodName + " registered with ID " + id + " ***");
    return id;
  }

  public static void startEvent() {
    REDispatch.analysisStart(startEventId);
    REDispatch.analysisEnd();
  }

  public static void collectByte(final int slot, final byte b) {
    REDispatch.analysisStart(collectByteId);
    REDispatch.sendInt(slot);
    REDispatch.sendByte(b);
    REDispatch.analysisEnd();
  }

  public static void collectChar(final int slot, final char c) {
    REDispatch.analysisStart(collectCharId);
    REDispatch.sendInt(slot);
    REDispatch.sendChar(c);
    REDispatch.analysisEnd();
  }

  public static void collectShort(final int slot, final short s) {
    REDispatch.analysisStart(collectShortId);
    REDispatch.sendInt(slot);
    REDispatch.sendShort(s);
    REDispatch.analysisEnd();
  }

  public static void collectInt(final int slot, final int i) {
    REDispatch.analysisStart(collectIntId);
    REDispatch.sendInt(slot);
    REDispatch.sendInt(i);
    REDispatch.analysisEnd();
  }

  public static void collectLong(final int slot, final long l) {
    REDispatch.analysisStart(collectLongId);
    REDispatch.sendInt(slot);
    REDispatch.sendLong(l);
    REDispatch.analysisEnd();
  }

  public static void collectFloat(final int slot, final float f) {
    REDispatch.analysisStart(collectFloatId);
    REDispatch.sendInt(slot);
    REDispatch.sendFloat(f);
    REDispatch.analysisEnd();
  }

  public static void collectDouble(final int slot, final double d) {
    REDispatch.analysisStart(collectDoubleId);
    REDispatch.sendInt(slot);
    REDispatch.sendDouble(d);
    REDispatch.analysisEnd();
  }

  public static void collectBoolean(final int slot, final boolean z) {
    REDispatch.analysisStart(collectBooleanId);
    REDispatch.sendInt(slot);
    REDispatch.sendBoolean(z);
    REDispatch.analysisEnd();
  }

  public static void collectString(final int slot, final Object s) {
    REDispatch.analysisStart(collectStringId);
    REDispatch.sendInt(slot);
    REDispatch.sendObjectPlusData(s);
    REDispatch.analysisEnd();
  }

  public static void collectObject(final int slot, final Object obj) {
    if (obj == null) {
      return;  // Skip null objects
    }

    // Check if this is a problematic type
    String className = obj.getClass().getName();
    for (String prefix : SKIP_TYPE_PREFIXES) {
      if (className.startsWith(prefix)) {
        return;  // Skip problematic types
      }
    }

    try {
      // Serialize to JSON with depth limit
      String json = serializeToJson(obj, MAX_OBJECT_DEPTH);

      // Enforce max JSON length
      if (json.length() > MAX_JSON_LENGTH) {
        json = json.substring(0, MAX_JSON_LENGTH) + "...}";
      }

      REDispatch.analysisStart(collectObjectJsonId);
      REDispatch.sendInt(slot);
      REDispatch.sendObjectPlusData(json);
      REDispatch.analysisEnd();
    } catch (Exception e) {
      // Silently ignore serialization errors to avoid breaking instrumented code
    } finally {
      // Clear visited set for this thread
      VISITED.get().clear();
    }
  }

  // Thread-local visited set for cycle detection
  private static String serializeToJson(Object obj, int maxDepth) {
    if (obj == null) {
      return "null";
    }
    if (maxDepth <= 0) {
      return "\"$ref:" + obj.getClass().getName() + "\"";
    }

    IdentityHashMap<Object, Boolean> visited = VISITED.get();
    if (visited.containsKey(obj)) {
      return "\"$cycle\"";  // Cycle detected
    }

    try {
      visited.put(obj, Boolean.TRUE);
      return buildJsonObject(obj, maxDepth);
    } finally {
      visited.remove(obj);
    }
  }

  private static String buildJsonObject(Object obj, int maxDepth) {
    Class<?> clazz = obj.getClass();
    String className = clazz.getName();

    // For JDK types (java.*, javax.*, sun.*, jdk.*), use toString() instead of reflection
    // This avoids InaccessibleObjectException from Java module system
    if (className.startsWith("java.") || className.startsWith("javax.") ||
        className.startsWith("sun.") || className.startsWith("jdk.")) {
      return "{\"$class\":\"" + className + "\",\"$value\":\"" + escapeJson(obj.toString()) + "\"}";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("{\"$class\":\"").append(className).append("\"");

    int fieldCount = 0;
    for (Field f : getAllInstanceFields(clazz)) {
      if (fieldCount++ >= MAX_FIELDS_PER_OBJECT) {
        break;  // Safety limit
      }

      try {
        f.setAccessible(true);
        Object value = f.get(obj);
        sb.append(",\"").append(f.getName()).append("\":");
        sb.append(serializeValue(value, f.getType(), maxDepth - 1));
      } catch (IllegalAccessException | InaccessibleObjectException e) {
        // Skip inaccessible fields (can happen with module system restrictions)
      }
    }

    sb.append("}");
    return sb.toString();
  }

  private static String serializeValue(Object value, Class<?> type, int depth) {
    if (value == null) {
      return "null";
    }

    if (type.isPrimitive() || value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof Character) {
      return "\"" + escapeJsonChar(((Character) value).charValue()) + "\"";
    }
    if (value instanceof String) {
      return "\"" + escapeJson((String) value) + "\"";
    }
    if (type.isEnum()) {
      return "\"" + ((Enum<?>) value).name() + "\"";
    }

    // For other objects, either recurse or mark as reference
    return serializeToJson(value, depth);
  }

  private static String escapeJson(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      sb.append(escapeJsonChar(s.charAt(i)));
    }
    return sb.toString();
  }

  private static String escapeJsonChar(char c) {
    switch (c) {
      case '\\': return "\\\\";
      case '"': return "\\\"";
      case '\n': return "\\n";
      case '\r': return "\\r";
      case '\t': return "\\t";
      case '\b': return "\\b";
      case '\f': return "\\f";
      default:
        if (c < 32 || c > 126) {
          return String.format("\\u%04x", (int) c);
        }
        return String.valueOf(c);
    }
  }

  private static List<Field> getAllInstanceFields(Class<?> clazz) {
    List<Field> result = new ArrayList<>();
    Class<?> current = clazz;
    while (current != null && current != Object.class) {
      for (Field f : current.getDeclaredFields()) {
        if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
          result.add(f);
        }
      }
      current = current.getSuperclass();
    }
    return result;
  }
}
