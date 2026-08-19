package io.github.ashishgituser.mcpgateway.core.observability;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes each audit event as a single structured JSON log line, so it can be routed to its own file
 * or sink independently of application logs (e.g. via a dedicated logback appender on the {@code
 * io.github.ashishgituser.mcpgateway.audit} logger name).
 */
public class Slf4jAuditLogger implements AuditLogger {

  private static final Logger log =
      LoggerFactory.getLogger("io.github.ashishgituser.mcpgateway.audit");

  @Override
  public void record(AuditEvent event) {
    log.info(toJson(event));
  }

  static String toJson(AuditEvent event) {
    StringBuilder json = new StringBuilder(128);
    json.append("{\"timestamp\":\"").append(event.timestamp()).append('"');
    json.append(",\"principal\":").append(quote(event.principal()));
    json.append(",\"tool\":").append(quote(event.toolName()));
    json.append(",\"outcome\":\"").append(event.outcome()).append('"');
    json.append(",\"durationMs\":").append(event.duration().toMillis());
    json.append(",\"reason\":").append(quote(event.reason()));
    if (event.arguments() != null) {
      json.append(",\"arguments\":").append(toJson(event.arguments()));
    }
    json.append('}');
    return json.toString();
  }

  private static String toJson(Map<String, Object> values) {
    StringBuilder json = new StringBuilder(64);
    json.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append(quote(entry.getKey())).append(':').append(toJson(entry.getValue()));
    }
    return json.append('}').toString();
  }

  @SuppressWarnings("unchecked")
  private static String toJson(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Map<?, ?> nested) {
      return toJson((Map<String, Object>) nested);
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    return quote(String.valueOf(value));
  }

  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + escaped + "\"";
  }
}
