package io.github.michaaels.hop.mcp;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

final class JsonUtil {
  private JsonUtil() {}
  static String toJson(Object value) {
    if (value == null) return "null";
    if (value instanceof String s) return quote(s);
    if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
    if (value instanceof Map<?, ?> map) {
      StringBuilder b = new StringBuilder("{"); boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) { if (!first) b.append(','); first = false; b.append(quote(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue())); }
      return b.append('}').toString();
    }
    if (value instanceof Collection<?> c) {
      StringBuilder b = new StringBuilder("["); boolean first = true;
      for (Object x : c) { if (!first) b.append(','); first = false; b.append(toJson(x)); }
      return b.append(']').toString();
    }
    if (value.getClass().isArray()) { StringBuilder b = new StringBuilder("["); for (int i = 0; i < Array.getLength(value); i++) { if (i > 0) b.append(','); b.append(toJson(Array.get(value, i))); } return b.append(']').toString(); }
    return quote(String.valueOf(value));
  }
  private static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); switch (c) {
      case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\b' -> b.append("\\b"); case '\f' -> b.append("\\f"); case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
      default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
    }} return b.append('"').toString();
  }
}
