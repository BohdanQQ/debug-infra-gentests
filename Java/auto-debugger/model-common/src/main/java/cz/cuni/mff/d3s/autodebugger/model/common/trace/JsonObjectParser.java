package cz.cuni.mff.d3s.autodebugger.model.common.trace;

/**
 * Simple JSON parser for ObjectSnapshot serialization.
 * Handles the format: {"$class":"com.example.User","name":"John","age":30}
 * 
 * Special markers:
 * - $class: fully qualified class name
 * - $cycle: indicates a circular reference
 * - $ref: prefix for depth-limited references
 */
public class JsonObjectParser {
  
  private final String json;
  private int pos;

  private JsonObjectParser(String json) {
    this.json = json;
    this.pos = 0;
  }

  /**
   * Parses a JSON string into an ObjectSnapshot.
   * 
   * @param json the JSON string to parse
   * @return the parsed ObjectSnapshot
   * @throws IllegalArgumentException if the JSON is invalid
   */
  public static ObjectSnapshot parse(String json) {
    JsonObjectParser parser = new JsonObjectParser(json);
    Object result = parser.parseValue();
    if (!(result instanceof ObjectSnapshot)) {
      throw new IllegalArgumentException("JSON must represent an object with $class field");
    }
    return (ObjectSnapshot) result;
  }

  private void skipWhitespace() {
    while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
      pos++;
    }
  }

  private Object parseValue() {
    skipWhitespace();
    if (pos >= json.length()) {
      throw new IllegalArgumentException("Unexpected end of JSON");
    }

    char c = json.charAt(pos);
    if (c == '{') {
      return parseObject();
    } else if (c == '"') {
      return parseString();
    } else if (c == 't' || c == 'f') {
      return parseBoolean();
    } else if (c == 'n') {
      return parseNull();
    } else if (c == '-' || Character.isDigit(c)) {
      return parseNumber();
    } else {
      throw new IllegalArgumentException("Unexpected character: " + c);
    }
  }

  private ObjectSnapshot parseObject() {
    skipWhitespace();
    if (json.charAt(pos) != '{') {
      throw new IllegalArgumentException("Expected '{'");
    }
    pos++; // skip '{'

    skipWhitespace();
    
    // Empty object
    if (pos < json.length() && json.charAt(pos) == '}') {
      pos++;
      throw new IllegalArgumentException("Object must have $class field");
    }

    String className = null;
    ObjectSnapshot snapshot = null;

    while (true) {
      skipWhitespace();
      
      // Check for end of object
      if (pos < json.length() && json.charAt(pos) == '}') {
        pos++;
        break;
      }

      // Parse key
      String key = parseString();
      
      skipWhitespace();
      if (pos >= json.length() || json.charAt(pos) != ':') {
        throw new IllegalArgumentException("Expected ':'");
      }
      pos++; // skip ':'

      // Parse value
      Object value = parseValue();

      // Handle special $class field
      if ("$class".equals(key)) {
        if (!(value instanceof String)) {
          throw new IllegalArgumentException("$class must be a string");
        }
        className = (String) value;
        snapshot = new ObjectSnapshot(className);
      } else if (snapshot != null) {
        snapshot.putField(key, value);
      } else {
        // We haven't seen $class yet, but we need to store this field
        // This shouldn't happen with well-formed JSON, but handle it gracefully
        if (className == null) {
          throw new IllegalArgumentException("$class must be the first field");
        }
      }

      skipWhitespace();
      if (pos < json.length() && json.charAt(pos) == ',') {
        pos++; // skip ','
      }
    }

    if (snapshot == null) {
      throw new IllegalArgumentException("Object must have $class field");
    }

    return snapshot;
  }

  private String parseString() {
    skipWhitespace();
    if (json.charAt(pos) != '"') {
      throw new IllegalArgumentException("Expected '\"'");
    }
    pos++; // skip opening quote

    StringBuilder sb = new StringBuilder();
    while (pos < json.length()) {
      char c = json.charAt(pos);
      if (c == '"') {
        pos++; // skip closing quote
        return sb.toString();
      } else if (c == '\\') {
        pos++;
        if (pos >= json.length()) {
          throw new IllegalArgumentException("Unexpected end of string");
        }
        char escaped = json.charAt(pos);
        switch (escaped) {
          case '"':
          case '\\':
          case '/':
            sb.append(escaped);
            break;
          case 'b':
            sb.append('\b');
            break;
          case 'f':
            sb.append('\f');
            break;
          case 'n':
            sb.append('\n');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 't':
            sb.append('\t');
            break;
          default:
            throw new IllegalArgumentException("Invalid escape sequence: \\" + escaped);
        }
        pos++;
      } else {
        sb.append(c);
        pos++;
      }
    }
    throw new IllegalArgumentException("Unterminated string");
  }

  private Boolean parseBoolean() {
    skipWhitespace();
    if (json.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    } else if (json.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    } else {
      throw new IllegalArgumentException("Invalid boolean value");
    }
  }

  private Object parseNull() {
    skipWhitespace();
    if (json.startsWith("null", pos)) {
      pos += 4;
      return null;
    } else {
      throw new IllegalArgumentException("Invalid null value");
    }
  }

  private Object parseNumber() {
    skipWhitespace();
    int start = pos;

    // Handle negative sign
    if (pos < json.length() && json.charAt(pos) == '-') {
      pos++;
    }

    // Parse digits
    if (pos >= json.length() || !Character.isDigit(json.charAt(pos))) {
      throw new IllegalArgumentException("Invalid number");
    }

    while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
      pos++;
    }

    // Check for decimal point
    boolean isDouble = false;
    if (pos < json.length() && json.charAt(pos) == '.') {
      isDouble = true;
      pos++;
      if (pos >= json.length() || !Character.isDigit(json.charAt(pos))) {
        throw new IllegalArgumentException("Invalid number");
      }
      while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
        pos++;
      }
    }

    // Check for exponent
    if (pos < json.length() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) {
      isDouble = true;
      pos++;
      if (pos < json.length() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) {
        pos++;
      }
      if (pos >= json.length() || !Character.isDigit(json.charAt(pos))) {
        throw new IllegalArgumentException("Invalid number");
      }
      while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
        pos++;
      }
    }

    String numberStr = json.substring(start, pos);

    if (isDouble) {
      return Double.parseDouble(numberStr);
    } else {
      try {
        return Integer.parseInt(numberStr);
      } catch (NumberFormatException e) {
        return Long.parseLong(numberStr);
      }
    }
  }
}
